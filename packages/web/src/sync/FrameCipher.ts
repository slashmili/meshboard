/** Phase 3 encryption primitive. Callers must authenticate the derivation context
 * and ensure fresh directional derivation inputs for every connection; this alone is not a handshake. */
export const MAX_CLEAR_FRAME = 16 * 1024
export const MAX_CIPHER_FRAMES = 1_048_576
const MAGIC = new Uint8Array([0x4d, 0x42, 0x45, 1])
const encoder = new TextEncoder()

export function newSessionKey(): string {
  return btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(32))))
    .replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '')
}

export function decodeSessionKey(value: string): Uint8Array<ArrayBuffer> {
  if (!/^[A-Za-z0-9_-]{43}$/.test(value)) throw new Error('Invalid session key')
  const bytes = Uint8Array.from(atob(value.replaceAll('-', '+').replaceAll('_', '/') + '='), c => c.charCodeAt(0))
  // Require canonical base64url, including the otherwise unused final two bits.
  if (btoa(String.fromCharCode(...bytes)).replaceAll('+', '-').replaceAll('/', '_').slice(0, -1) !== value) throw new Error('Invalid session key')
  return bytes
}

/** One direction, one connection, ordered reliable transport. Fatal errors close
 * this instance; reconnects must derive fresh keys, never reset the counter. */
export class FrameCipher {
  private sequence = 0
  private busy = false
  private key: CryptoKey | undefined
  private constructor(key: CryptoKey, private mode: 'encrypt' | 'decrypt', private context: Uint8Array<ArrayBuffer>) { this.key = key }

  static async create(secret: Uint8Array, salt: Uint8Array, context: string, mode: 'encrypt' | 'decrypt') {
    const info = encoder.encode(context)
    if (secret.length !== 32 || salt.length !== 32 || info.length < 1 || info.length > 4096) throw new Error('Invalid key context')
    const connectionSalt = salt.slice()
    const input = await crypto.subtle.importKey('raw', secret.slice(), 'HKDF', false, ['deriveKey'])
    const key = await crypto.subtle.deriveKey({ name: 'HKDF', hash: 'SHA-256', salt: connectionSalt, info }, input,
      { name: 'AES-GCM', length: 256 }, false, [mode])
    return new FrameCipher(key, mode, info)
  }

  close() { this.key = undefined }

  async process(input: Uint8Array): Promise<Uint8Array<ArrayBuffer>> {
    try {
      if (!this.key || this.busy || this.sequence >= MAX_CIPHER_FRAMES) throw new Error('Cipher is closed or unavailable')
      this.busy = true
      const key = this.key
      const header = new Uint8Array(12)
      header.set(MAGIC)
      new DataView(header.buffer).setBigUint64(4, BigInt(this.sequence))
      const iv = new Uint8Array(12)
      iv.set(header.subarray(4), 4)
      const aad = new Uint8Array(header.length + this.context.length)
      aad.set(header); aad.set(this.context, header.length)
      let result: Uint8Array<ArrayBuffer>
      if (this.mode === 'encrypt') {
        if (!input.length || input.length > MAX_CLEAR_FRAME) throw new Error('Invalid clear frame size')
        const ciphertext = await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: aad, tagLength: 128 }, key, input.slice())
        result = new Uint8Array(header.length + ciphertext.byteLength)
        result.set(header); result.set(new Uint8Array(ciphertext), header.length)
      } else {
        if (input.length < 29 || input.length > MAX_CLEAR_FRAME + 28 || header.some((value, i) => input[i] !== value)) throw new Error('Invalid encrypted frame or sequence')
        result = new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv, additionalData: aad, tagLength: 128 }, key, input.slice(12)))
      }
      // A concurrent call or disposal must also invalidate an in-flight result.
      if (!this.key) throw new Error('Cipher is closed')
      this.sequence++
      return result
    } catch {
      this.close()
      throw new Error('Encrypted frame rejected')
    } finally { this.busy = false }
  }
}
