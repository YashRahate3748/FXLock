import { spawn } from 'node:child_process'
import { createServer } from 'node:net'
import { mkdir, appendFile } from 'node:fs/promises'
import { createInterface } from 'node:readline'
import { setTimeout as delay } from 'node:timers/promises'
import path from 'node:path'

export async function assertPortAvailable(port, host = '127.0.0.1') {
  const server = createServer()
  await new Promise((resolve, reject) => {
    server.once('error', () => reject(new Error(`Port ${port} is occupied or unavailable. Stop the existing service first.`)))
    server.listen({ port, host, exclusive: true }, () => server.close(resolve))
  })
}

export function toolCommand(tool, args) {
  if (!['npm', 'mvn'].includes(tool) || args.some((arg) => !/^[A-Za-z0-9_=,.:/-]+$/.test(arg))) {
    throw new Error('Unsupported build command')
  }
  return process.platform === 'win32'
    ? { command: process.env.ComSpec || 'cmd.exe', args: ['/d', '/s', '/c', `${tool}.cmd ${args.join(' ')}`] }
    : { command: tool, args }
}

async function stopChild(child, closed) {
  if (child.exitCode !== null || child.signalCode !== null || !child.pid) { await closed; return }
  if (process.platform === 'win32') {
    // Only terminate a live process created by this launcher and its descendants.
    await new Promise((resolve) => {
      const killer = spawn('taskkill.exe', ['/PID', String(child.pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' })
      killer.once('error', resolve)
      killer.once('close', resolve)
    })
  } else {
    child.kill('SIGTERM')
  }
  if (!await Promise.race([closed.then(() => true), delay(3000, undefined, { ref: false }).then(() => false)])) {
    child.kill('SIGKILL')
    if (!await Promise.race([closed.then(() => true), delay(3000, undefined, { ref: false }).then(() => false)])) {
      throw new Error('A launcher-owned process could not be stopped. Check the run logs.')
    }
  }
}

export async function runCommand(tool, args, { cwd, signal, capture = false } = {}) {
  signal?.throwIfAborted()
  const invocation = tool === 'java' ? { command: 'java', args } : toolCommand(tool, args)
  const child = spawn(invocation.command, invocation.args, {
    cwd, windowsHide: true, stdio: ['ignore', capture ? 'pipe' : 'inherit', capture ? 'pipe' : 'inherit'],
  })
  let output = ''
  if (capture) {
    child.stdout.on('data', (data) => { output += data })
    child.stderr.on('data', (data) => { output += data })
  }
  const closed = new Promise((resolve) => child.once('close', resolve))
  let stopError
  const cancel = () => { stopChild(child, closed).catch((error) => { stopError = error }) }
  signal?.addEventListener('abort', cancel, { once: true })
  try {
    await new Promise((resolve, reject) => {
      child.once('error', () => reject(new Error(`${tool} could not start. Check its installation and PATH.`)))
      child.once('close', (code) => code === 0 ? resolve() : reject(new Error(`${tool} failed (exit ${code ?? 'signal'}).`)))
    })
    signal?.throwIfAborted()
    if (stopError) throw stopError
    return output
  } finally {
    signal?.removeEventListener('abort', cancel)
  }
}

export function redact(text, secrets) {
  return secrets.filter(Boolean).sort((a, b) => b.length - a.length)
    .reduce((result, secret) => result.split(secret).join('[redacted]'), text)
}

export class ProcessGroup {
  constructor(logDirectory, secrets = []) {
    this.logDirectory = logDirectory
    this.secrets = secrets
    this.children = []
    this.stopping = false
    this.failure = null
    this.onFailure = () => {}
    this.logWrites = Promise.resolve()
  }

  async start(spec) {
    await mkdir(this.logDirectory, { recursive: true })
    const child = spawn(spec.command, spec.args, {
      cwd: spec.cwd, env: spec.env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'],
    })
    const fail = () => {
      if (!this.stopping && !this.failure) {
        this.failure = new Error(`${spec.name} exited or could not start. Check ${this.logDirectory}`)
        this.onFailure(this.failure)
      }
    }
    const closed = new Promise((resolve) => child.once('close', resolve))
    this.children.push({ child, closed })
    child.once('error', fail)
    child.once('exit', fail)
    for (const [stream, suffix] of [[child.stdout, 'out'], [child.stderr, 'err']]) {
      const lines = createInterface({ input: stream })
      lines.on('line', (line) => {
        const safe = redact(line, this.secrets)
        this.logWrites = this.logWrites.then(() => appendFile(path.join(this.logDirectory, `${spec.name}.${suffix}.log`), `${safe}\n`))
          .catch(() => {
            if (!this.failure) {
              this.failure = new Error('Could not write service logs. Check directory permissions.')
              this.onFailure(this.failure)
            }
          })
      })
    }
    return child
  }

  assertRunning() {
    if (this.failure) throw this.failure
  }

  async ready(spec, { timeoutMs = 120000, signal } = {}) {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      signal?.throwIfAborted()
      this.assertRunning()
      try {
        const timeout = AbortSignal.timeout(Math.min(2000, Math.max(1, deadline - Date.now())))
        const response = await fetch(spec.url, {
          headers: spec.headers || {}, redirect: 'error',
          signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
        })
        const healthy = spec.jsonHealth ? response.ok && (await response.json()).status === 'UP' : response.ok
        if (!spec.jsonHealth) await response.body?.cancel()
        this.assertRunning()
        if (healthy) return
      } catch { /* A starting service can temporarily refuse connections. */ }
      signal?.throwIfAborted()
      this.assertRunning()
      await delay(200, undefined, { signal })
    }
    throw new Error(`${spec.name} was not ready within ${timeoutMs / 1000}s. Check its logs and existing database setup.`)
  }

  async stop() {
    this.stopping = true
    const errors = []
    for (const { child, closed } of [...this.children].reverse()) {
      try { await stopChild(child, closed) } catch (error) { errors.push(error) }
    }
    await this.logWrites
    if (errors.length) throw errors[0]
  }
}

export async function startServices(specs, group, options = {}) {
  try {
    for (const spec of specs) {
      options.signal?.throwIfAborted()
      options.onStarting?.(spec)
      await group.start(spec)
      await group.ready(spec, options)
    }
    return group
  } catch (error) {
    await group.stop()
    throw error
  }
}
