import type { Page, WebSocketRoute } from '@playwright/test'

declare global {
  interface Window {
    meshboardNetworkFault: {
      documentId: string
      openChannels(): number
      closeChannels(): void
    }
  }
}

/** Test-only transport faults: keep the page and its in-memory CRDT alive. */
export async function networkFault(page: Page) {
  let blocked = false
  const events: unknown[] = []
  const record = (event: unknown) => { events.push(event); if (events.length > 100) events.shift() }
  const sockets = new Map<WebSocketRoute, WebSocketRoute>()
  await page.routeWebSocket('**/signal-y-webrtc', async client => {
    // Server-side routing uses the browser's WebSocket.close(): use an
    // application code (3000–4999), not a reserved code such as 1001.
    if (blocked) { await client.close({ code: 4000, reason: 'Test signaling outage' }); return }
    const server = client.connectToServer()
    for (const [source, destination, direction] of [[client, server, 'out'], [server, client, 'in']] as const) {
      source.onMessage(message => {
        if (typeof message === 'string') {
          const data = JSON.parse(message)
          record({ direction, type: data.type, code: data.code, topics: data.topics?.length, kind: data.data?.type, signal: data.data?.signal?.type })
        }
        destination.send(message)
      })
    }
    sockets.set(client, server)
    client.onClose((code, reason) => { sockets.delete(client); return server.close({ code, reason }) })
    server.onClose((code, reason) => { sockets.delete(client); return client.close({ code, reason }) })
  })
  await page.addInitScript(() => {
    const channels = new Set<RTCDataChannel>()
    function track(channel: RTCDataChannel) {
      channels.add(channel)
      channel.addEventListener('close', () => channels.delete(channel), { once: true })
    }
    const Original = window.RTCPeerConnection
    window.RTCPeerConnection = class extends Original {
      constructor(configuration?: RTCConfiguration) {
        super(configuration)
        this.addEventListener('datachannel', event => track(event.channel))
      }
      override createDataChannel(label: string, options?: RTCDataChannelInit) {
        const channel = super.createDataChannel(label, options)
        track(channel)
        return channel
      }
    }
    window.meshboardNetworkFault = {
      documentId: crypto.randomUUID(),
      openChannels: () => [...channels].filter(channel => channel.readyState === 'open').length,
      closeChannels: () => { for (const channel of channels) channel.close() },
    }
  })
  return {
    events: () => events,
    async pauseSignaling() {
      blocked = true
      record({ fault: 'signaling-paused' })
      const active = [...sockets]
      sockets.clear()
      await Promise.all(active.flatMap(([client, server]) => [
        client.close({ code: 4000, reason: 'Test signaling outage' }),
        server.close({ code: 4000, reason: 'Test signaling outage' }),
      ]))
    },
    resumeSignaling() { blocked = false; record({ fault: 'signaling-resumed' }) },
    closeChannels: () => page.evaluate(() => window.meshboardNetworkFault.closeChannels()),
    openChannels: () => page.evaluate(() => window.meshboardNetworkFault.openChannels()),
    documentId: () => page.evaluate(() => window.meshboardNetworkFault.documentId),
  }
}
