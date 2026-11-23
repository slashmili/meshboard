import { defineConfig, loadEnv, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import { attachSignaling, rtcConfigurationProvider } from '@meshboard/signaling'

export default defineConfig(({ mode }) => {
  const env = { ...loadEnv(mode, '../..', 'MESHBOARD_'), ...process.env }
  const signaling: Plugin = {
    name: 'meshboard-local-signaling',
    configureServer(server) {
      if (!server.httpServer) return
      const relay = attachSignaling(server.httpServer, rtcConfigurationProvider(env, true))
      server.middlewares.use(relay.handleHttp)
      server.httpServer.on('close', relay.close)
    },
    configurePreviewServer(server) {
      const relay = attachSignaling(server.httpServer, rtcConfigurationProvider(env, true))
      server.middlewares.use(relay.handleHttp)
      server.httpServer.on('close', relay.close)
    },
  }
  // Pin the policy instead of inheriting a moving Vite default; see docs/support.md.
  return { build: { target: ['chrome113', 'edge113', 'firefox114', 'safari17', 'ios17'] }, publicDir: '../../icons', plugins: [react(), signaling], server: { port: 5173, strictPort: true } }
})
