import { randomBytes } from 'node:crypto'
import { access, readFile } from 'node:fs/promises'
import { createInterface, emitKeypressEvents } from 'node:readline'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { buildDashboard, repositoryRoot } from './build-dashboard.mjs'
import { assertPortAvailable, ProcessGroup, runCommand, startServices } from './dev-tools.mjs'

const services = ['rate-service', 'settlement-service', 'quote-service']
const safeTests = {
  'rate-service': 'RateValidationServiceTest,TrustedRateCacheTest',
  'settlement-service': 'FundingTransactionTest,CustomerTransferHistoryTest,HistoryApiTest,DashboardApiTest,DashboardBrowserFlowTest,SettlementApiTest,IntegrationSettlementApiTest,InternalCustomerResolverTest,LoginVerificationServiceTest,AccountPasswordProfileTest,PlaintextAccountServiceTest,PlaintextAccountApiTest',
}

export function parseOptions(args) {
  const options = { build: true, buildOnly: false, check: false, test: false, help: false }
  for (const arg of args) {
    switch (arg.toLowerCase()) {
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
  if (!process.stdin.isTTY || !process.stdin.setRawMode) throw new Error('Supply ORACLE_PASSWORD and TWELVE_DATA_API_KEY through your environment when no interactive terminal is available.')
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
  const ports = {
    rate: Number(env.FXLOCK_RATE_PORT || 8081), quote: Number(env.FXLOCK_QUOTE_PORT || 8082),
    settlement: Number(env.FXLOCK_SETTLEMENT_PORT || 8083), frontend: Number(env.FXLOCK_FRONTEND_PORT || 5173),
  }
  if (Object.values(ports).some((port) => !Number.isInteger(port) || port < 1 || port > 65535)
    || new Set(Object.values(ports)).size !== 4) throw new Error('Choose four distinct valid service ports.')
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
    FXLOCK_SETTLEMENT_PORT: String(ports.settlement), EUREKA_ENABLED: 'false',
    SPRING_SQL_INIT_MODE: 'never', SPRING_JPA_HIBERNATE_DDL_AUTO: 'validate',
  }
  console.log('Service username:', childEnv.FXLOCK_SERVICE_USERNAME);
  console.log('Service password:', childEnv.FXLOCK_SERVICE_PASSWORD);
  console.log('Risk username:', childEnv.FXLOCK_RISK_USERNAME);
  console.log('Risk password:', childEnv.FXLOCK_RISK_PASSWORD);
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

export async function servicePlan(root, config) {
  const common = ['--server.address=127.0.0.1', '--eureka.client.enabled=false', '--spring.cloud.discovery.enabled=false',
    '--spring.sql.init.mode=never', '--spring.jpa.hibernate.ddl-auto=validate']
  const definitions = [
    ['settlement-service', config.ports.settlement, 'integration,plaintext-account-demo', '/internal/health'],
    ['rate-service', config.ports.rate, 'default', '/actuator/health'],
    ['quote-service', config.ports.quote, 'team', '/actuator/health'],
  ]
  const specs = []
  for (const [name, port, profile, health] of definitions) {
    specs.push({ name, command: 'java', args: ['-jar', await jarPath(root, name), `--spring.profiles.active=${profile}`, `--server.port=${port}`, ...common],
      cwd: path.join(root, name), env: config.env, url: `http://127.0.0.1:${port}${health}`, jsonHealth: true,
      headers: name === 'settlement-service' ? { 'X-Internal-Token': config.env.FX_INTERNAL_TOKEN } : {} })
  }
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
  if (options.test) await runCommand('npm', ['test'], { cwd: path.join(repositoryRoot, 'frontend'), signal })
  await buildDashboard({ signal })
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

export async function main(args = process.argv.slice(2)) {
  const options = parseOptions(args)
  if (options.help) {
    console.log('npm run dev [-- --no-build|--check]\nStarts M3, M1, M2 and Vite. Eureka stays disabled.\nnpm run build [-- --test] builds offline without starting services.\nMissing Oracle settings and provider key are prompted without saving secrets.\nNo PowerShell scripts, execution-policy changes, installs, migrations or seed scripts are run.')
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
    for (const port of Object.values(config.ports)) await assertPortAvailable(port)
    if (options.build && !options.check) await build(options, abort.signal)
    const specs = await servicePlan(repositoryRoot, config)
    await access(path.join(repositoryRoot, 'settlement-service', 'src', 'main', 'resources', 'static', 'dashboard', 'index.html'))
    if (options.check) {
      console.log('Preflight passed: Java 25, Maven, configuration, available ports, three jars, Vite and dashboard assets. No services or database connections were started.')
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
    await startServices(specs, group, { timeoutMs: config.timeoutMs, signal: abort.signal,
      onStarting: (spec) => console.log(`Starting ${spec.name}...`) })
    console.log(`FXLock ready. Login: http://localhost:${config.ports.quote}/login`)
    console.log(`Hosted dashboard: http://localhost:${config.ports.settlement}/dashboard/`)
    console.log(`React development: http://localhost:${config.ports.frontend}/dashboard/ (open after logging in on M2)`)
    console.log('Eureka is disabled. Press Ctrl+C here to stop all four processes.')
    await stopped
  } finally {
    try { if (group) { await group.stop(); console.log('FXLock development processes stopped.') } }
    finally { process.removeListener('SIGINT', interrupt); process.removeListener('SIGTERM', interrupt) }
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => { console.error(error.message || 'Startup failed.'); process.exitCode = 1 })
}
