import { randomBytes } from 'node:crypto'
import { access, readFile } from 'node:fs/promises'
import { createInterface, emitKeypressEvents } from 'node:readline'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { buildDashboard, repositoryRoot } from './build-dashboard.mjs'
import { assertPortAvailable, ProcessGroup, runCommand, startServices } from './dev-tools.mjs'
import { ensureKafka, endpoint, kafkaRuntime } from './kafka-dev.mjs'
import { setTimeout as delay } from 'node:timers/promises'

const services = ['discovery-server', 'event-service', 'rate-service', 'settlement-service', 'quote-service']
const safeTests = {
  'rate-service': 'RateValidationServiceTest,TrustedRateCacheTest',
  'settlement-service': 'FundingTransactionTest,CustomerTransferHistoryTest,HistoryApiTest,DashboardApiTest,DashboardBrowserFlowTest,SettlementApiTest,IntegrationSettlementApiTest,InternalCustomerResolverTest,LoginVerificationServiceTest,AccountPasswordProfileTest,PlaintextAccountServiceTest,PlaintextAccountApiTest',
}

export function parseOptions(args) {
  const options = { build: true, buildOnly: false, check: false, test: false, help: false, frontend: false, startKafka: false }
  for (const arg of args) {
    switch (arg.toLowerCase()) {
      case '--with-frontend': options.frontend = true; break
      case '--start-kafka': options.startKafka = true; break
      case '--backend-only': options.frontend = false; break
      case '--no-build': options.build = false; break
      case '--build-only': options.buildOnly = true; break
      case '--check': case '-check': options.check = true; break
      case '--test': case '-test': options.test = true; break
      case '--help': case '-h': options.help = true; break
      case '-build': case '-plaintextaccountdemo': break
      default: throw new Error(`Unsupported option. Run npm run dev -- --help for usage.`)
    }
  }
  if (options.check && options.buildOnly) throw new Error('Choose --check or --build-only.')
  return options
}

function first(env, names, fallback = '') {
  return names.map((name) => env[name]).find((value) => typeof value === 'string' && value.trim()) || fallback
}

function ask(question, fallback, signal) {
  signal?.throwIfAborted()
  if (!process.stdin.isTTY) throw new Error('Interactive input is unavailable. Supply the documented environment variables before starting.')
  return new Promise((resolve, reject) => {
    const input = createInterface({ input: process.stdin, output: process.stdout, historySize: 0 })
    const cancel = () => { signal?.removeEventListener('abort', cancel); input.close(); reject(new Error('Startup cancelled.')) }
    input.once('SIGINT', cancel)
    signal?.addEventListener('abort', cancel, { once: true })
    input.question(`${question}${fallback ? ` [${fallback}]` : ''}: `, (answer) => {
      signal?.removeEventListener('abort', cancel)
      input.close()
      resolve(answer || fallback)
    })
  })
}

function askSecret(question, signal) {
  if (!process.stdin.isTTY || !process.stdin.setRawMode) throw new Error('Supply ORACLE_PASSWORD, TWELVE_DATA_API_KEY and M4_OPS_PASSWORD through your environment when no interactive terminal is available.')
  signal?.throwIfAborted()
  return new Promise((resolve, reject) => {
    const wasRaw = process.stdin.isRaw
    let value = ''
    emitKeypressEvents(process.stdin)
    const finish = (error) => {
      process.stdin.removeListener('keypress', keypress)
      signal?.removeEventListener('abort', cancel)
      process.stdin.setRawMode(Boolean(wasRaw))
      process.stdin.pause()
      process.stdout.write('\n')
      if (error) reject(error)
      else resolve(value)
      value = ''
    }
    const cancel = () => finish(new Error('Startup cancelled.'))
    const keypress = (text, key = {}) => {
      if (key.ctrl && key.name === 'c') return cancel()
      if (key.name === 'return' || key.name === 'enter') return finish()
      if (key.name === 'backspace') value = [...value].slice(0, -1).join('')
      else if (!key.ctrl && !key.meta && text && !/[\x00-\x1f\x7f]/.test(text)) value += text
    }
    process.stdout.write(`${question} (hidden, not saved): `)
    signal?.addEventListener('abort', cancel, { once: true })
    process.stdin.on('keypress', keypress)
    process.stdin.setRawMode(true)
    process.stdin.resume()
  })
}

export async function configuration(env, { prompt = ask, secret = askSecret, signal } = {}) {
  const databaseUrl = first(env, ['ORACLE_JDBC_URL', 'FXLOCK_DB_URL', 'DB_URL', 'SPRING_DATASOURCE_URL'])
    || await prompt('Oracle JDBC URL', 'jdbc:oracle:thin:@//localhost:1521/FREEPDB1', signal)
  const username = first(env, ['ORACLE_USERNAME', 'FXLOCK_DB_USERNAME', 'DB_USERNAME', 'SPRING_DATASOURCE_USERNAME'])
    || await prompt('Oracle username', 'FXLOCK', signal)
  const password = first(env, ['ORACLE_PASSWORD', 'FXLOCK_DB_PASSWORD', 'DB_PASSWORD', 'SPRING_DATASOURCE_PASSWORD'])
    || await secret('Existing Oracle password', signal)
  const apiKey = first(env, ['TWELVE_DATA_API_KEY']) || await secret('Twelve Data API key', signal)
  if (!databaseUrl.startsWith('jdbc:oracle:thin:')) throw new Error('Use the existing Oracle thin JDBC URL.')
  if (!password || !apiKey) throw new Error('The existing Oracle password and Twelve Data API key are required.')
  const opsPassword = first(env, ['M4_OPS_PASSWORD']) || await secret('Event Service operations password (choose one for local use)', signal)
  if (!opsPassword.trim()) throw new Error('An Event Service operations password is required.')
  const ports = {
    discovery: Number(env.FXLOCK_DISCOVERY_PORT || 8761), event: Number(env.FXLOCK_EVENT_PORT || 8084),
    rate: Number(env.FXLOCK_RATE_PORT || 8081), quote: Number(env.FXLOCK_QUOTE_PORT || 8082),
    settlement: Number(env.FXLOCK_SETTLEMENT_PORT || 8083), frontend: Number(env.FXLOCK_FRONTEND_PORT || 5173),
  }
  if (Object.values(ports).some((port) => !Number.isInteger(port) || port < 1 || port > 65535)
    || new Set(Object.values(ports)).size !== 6) throw new Error('Choose six distinct valid service ports.')
  const timeoutSeconds = Number(env.FXLOCK_STARTUP_TIMEOUT_SECONDS || 120)
  if (!Number.isInteger(timeoutSeconds) || timeoutSeconds < 1 || timeoutSeconds > 600) throw new Error('Startup timeout must be 1–600 seconds.')
  const random = () => randomBytes(32).toString('base64url')
  const childEnv = {
    ...env,
    SPRING_DATASOURCE_URL: databaseUrl, SPRING_DATASOURCE_USERNAME: username, SPRING_DATASOURCE_PASSWORD: password,
    ORACLE_JDBC_URL: databaseUrl, ORACLE_USERNAME: username, ORACLE_PASSWORD: password,
    FXLOCK_DB_URL: databaseUrl, FXLOCK_DB_USERNAME: username, FXLOCK_DB_PASSWORD: password, DB_PASSWORD: password,
    TWELVE_DATA_API_KEY: apiKey,
    FX_INTERNAL_TOKEN: first(env, ['FX_INTERNAL_TOKEN'], random()),
    FXLOCK_SERVICE_USERNAME: first(env, ['FXLOCK_SERVICE_USERNAME'], 'fxlock-service'),
    FXLOCK_SERVICE_PASSWORD: first(env, ['FXLOCK_SERVICE_PASSWORD'], random()),
    FXLOCK_RISK_USERNAME: first(env, ['FXLOCK_RISK_USERNAME'], 'risk-analyst'),
    FXLOCK_RISK_PASSWORD: first(env, ['FXLOCK_RISK_PASSWORD'], random()),
    RATE_BASE_URL: `http://127.0.0.1:${ports.rate}`,
    SETTLEMENT_BASE_URL: `http://127.0.0.1:${ports.settlement}`,
    FXLOCK_ACCOUNT_SERVICE_URL: `http://127.0.0.1:${ports.settlement}`,
    FXLOCK_DASHBOARD_URL: `http://localhost:${ports.settlement}`,
    FXLOCK_QUOTE_BROWSER_URL: `http://localhost:${ports.quote}`,
    FXLOCK_SETTLEMENT_PORT: String(ports.settlement), EUREKA_ENABLED: 'true',
    EUREKA_URL: `http://127.0.0.1:${ports.discovery}/eureka/`,
    KAFKA_BOOTSTRAP_SERVERS: first(env, ['KAFKA_BOOTSTRAP_SERVERS'], 'localhost:9092'),
    M4_OPS_USERNAME: first(env, ['M4_OPS_USERNAME'], 'fxlock-ops'), M4_OPS_PASSWORD: opsPassword,
    SPRING_PROFILES_ACTIVE: '', SPRING_BOOT_RUN_JVMARGUMENTS: '',
    SPRING_SQL_INIT_MODE: 'never', SPRING_JPA_HIBERNATE_DDL_AUTO: 'validate',
  }
  endpoint(childEnv.KAFKA_BOOTSTRAP_SERVERS)
  const secrets = [...new Set(Object.entries(childEnv).filter(([key]) => /PASSWORD|TOKEN|API_KEY/.test(key)).map(([, value]) => value).filter(Boolean))]
  return { ports, env: childEnv, secrets, timeoutMs: timeoutSeconds * 1000 }
}

export async function jarPath(root, service) {
  const pom = await readFile(path.join(root, service, 'pom.xml'), 'utf8')
  const project = pom.replace(/<parent>[\s\S]*?<\/parent>/, '')
  const artifact = project.match(/<artifactId>([^<]+)<\/artifactId>/)?.[1]
  const version = project.match(/<version>([^<]+)<\/version>/)?.[1]
  if (!artifact || !version || !/^[\w.-]+$/.test(artifact + version)) throw new Error(`Cannot determine ${service} jar from its pom.xml.`)
  const jar = path.join(root, service, 'target', `${artifact}-${version}.jar`)
  try { await access(jar) } catch { throw new Error(`Missing ${service} jar. Run npm run build first.`) }
  return jar
}

export async function servicePlan(root, config, { frontend = false } = {}) {
  const common = ['--server.address=127.0.0.1', '--eureka.client.enabled=true', '--spring.cloud.discovery.enabled=true',
    '--spring.sql.init.mode=never', '--spring.jpa.hibernate.ddl-auto=validate']
  const definitions = [
    ['discovery-server', config.ports.discovery, 'default', '/'],
    ['settlement-service', config.ports.settlement, 'integration,plaintext-account-demo,m4', '/internal/health'],
    ['rate-service', config.ports.rate, 'm4', '/actuator/health'],
    ['quote-service', config.ports.quote, 'team,m4', '/actuator/health'],
    ['event-service', config.ports.event, 'default', '/actuator/health'],
  ]
  const specs = []
  for (const [name, port, profile, health] of definitions) {
    specs.push({ name, command: 'java', args: ['-jar', await jarPath(root, name), `--spring.profiles.active=${profile}`, `--server.port=${port}`, ...(name === 'discovery-server' ? ['--server.address=127.0.0.1', '--eureka.client.enabled=true', '--spring.cloud.discovery.enabled=true', '--eureka.client.register-with-eureka=false', '--eureka.client.fetch-registry=false'] : common)],
      cwd: path.join(root, name), env: config.env, url: `http://127.0.0.1:${port}${health}`, jsonHealth: name !== 'discovery-server',
      headers: name === 'settlement-service' ? { 'X-Internal-Token': config.env.FX_INTERNAL_TOKEN } : {} })
  }
  if (!frontend) return specs
  const vite = path.join(root, 'frontend', 'node_modules', 'vite', 'bin', 'vite.js')
  try { await access(vite) } catch { throw new Error('Existing frontend dependencies are missing. No dependencies were installed automatically.') }
  specs.push({ name: 'frontend', command: process.execPath, args: [vite, '--host', '127.0.0.1', '--port', String(config.ports.frontend)],
    cwd: path.join(root, 'frontend'), env: config.env, url: `http://127.0.0.1:${config.ports.frontend}/dashboard/`, jsonHealth: false })
  return specs
}

async function checkTools(signal) {
  const java = await runCommand('java', ['--version'], { signal, capture: true })
  if (!/^(?:openjdk|java) 25(?:\.|\s)/m.test(java)) throw new Error('Java 25 must be on PATH.')
  const maven = await runCommand('mvn', ['-o', '-version'], { signal, capture: true })
  if (!/Java version: 25(?:\.|,)/.test(maven)) throw new Error('Maven must use Java 25. Check JAVA_HOME and PATH.')
}

async function build(options, signal) {
  if (options.frontend && options.test) await runCommand('npm', ['test'], { cwd: path.join(repositoryRoot, 'frontend'), signal })
  if (options.frontend) await buildDashboard({ signal })
  for (const service of services) {
    console.log(`Building ${service} offline...`)
    const args = ['-o', options.test ? (safeTests[service] ? `-Dtest=${safeTests[service]}` : null) : '-DskipTests', 'package'].filter(Boolean)
    try { await runCommand('mvn', args, { cwd: path.join(repositoryRoot, service), signal }) }
    catch (error) {
      if (signal.aborted) throw error
      throw new Error(`Build failed for ${service}; no services were started. If Maven reports missing cached artifacts, download them manually using: mvn -f ${service}/pom.xml -DskipTests package`)
    }
  }
}


export async function waitForRegistration(config, { signal, group, timeoutMs = config.timeoutMs } = {}) {
  const expected = ['RATE-SERVICE', 'QUOTE-SERVICE', 'SETTLEMENT-SERVICE', 'EVENT-SERVICE']
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    signal?.throwIfAborted(); group?.assertRunning()
    try {
      const response = await fetch(`http://127.0.0.1:${config.ports.discovery}/eureka/apps`, { headers: { Accept: 'application/json' }, signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(2000)]) : AbortSignal.timeout(2000) })
      if (response.ok) {
        const body = await response.json()
        let apps = body?.applications?.application || []
        if (!Array.isArray(apps)) apps = [apps]
        const names = apps.filter(app => {
          const instances = Array.isArray(app.instance) ? app.instance : [app.instance]
          return instances.some(instance => instance?.status === 'UP')
        }).map(app => app.name)
        if (expected.every(name => names.includes(name))) return
      }
    } catch { /* Wait for Eureka registration. */ }
    await delay(500, undefined, { signal })
  }
  throw new Error('All four applications did not register UP with Eureka in time. Inspect the service logs.')
}

export async function main(args = process.argv.slice(2)) {
  const options = parseOptions(args)
  if (options.help) {
    console.log('npm run dev [-- --no-build] [--start-kafka] [--with-frontend]\nStarts Eureka, M3, M1, M2 and Event Service; frontend is optional.\n--start-kafka starts missing local Kafka/ZooKeeper using KAFKA_JAVA_HOME (Java 8).\nWithout it, reuse your running Kafka and existing five topics.\nnpm run build [-- --test] builds offline without starting services.\n--check validates tools, configuration, files and available app ports only; it does not start processes.\nSecrets are prompted and not saved. No Oracle migration/reset or package install is run.')
    return
  }
  const abort = new AbortController()
  const interrupt = () => abort.abort(new Error('Startup or application run cancelled.'))
  process.on('SIGINT', interrupt)
  process.on('SIGTERM', interrupt)
  let group
  try {
    await checkTools(abort.signal)
    if (options.buildOnly) { await build(options, abort.signal); return }
    console.log('Local demo: M3 uses the existing plaintext-account-demo profile. Use throwaway login passwords only.')
    console.log('PowerShell saved settings are not read. Missing credentials are prompted and are not saved.')
    const config = await configuration(process.env, { signal: abort.signal })
    if (options.startKafka) {
      config.env.KAFKA_HOME ||= await ask('Kafka installation folder', 'C:\\kafka_2.12-2.2.0', abort.signal)
      config.env.KAFKA_JAVA_HOME ||= await ask('Kafka Java 8 folder (C:\\Java_18 contains Java 8 on this machine)', 'C:\\Java_18', abort.signal)
    }
    for (const [name, port] of Object.entries(config.ports)) {
      if (name !== 'frontend' || options.frontend) await assertPortAvailable(port)
    }
    if (options.build && !options.check) await build(options, abort.signal)
    const specs = await servicePlan(repositoryRoot, config, options)
    if (options.frontend) await access(path.join(repositoryRoot, 'settlement-service', 'src', 'main', 'resources', 'static', 'dashboard', 'index.html'))
    if (options.check) {
      if (options.startKafka) await kafkaRuntime(config.env)
      console.log('Preflight passed: Java 25, Maven, configuration, available app ports and five jars. No services or database connections were started. Kafka connectivity and Oracle schema are checked during actual startup.')
      return
    }
    const logDirectory = path.join(repositoryRoot, 'target', 'dev-runs', `${Date.now()}-${randomBytes(4).toString('hex')}`)
    console.log(`Logs: ${logDirectory}`)
    group = new ProcessGroup(logDirectory, config.secrets)
    const stopped = new Promise((resolve, reject) => {
      group.onFailure = reject
      abort.signal.addEventListener('abort', resolve, { once: true })
    })
    // Mark handled during sequential startup; group.ready also reports startup failures.
    stopped.catch(() => {})
    await ensureKafka(config, group, { manage: options.startKafka, signal: abort.signal })
    await startServices(specs, group, { timeoutMs: config.timeoutMs, signal: abort.signal,
      onStarting: (spec) => console.log(`Starting ${spec.name}...`) })
    await waitForRegistration(config, { signal: abort.signal, group })
    console.log(`FXLock ready. Login: http://localhost:${config.ports.quote}/login`)
    console.log(`Hosted dashboard: http://localhost:${config.ports.settlement}/dashboard/`)
    if (options.frontend) console.log(`React development: http://localhost:${config.ports.frontend}/dashboard/ (open after logging in on M2)`)
    console.log(`Eureka: http://localhost:${config.ports.discovery} | Event APIs: http://localhost:${config.ports.event}/api/v1/operations/summary`)
    console.log('Press Ctrl+C here to stop only processes started by this launcher. Reused Kafka/ZooKeeper stay running.')
    await stopped
  } finally {
    try { if (group) { await group.stop(); console.log('FXLock development processes stopped.') } }
    finally { process.removeListener('SIGINT', interrupt); process.removeListener('SIGTERM', interrupt) }
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => { console.error(error.message || 'Startup failed.'); process.exitCode = 1 })
}
