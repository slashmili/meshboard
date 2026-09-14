import { createServer } from 'node:http'
import { attachSignaling, rtcConfiguration } from './server.ts'

const config = rtcConfiguration(process.env, process.env.MESHBOARD_LOCAL_DEV === 'true')
const server = createServer((request, response) => relay.handleHttp(request, response, () => response.writeHead(404).end()))
const relay = attachSignaling(server, config)
const host = process.env.HOST ?? '127.0.0.1'
const port = Number(process.env.PORT ?? 4444)
server.listen(port, host, () => console.log(`Meshboard signaling listening on ${host}:${port}`))
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { relay.close(); server.close() })
