import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:net'
import { createServer as createHttpServer } from 'node:http'
import { metadata, parseMetadata, endpoint, topics, waitForKafka } from '../kafka-dev.mjs'
import { configuration, servicePlan, parseOptions, waitForRegistration } from '../dev.mjs'
import { mkdir, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { randomUUID } from 'node:crypto'
import { repositoryRoot } from '../build-dashboard.mjs'

const i32 = n => { const b = Buffer.alloc(4); b.writeInt32BE(n); return b }
const i16 = n => { const b = Buffer.alloc(2); b.writeInt16BE(n); return b }
const str = s => Buffer.concat([i16(Buffer.byteLength(s)), Buffer.from(s)])
function reply({ brokerCount = 1, leader = 0, partitions = 1 } = {}) {
  const body = [i32(73), i32(brokerCount)]
  if (brokerCount) body.push(i32(0), str('localhost'), i32(9092))
  body.push(i32(topics.length))
  for (const name of topics) {
    body.push(i16(0), str(name), i32(partitions))
    if (partitions) body.push(i16(0), i32(0), i32(leader), i32(1), i32(0), i32(1), i32(0))
  }
  return Buffer.concat(body)
}

test('Kafka metadata parser checks registered brokers and ready partition leaders', () => {
  const result = parseMetadata(reply())
  assert.equal(result.brokers.length, 1)
  assert.ok(topics.every(name => result.topics.get(name)))
  assert.equal(parseMetadata(reply({ brokerCount: 0 })).brokers.length, 0)
  assert.ok([...parseMetadata(reply({ leader: -1 })).topics.values()].every(v => !v))
  assert.ok([...parseMetadata(reply({ partitions: 0 })).topics.values()].every(v => !v))
  assert.throws(() => parseMetadata(Buffer.from([0, 0, 0])), /Invalid/)
})

test('Kafka preflight reads fragmented protocol replies without requesting topic creation', async () => {
  const server = createServer(socket => {
    let request = Buffer.alloc(0)
    socket.on('data', chunk => {
      request = Buffer.concat([request, chunk])
      if (request.length < 4 || request.length < request.readInt32BE(0) + 4) return
      assert.equal(request.readInt16BE(4), 3) // Metadata API
      assert.equal(request.readInt16BE(6), 0) // v0
      const clientSize = request.readInt16BE(12)
      assert.equal(request.readInt32BE(14 + clientSize), 0) // read existing topics only
      const body = reply(), frame = Buffer.concat([i32(body.length), body])
      socket.write(frame.subarray(0, 7))
      setTimeout(() => socket.end(frame.subarray(7)), 5)
    })
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  try {
    const address = `127.0.0.1:${server.address().port}`
    assert.equal((await metadata(address)).brokers.length, 1)
    assert.equal((await waitForKafka(address, { requiredTopics: topics, timeoutMs: 1000 })).topics.size, 5)
  } finally { await new Promise(resolve => server.close(resolve)) }
})

test('Kafka preflight rejects a non-Kafka TCP listener', async () => {
  const server = createServer(socket => { socket.resume(); socket.end('HTTP/1.1 200 OK\r\n\r\n') })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  try { await assert.rejects(metadata(`127.0.0.1:${server.address().port}`), /Invalid/) }
  finally { await new Promise(resolve => server.close(resolve)) }
})

test('local endpoint parsing rejects shell input and invalid ports', () => {
  assert.deepEqual(endpoint('localhost:9092'), { host: 'localhost', port: 9092 })
  for (const value of ['localhost:0', 'localhost:70000', 'localhost:9092;echo', 'localhost:9092,other:9092']) assert.throws(() => endpoint(value))
})

test('backend mode has no frontend dependency; optional frontend preserves existing Vite launch', async () => {
  const root = path.join(repositoryRoot, 'target', 'native-launcher-tests', randomUUID())
  for (const name of ['discovery-server', 'event-service', 'rate-service', 'settlement-service', 'quote-service']) {
    await mkdir(path.join(root, name, 'target'), { recursive: true })
    await writeFile(path.join(root, name, 'pom.xml'), `<project><artifactId>${name}</artifactId><version>fixture</version></project>`)
    await writeFile(path.join(root, name, 'target', `${name}-fixture.jar`), '')
  }
  const config = await configuration({ ORACLE_PASSWORD: 'fixture', TWELVE_DATA_API_KEY: 'fixture', M4_OPS_PASSWORD: 'fixture', ORACLE_JDBC_URL: 'jdbc:oracle:thin:@fixture', ORACLE_USERNAME: 'fixture' })
  assert.equal((await servicePlan(root, config)).length, 5)
  await assert.rejects(servicePlan(root, config, { frontend: true }), /frontend dependencies/)
  await mkdir(path.join(root, 'frontend', 'node_modules', 'vite', 'bin'), { recursive: true })
  await writeFile(path.join(root, 'frontend', 'node_modules', 'vite', 'bin', 'vite.js'), '')
  const specs = await servicePlan(root, config, { frontend: true })
  assert.equal(specs.length, 6)
  assert.ok(specs.find(s => s.name === 'settlement-service').args.includes('--spring.profiles.active=integration,plaintext-account-demo,m4'))
  assert.ok(specs.find(s => s.name === 'quote-service').args.includes('--spring.profiles.active=team,m4'))
  assert.ok(specs.find(s => s.name === 'rate-service').args.includes('--spring.profiles.active=m4'))
  assert.equal(parseOptions([]).frontend, false)
  assert.equal(parseOptions(['--with-frontend', '--start-kafka']).startKafka, true)
})

test('startup waits for all four Eureka clients to register UP', async () => {
  let requests = 0
  const names = ['RATE-SERVICE', 'QUOTE-SERVICE', 'SETTLEMENT-SERVICE', 'EVENT-SERVICE']
  const server = createHttpServer((request, response) => {
    assert.equal(request.url, '/eureka/apps')
    requests++
    const application = names.map((name, index) => ({ name, instance: [{ status: index === 3 && requests === 1 ? 'STARTING' : 'UP' }] }))
    response.setHeader('Content-Type', 'application/json')
    response.end(JSON.stringify({ applications: { application } }))
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  try {
    await waitForRegistration({ ports: { discovery: server.address().port }, timeoutMs: 3000 })
    assert.ok(requests >= 2)
  } finally { await new Promise(resolve => server.close(resolve)) }
})
