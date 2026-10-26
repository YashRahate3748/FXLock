import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:net'
import { mkdir, readFile, writeFile, readdir } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { configuration, jarPath, parseOptions, servicePlan } from '../dev.mjs'
import { assertPortAvailable, ProcessGroup, redact, runCommand, startServices, toolCommand } from '../dev-tools.mjs'
import { copyDashboard, repositoryRoot } from '../build-dashboard.mjs'

const fixture = fileURLToPath(new URL('./dev-fixture.mjs', import.meta.url))
const env = { ORACLE_JDBC_URL: 'jdbc:oracle:thin:@//fixture:1521/service', ORACLE_USERNAME: 'fixture-user',
  ORACLE_PASSWORD: ' fixture-password ', TWELVE_DATA_API_KEY: 'fixture-provider-key', M4_OPS_PASSWORD: 'fixture-operations-password' }

async function directory() {
  const root = path.join(repositoryRoot, 'target', 'native-launcher-tests', randomUUID())
  await mkdir(root, { recursive: true })
  return root
}

async function availablePort() {
  const server = createServer()
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  const port = server.address().port
  await new Promise((resolve) => server.close(resolve))
  return port
}

async function fakeProject(root) {
  for (const name of ['discovery-server', 'event-service', 'rate-service', 'quote-service', 'settlement-service']) {
    await mkdir(path.join(root, name, 'target'), { recursive: true })
    await writeFile(path.join(root, name, 'pom.xml'), `<project><parent><artifactId>parent</artifactId><version>3.5.16</version></parent><artifactId>${name}</artifactId><version>fixture</version></project>`)
    await writeFile(path.join(root, name, 'target', `${name}-fixture.jar`), '')
  }
  await mkdir(path.join(root, 'frontend', 'node_modules', 'vite', 'bin'), { recursive: true })
  await writeFile(path.join(root, 'frontend', 'node_modules', 'vite', 'bin', 'vite.js'), '')
}

test('options support root npm usage and legacy cmd flags without execution-policy flags', () => {
  assert.equal(parseOptions([]).build, true)
  assert.equal(parseOptions(['--no-build']).build, false)
  assert.equal(parseOptions(['-Check']).check, true)
  assert.equal(parseOptions(['-Build', '-PlaintextAccountDemo']).build, true)
  assert.equal(parseOptions(['--build-only', '-Test']).test, true)
  assert.throws(() => parseOptions(['-ExecutionPolicy']), /Unsupported option/)
})

test('configuration shares credentials, preserves password whitespace, enables Eureka and never changes parent environment', async () => {
  const original = structuredClone(env)
  const config = await configuration(env)
  assert.deepEqual(env, original)
  assert.equal(config.env.SPRING_DATASOURCE_PASSWORD, ' fixture-password ')
  assert.equal(config.env.FXLOCK_DB_PASSWORD, config.env.ORACLE_PASSWORD)
  assert.equal(config.env.EUREKA_ENABLED, 'true')
  assert.equal(config.env.SPRING_SQL_INIT_MODE, 'never')
  assert.equal(config.env.SPRING_JPA_HIBERNATE_DDL_AUTO, 'validate')
  assert.ok(config.env.FX_INTERNAL_TOKEN.length >= 32)
  assert.equal(config.env.FXLOCK_DASHBOARD_URL, 'http://localhost:8083')
  assert.equal(config.env.FXLOCK_QUOTE_BROWSER_URL, 'http://localhost:8082')
  assert.ok(config.secrets.includes(' fixture-password '))
})

test('missing settings use in-memory prompts; invalid ports and empty secrets are rejected', async () => {
  const prompts = []
  const config = await configuration({}, {
    prompt: async (label, fallback) => { prompts.push(label); return fallback },
    secret: async (label) => { prompts.push(label); return 'fixture-secret' },
  })
  assert.equal(prompts.length, 5)
  assert.equal(config.env.ORACLE_USERNAME, 'FXLOCK')
  await assert.rejects(configuration({ ...env, FXLOCK_QUOTE_PORT: '8083' }), /distinct valid/)
  await assert.rejects(configuration({ ...env, FXLOCK_FRONTEND_PORT: '70000' }), /distinct valid/)
  await assert.rejects(configuration({ ...env, ORACLE_PASSWORD: '' }, { secret: async () => '' }), /required/)
  await assert.rejects(configuration({ ...env, FXLOCK_STARTUP_TIMEOUT_SECONDS: '0' }), /timeout/)
})

test('plan starts five Java services with discovery; frontend is opt-in and credentials stay out of arguments', async () => {
  const root = await directory()
  await fakeProject(root)
  const config = await configuration(env)
  const specs = await servicePlan(root, config)
  assert.deepEqual(specs.map((spec) => spec.name), ['discovery-server', 'settlement-service', 'rate-service', 'quote-service', 'event-service'])
  assert.equal(await jarPath(root, 'quote-service'), path.join(root, 'quote-service', 'target', 'quote-service-fixture.jar'))
  for (const spec of specs) {
    assert.equal(spec.env.FX_INTERNAL_TOKEN, config.env.FX_INTERNAL_TOKEN)
    assert.doesNotMatch(JSON.stringify(spec.args), /fixture-password|fixture-provider-key|powershell|ExecutionPolicy/i)
    if (spec.name !== 'discovery-server') assert.ok(spec.args.includes('--eureka.client.enabled=true'))
  }
  assert.equal(specs[1].headers['X-Internal-Token'], config.env.FX_INTERNAL_TOKEN)
})

test('dashboard copy preserves other static files and existing assets without deleting directories', async () => {
  const root = await directory()
  const dist = path.join(root, 'frontend', 'dist')
  const staticRoot = path.join(root, 'settlement-service', 'src', 'main', 'resources', 'static')
  await mkdir(path.join(dist, 'assets'), { recursive: true })
  await mkdir(path.join(staticRoot, 'dashboard', 'assets'), { recursive: true })
  await writeFile(path.join(dist, 'index.html'), '<script src="/dashboard/assets/current.js"></script>')
  await writeFile(path.join(dist, 'assets', 'current.js'), 'fixture')
  await writeFile(path.join(staticRoot, 'wallets.html'), 'keep wallets')
  await writeFile(path.join(staticRoot, 'dashboard', 'assets', 'old.js'), 'keep old')
  await copyDashboard(root)
  assert.equal(await readFile(path.join(staticRoot, 'wallets.html'), 'utf8'), 'keep wallets')
  assert.equal(await readFile(path.join(staticRoot, 'dashboard', 'assets', 'old.js'), 'utf8'), 'keep old')
  assert.equal(await readFile(path.join(staticRoot, 'dashboard', 'assets', 'current.js'), 'utf8'), 'fixture')
  assert.match(await readFile(path.join(staticRoot, 'dashboard', 'index.html'), 'utf8'), /current.js/)
  await assert.rejects(copyDashboard(await directory()))
})

test('occupied ports are rejected without terminating the existing listener', async () => {
  const server = createServer()
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  try {
    await assert.rejects(assertPortAvailable(server.address().port), /occupied/)
    assert.ok(server.listening)
  } finally { await new Promise((resolve) => server.close(resolve)) }
})

async function spec(name = 'fixture') {
  const port = await availablePort()
  return { name, command: process.execPath, args: [fixture, String(port)], cwd: repositoryRoot,
    env: { ...process.env, FIXTURE_TOKEN: 'fixture-private-token' },
    url: `http://127.0.0.1:${port}/`, jsonHealth: true, headers: { 'X-Internal-Token': 'fixture-private-token' } }
}

test('authenticated readiness, redacted split output and cleanup work with a disposable Node fixture', async () => {
  const logs = await directory()
  const group = new ProcessGroup(logs, ['fixture-private-token'])
  try {
    await startServices([await spec()], group, { timeoutMs: 5000 })
    assert.equal(group.children[0].child.exitCode, null)
    await new Promise((resolve) => setTimeout(resolve, 80))
  } finally { await group.stop() }
  assert.notEqual(group.children[0].child.exitCode ?? group.children[0].child.signalCode, null)
  const output = (await Promise.all((await readdir(logs)).map((name) => readFile(path.join(logs, name), 'utf8')))).join('')
  assert.match(output, /\[redacted\]/)
  assert.doesNotMatch(output, /fixture-private-token/)
})

test('readiness failure cleans up all processes already started', async () => {
  const group = new ProcessGroup(await directory())
  const first = await spec('first')
  const second = await spec('unhealthy')
  second.url += 'down'
  await assert.rejects(startServices([first, second], group, { timeoutMs: 800 }), /not ready/)
  assert.equal(group.children.length, 2)
  assert.ok(group.children.every(({ child }) => child.exitCode !== null || child.signalCode !== null))
})

test('early process exit is detected and aborting startup stops its owned process', async () => {
  const group = new ProcessGroup(await directory())
  const exiting = await spec()
  exiting.args.push('--exit')
  await assert.rejects(startServices([exiting], group, { timeoutMs: 5000 }), /exited/)
  const controller = new AbortController()
  const aborted = new ProcessGroup(await directory())
  const unhealthy = await spec()
  unhealthy.url += 'down'
  const timer = setTimeout(() => controller.abort(), 400)
  try {
    await assert.rejects(startServices([unhealthy], aborted, { timeoutMs: 5000, signal: controller.signal }))
    assert.ok(aborted.children.every(({ child }) => child.exitCode !== null || child.signalCode !== null))
  } finally { clearTimeout(timer); await aborted.stop() }
})

test('unexpected exit after readiness is reported and unrelated listeners survive shutdown', async () => {
  const listener = createServer()
  await new Promise((resolve) => listener.listen(0, '127.0.0.1', resolve))
  const group = new ProcessGroup(await directory())
  try {
    await startServices([await spec()], group, { timeoutMs: 5000 })
    const failed = new Promise((resolve) => { group.onFailure = resolve })
    group.children[0].child.kill()
    assert.match((await failed).message, /exited/)
    await group.stop()
    assert.ok(listener.listening)
  } finally { await group.stop(); await new Promise((resolve) => listener.close(resolve)) }
})

test('native build tools accept only fixed safe commands and log redaction handles overlapping values', () => {
  const command = toolCommand('mvn', ['-o', '-DskipTests', 'package'])
  assert.doesNotMatch(JSON.stringify(command), /powershell|ExecutionPolicy/i)
  assert.throws(() => toolCommand('mvn', ['package & echo unexpected']), /Unsupported/)
  assert.throws(() => toolCommand('powershell', []), /Unsupported/)
  assert.equal(redact('credential-long credential', ['credential', 'credential-long']), '[redacted] [redacted]')
})
