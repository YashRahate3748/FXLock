import { createConnection } from 'node:net'
import { access } from 'node:fs/promises'
import path from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
import { runExecutable } from './dev-tools.mjs'

export const topics = ['fx-rate-updated', 'fx-settlement-completed', 'fx-system-alert', 'fx-quote-events', 'fx-simulated-funding-completed']

export function endpoint(value) {
  const match = /^([a-zA-Z0-9._-]+):(\d+)$/.exec(value)
  if (!match || Number(match[2]) < 1 || Number(match[2]) > 65535) throw new Error('Use one Kafka address such as localhost:9092.')
  return { host: match[1], port: Number(match[2]) }
}

export function tcpOpen(host, port) {
  return new Promise((resolve) => {
    const socket = createConnection({ host, port })
    const finish = (ok) => { socket.destroy(); resolve(ok) }
    socket.setTimeout(1500, () => finish(false))
    socket.once('connect', () => finish(true)); socket.once('error', () => finish(false))
  })
}

// Metadata API v0 works with Kafka 2.2.0. An empty list reads existing topics;
// this request never asks the broker to auto-create a named missing topic.
export function parseMetadata(buffer) {
  let offset = 0
  const need = (n) => { if (n < 0 || offset + n > buffer.length) throw new Error('Invalid Kafka metadata response') }
  const i32 = () => { need(4); const n = buffer.readInt32BE(offset); offset += 4; return n }
  const i16 = () => { need(2); const n = buffer.readInt16BE(offset); offset += 2; return n }
  const count = () => { const n = i32(); if (n < 0 || n > 100000) throw new Error('Invalid Kafka metadata count'); return n }
  const string = () => { const n = i16(); need(n); const s = buffer.toString('utf8', offset, offset + n); offset += n; return s }
  if (i32() !== 73) throw new Error('Unexpected Kafka correlation ID')
  const brokers = []
  for (let n = count(); n > 0; n--) brokers.push({ id: i32(), host: string(), port: i32() })
  const found = new Map()
  for (let n = count(); n > 0; n--) {
    const error = i16(), name = string(), partitions = count(); let healthy = error === 0 && partitions > 0
    for (let p = partitions; p > 0; p--) {
      const partitionError = i16(); i32(); const leader = i32()
      healthy &&= partitionError === 0 && leader >= 0
      for (let r = count(); r > 0; r--) i32()
      for (let r = count(); r > 0; r--) i32()
    }
    found.set(name, healthy)
  }
  return { brokers, topics: found }
}

export async function metadata(address) {
  const { host, port } = endpoint(address)
  return new Promise((resolve, reject) => {
    const socket = createConnection({ host, port }); let data = Buffer.alloc(0), done = false
    const finish = (error, result) => {
      if (done) return; done = true; socket.destroy()
      error ? reject(error) : resolve(result)
    }
    const timer = setTimeout(() => finish(new Error('Kafka metadata timed out')), 2500)
    socket.once('close', () => { clearTimeout(timer); if (!done) finish(new Error('Kafka closed the metadata connection')) })
    socket.once('error', () => finish(new Error(`Kafka is unavailable at ${address}`)))
    socket.once('connect', () => {
      const client = Buffer.from('fxlock-launcher'), body = Buffer.alloc(14 + client.length)
      body.writeInt16BE(3, 0); body.writeInt16BE(0, 2); body.writeInt32BE(73, 4)
      body.writeInt16BE(client.length, 8); client.copy(body, 10); body.writeInt32BE(0, 10 + client.length)
      const frame = Buffer.alloc(4); frame.writeInt32BE(body.length); socket.write(Buffer.concat([frame, body]))
    })
    socket.on('data', (chunk) => {
      data = Buffer.concat([data, chunk])
      if (data.length < 4) return
      const size = data.readInt32BE(0)
      if (size < 4 || size > 16 * 1024 * 1024) return finish(new Error('Invalid Kafka response size'))
      if (data.length >= size + 4) {
        try { finish(null, parseMetadata(data.subarray(4, size + 4))) } catch (error) { finish(error) }
      }
    })
  })
}

export async function waitForKafka(address, { timeoutMs = 120000, signal, group, requiredTopics = [] } = {}) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    signal?.throwIfAborted(); group?.assertRunning()
    try {
      const result = await metadata(address)
      if (result.brokers.length && requiredTopics.every((name) => result.topics.get(name) === true)) return result
    } catch { /* Registration and partition leadership may need time after startup. */ }
    await delay(500, undefined, { signal })
  }
  throw new Error(`Kafka at ${address} has no ready broker/topics. Check Kafka logs and run scripts/kafka-topics.ps1 if topics are missing.`)
}

export async function kafkaRuntime(env) {
  const home = env.KAFKA_HOME || 'C:\\kafka_2.12-2.2.0'
  const javaHome = env.KAFKA_JAVA_HOME
  if (!javaHome) throw new Error('Set KAFKA_JAVA_HOME to Kafka\'s Java 8 installation (your working path was C:\\Java_18).')
  const java = path.join(javaHome, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
  for (const file of [java, path.join(home, 'libs'), path.join(home, 'config', 'server.properties'), path.join(home, 'config', 'zookeeper.properties')]) await access(file)
  const version = await runExecutable(java, ['-version'], { capture: true, label: 'Kafka Java' })
  if (!/version "1\.8\./.test(version)) throw new Error('The Kafka 2.2.0 managed launch requires its working Java 8 runtime. Keep Java 25 on the main PATH.')
  const args = ['-Xms256m', '-Xmx512m', `-Dkafka.logs.dir=${path.join(home, 'logs')}`, `-Dlog4j.configuration=file:${path.join(home, 'config', 'log4j.properties').replaceAll('\\', '/')}`, '-cp', path.join(home, 'libs', '*')]
  return { home, java, args, env: { ...env, JAVA_HOME: javaHome } }
}

export async function ensureKafka(config, group, { manage = false, signal } = {}) {
  const { host, port } = endpoint(config.env.KAFKA_BOOTSTRAP_SERVERS)
  if (!manage) return waitForKafka(config.env.KAFKA_BOOTSTRAP_SERVERS, { timeoutMs: 12000, signal, requiredTopics: topics })
  if (!['localhost', '127.0.0.1'].includes(host) || port !== 9092) throw new Error('--start-kafka supports the local Kafka 2.2.0 setup on 9092 only.')
  const runtime = await kafkaRuntime(config.env)
  const zooKeeper = config.env.KAFKA_ZOOKEEPER || 'localhost:2181'
  if (!['localhost:2181', '127.0.0.1:2181'].includes(zooKeeper)) throw new Error('Managed ZooKeeper must use localhost:2181.')
  if (!await tcpOpen('127.0.0.1', 2181)) {
    const spec = { name: 'zookeeper', command: runtime.java, args: [...runtime.args, 'org.apache.zookeeper.server.quorum.QuorumPeerMain', path.join(runtime.home, 'config', 'zookeeper.properties')], cwd: runtime.home, env: runtime.env, probe: () => tcpOpen('127.0.0.1', 2181) }
    console.log('Starting local ZooKeeper...'); await group.start(spec); await group.ready(spec, { signal, timeoutMs: config.timeoutMs })
  } else console.log('Reusing the existing ZooKeeper listener; it will not be stopped by this launcher.')
  const existingBroker = await tcpOpen(host, port)
  if (!existingBroker) {
    // The local broker uses a six-second ZooKeeper session timeout. After a
    // forced Windows stop, its ephemeral registration can briefly survive.
    // Allow two session windows before reusing broker.id=0; never delete znodes.
    console.log('Waiting 12 seconds for old ZooKeeper broker sessions to expire...')
    await delay(12000, undefined, { signal })
    group.assertRunning()
    const spec = { name: 'kafka', command: runtime.java, args: [...runtime.args, 'kafka.Kafka', path.join(runtime.home, 'config', 'server.properties'), '--override', 'log.cleaner.enable=false', '--override', `zookeeper.connect=${zooKeeper}`, '--override', 'listeners=PLAINTEXT://127.0.0.1:9092', '--override', 'advertised.listeners=PLAINTEXT://localhost:9092'], cwd: runtime.home, env: runtime.env, probe: async () => { try { return (await metadata(config.env.KAFKA_BOOTSTRAP_SERVERS)).brokers.length > 0 } catch { return false } } }
    console.log('Starting Kafka with the temporary local log-cleaner override...'); await group.start(spec); await group.ready(spec, { signal, timeoutMs: config.timeoutMs })
  } else console.log('Reusing the existing Kafka listener; it will not be stopped by this launcher.')
  // Fail if this is not a working Kafka broker; an open TCP port alone is insufficient.
  const current = await waitForKafka(config.env.KAFKA_BOOTSTRAP_SERVERS, { signal, group, timeoutMs: config.timeoutMs })
  const missing = topics.filter((name) => !current.topics.has(name))
  if (existingBroker && missing.length) throw new Error('The reused broker is missing M4 topics. Run scripts/kafka-topics.ps1 against its matching ZooKeeper, then retry. This launcher will not provision an unidentified cluster.')
  for (const topic of missing) {
    console.log(`Creating missing event topic ${topic}...`)
    await runExecutable(runtime.java, [...runtime.args, 'kafka.admin.TopicCommand', '--zookeeper', zooKeeper, '--create', '--if-not-exists', '--replication-factor', '1', '--partitions', '1', '--topic', topic], { cwd: runtime.home, env: runtime.env, signal, capture: true, label: 'Kafka topic setup' })
  }
  await waitForKafka(config.env.KAFKA_BOOTSTRAP_SERVERS, { signal, group, timeoutMs: config.timeoutMs, requiredTopics: topics })
}
