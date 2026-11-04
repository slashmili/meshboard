import { decodeSessionKey, FrameCipher } from './FrameCipher'

export const SECURE_CHANNEL = 'meshboard.secure-v1'
const HELLO = new Uint8Array([77, 66, 72, 1]), PROOF = new Uint8Array([77, 66, 65, 1])
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
const hex = (bytes: Uint8Array) => Array.from(bytes, n => n.toString(16).padStart(2, '0')).join('')
export function fingerprint(sdp: string): string {
  if (sdp.length > 65536 || !sdp.includes('m=application ')) throw new Error('Invalid data channel SDP')
  const lines = sdp.split(/\r?\n/).filter(line => line.startsWith('a=fingerprint:'))
  if (!lines.length || lines.some(line => !/^a=fingerprint:sha-256 ([0-9a-f]{2}:){31}[0-9a-f]{2}$/i.test(line))) throw new Error('Invalid DTLS fingerprint')
  const values = new Set(lines.map(line => line.slice('a=fingerprint:sha-256 '.length).replaceAll(':', '').toLowerCase()))
  if (values.size !== 1) throw new Error('Ambiguous DTLS fingerprint')
  return [...values][0]
}

/** Per-connection authentication. Invoked serially on an ordered data channel. */
export class SecureChannel {
  readonly hello: Uint8Array
  authenticated = false
  private secret: Uint8Array
  private challenge = crypto.getRandomValues(new Uint8Array(32))
  private sender?: FrameCipher
  private receiver?: FrameCipher
  private remoteHello = false
  private closed = false
  private localFingerprint: string
  private remoteFingerprint: string
  constructor(key: string, private room: string, private self: string, private peer: string, localSdp: string, remoteSdp: string) {
    if (![room, self, peer].every(value => uuid.test(value)) || self === peer) throw new Error('Invalid channel identity')
    this.secret = decodeSessionKey(key)
    this.localFingerprint = fingerprint(localSdp); this.remoteFingerprint = fingerprint(remoteSdp)
    this.hello = new Uint8Array(36); this.hello.set(HELLO); this.hello.set(this.challenge, 4)
  }
  private context(sender: string, receiver: string, senderFp: string, receiverFp: string, senderNonce: Uint8Array, receiverNonce: Uint8Array) {
    return ['meshboard.secure-v1', this.room, sender, receiver, senderFp, receiverFp, hex(senderNonce), hex(receiverNonce)].join('\n')
  }
  async receive(bytes: Uint8Array): Promise<{ response?: Uint8Array; data?: Uint8Array; admitted?: boolean }> {
    try {
      if (this.closed) throw new Error('Closed')
      if (!this.remoteHello) {
        if (bytes.length !== 36 || HELLO.some((n, i) => bytes[i] !== n) || bytes.subarray(4).every((n, i) => n === this.challenge[i])) throw new Error('Invalid hello')
        this.remoteHello = true
        const remote = bytes.slice(4), salt = new Uint8Array(32)
        this.sender = await FrameCipher.create(this.secret, salt, this.context(this.self, this.peer, this.localFingerprint, this.remoteFingerprint, this.challenge, remote), 'encrypt')
        this.receiver = await FrameCipher.create(this.secret, salt, this.context(this.peer, this.self, this.remoteFingerprint, this.localFingerprint, remote, this.challenge), 'decrypt')
        this.secret.fill(0)
        const response = await this.sender.process(PROOF)
        if (this.closed) throw new Error('Closed')
        return { response }
      }
      const data = await this.receiver!.process(bytes)
      if (this.closed) throw new Error('Closed')
      if (!this.authenticated) {
        if (data.length !== PROOF.length || PROOF.some((n, i) => n !== data[i])) throw new Error('Invalid confirmation')
        this.authenticated = true
        return { admitted: true }
      }
      return { data }
    } catch { this.close(); throw new Error('Peer authentication failed') }
  }
  async encrypt(bytes: Uint8Array) {
    if (!this.authenticated || this.closed) throw new Error('Peer is not authenticated')
    return this.sender!.process(bytes)
  }
  close() { this.closed = true; this.authenticated = false; this.secret.fill(0); this.sender?.close(); this.receiver?.close() }
}
