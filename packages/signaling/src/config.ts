import { createHmac, randomUUID } from 'node:crypto'
import type { RtcConfig } from '@meshboard/shared-protocol'

// Construct once at startup, issue a fresh, expiring credential per HTTP request.
// The shared signing secret never appears in the returned client configuration.
export function rtcConfigurationProvider(env: NodeJS.ProcessEnv, development = false): () => RtcConfig {
  const secret = env.MESHBOARD_TURN_SECRET
  if (!secret) {
    const config = rtcConfiguration(env, development)
    return () => config
  }
  if (!/^[a-fA-F0-9]{64}$/.test(secret)) throw new Error('MESHBOARD_TURN_SECRET must be 32 random bytes encoded as 64 hex characters.')
  if (env.MESHBOARD_TURN_USERNAME || env.MESHBOARD_TURN_CREDENTIAL) throw new Error('Choose TURN shared-secret or static credentials, not both.')
  const ttl = Number(env.MESHBOARD_TURN_TTL_SECONDS ?? '86400')
  if (!Number.isInteger(ttl) || ttl < 600 || ttl > 86400) throw new Error('TURN credential lifetime must be between 600 and 86400 seconds.')
  const config = rtcConfiguration({ ...env, MESHBOARD_TURN_USERNAME: 'generated', MESHBOARD_TURN_CREDENTIAL: 'generated' })
  return () => {
    const username = `${Math.floor(Date.now() / 1000) + ttl}:${randomUUID()}`
    const credential = createHmac('sha1', secret).update(username).digest('base64')
    return { ...config, iceServers: config.iceServers.map(server => ({ ...server, username, credential })) }
  }
}

export function rtcConfiguration(env: NodeJS.ProcessEnv, development = false): RtcConfig {
  const urls = env.MESHBOARD_TURN_URLS?.split(',').map(url => url.trim()).filter(Boolean)
  const relayOnly = env.MESHBOARD_RELAY_ONLY === 'true'
  if (urls?.length) {
    if (urls.some(url => !/^turns?:[^\s]+$/.test(url)) || !env.MESHBOARD_TURN_USERNAME || !env.MESHBOARD_TURN_CREDENTIAL) {
      throw new Error('TURN requires valid MESHBOARD_TURN_URLS, MESHBOARD_TURN_USERNAME, and MESHBOARD_TURN_CREDENTIAL.')
    }
    return {
      iceServers: [{ urls, username: env.MESHBOARD_TURN_USERNAME, credential: env.MESHBOARD_TURN_CREDENTIAL }],
      iceTransportPolicy: relayOnly ? 'relay' : 'all', localDevelopment: false,
    }
  }
  if (!development) throw new Error('Configure TURN before starting the signaling service. See README.md.')
  return {
    iceServers: [{ urls: ['turn:127.0.0.1:3478?transport=udp', 'turn:127.0.0.1:3478?transport=tcp'], username: 'meshboard-dev', credential: 'local-testing-only' }],
    iceTransportPolicy: relayOnly ? 'relay' : 'all', localDevelopment: true,
  }
}
