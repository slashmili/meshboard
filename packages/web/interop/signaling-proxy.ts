import { createServer, createConnection, type Socket, type AddressInfo } from 'node:net'

/** Loopback-only test proxy. Cut native HTTP/WebSocket traffic, not WebRTC or adb. */
export async function signalingProxy() {
  let blocked = false
  let rejected = 0
  const sockets = new Set<Socket>()
  const server = createServer(client => {
    if (blocked) { rejected++; client.destroy(); return }
    const upstream = createConnection({ host: '127.0.0.1', port: 5174 })
    for (const [socket, other] of [[client, upstream], [upstream, client]]) {
      sockets.add(socket)
      socket.on('error', () => socket.destroy())
      socket.on('close', () => { sockets.delete(socket); other.destroy() })
    }
    client.pipe(upstream); upstream.pipe(client)
  })
  await new Promise<void>((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', resolve)
  })
  return {
    origin: `http://127.0.0.1:${(server.address() as AddressInfo).port}`,
    rejected: () => rejected,
    pause() { blocked = true; for (const socket of sockets) socket.destroy() },
    resume() { blocked = false },
    async close() {
      blocked = true
      for (const socket of sockets) socket.destroy()
      await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()))
    },
  }
}
