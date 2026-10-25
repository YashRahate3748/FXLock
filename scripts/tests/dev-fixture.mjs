import { createServer } from 'node:http'

if (process.argv.includes('--exit')) process.exit(9)
const port = Number(process.argv[2])
const server = createServer((request, response) => {
  if (process.env.FIXTURE_TOKEN && request.headers['x-internal-token'] !== process.env.FIXTURE_TOKEN) {
    response.writeHead(401).end(); return
  }
  response.setHeader('Content-Type', 'application/json')
  if (request.url === '/down') { response.writeHead(503).end('{"status":"DOWN"}'); return }
  response.end('{"status":"UP"}')
})
server.listen(port, '127.0.0.1', () => {
  if (process.env.FIXTURE_TOKEN) {
    const token = process.env.FIXTURE_TOKEN
    process.stdout.write(`Fixture credential: ${token.slice(0, 3)}`)
    setTimeout(() => process.stdout.write(`${token.slice(3)}\n`), 20)
  }
})
