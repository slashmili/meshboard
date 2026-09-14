import type { RtcConfig } from '@meshboard/shared-protocol'

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
