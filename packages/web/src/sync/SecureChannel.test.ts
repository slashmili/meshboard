import { expect, it, vi } from 'vitest'
import { SecureChannel, fingerprint } from './SecureChannel'
import { newSessionKey } from './FrameCipher'

export const room = '12345678-1234-4234-8234-123456789abc'
export const alice = '12345678-1234-4234-8234-123456789abd', bob = '12345678-1234-4234-8234-123456789abe'
export const sdp = (byte: string) => `v=0\r\na=fingerprint:sha-256 ${Array(32).fill(byte).join(':')}\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n`
const pair = (key = newSessionKey(), otherKey = key, remoteSdp = sdp('22')) => [
  new SecureChannel(key, room, alice, bob, sdp('11'), remoteSdp),
  new SecureChannel(otherKey, room, bob, alice, sdp('22'), sdp('11')),
] as const
export async function authenticate(a: SecureChannel, b: SecureChannel) {
  const first = await a.receive(b.hello), second = await b.receive(a.hello)
  expect((await a.receive(second.response!)).admitted).toBe(true)
  expect((await b.receive(first.response!)).admitted).toBe(true)
}
it('admits only after encrypted confirmation and exchanges both directions', async () => {
  const [a, b] = pair()
  await expect(a.encrypt(new Uint8Array([1]))).rejects.toThrow()
  await authenticate(a, b)
  expect((await b.receive(await a.encrypt(new Uint8Array([0, 1, 2])))).data).toEqual(new Uint8Array([0, 1, 2]))
  expect((await a.receive(await b.encrypt(new Uint8Array([3])))).data).toEqual(new Uint8Array([3]))
})
it('rejects wrong keys and signaling fingerprint substitution', async () => {
  const key = newSessionKey()
  for (const [a, b] of [pair(key, newSessionKey()), pair(key, key, sdp('33'))]) {
    await a.receive(b.hello)
    const proof = await b.receive(a.hello)
    await expect(a.receive(proof.response!)).rejects.toThrow('Peer authentication failed')
    expect(a.authenticated).toBe(false)
  }
})
it('rejects reflected hellos, duplicate hellos, plaintext and reconnect replay', async () => {
  const [a, b] = pair()
  await expect(a.receive(a.hello)).rejects.toThrow()
  const [c, d] = pair()
  await c.receive(d.hello)
  await expect(c.receive(d.hello)).rejects.toThrow()
  await expect(b.receive(new Uint8Array([0, 0, 1, 0]))).rejects.toThrow()
  const key = newSessionKey(), [e, f] = pair(key)
  const old = await f.receive(e.hello)
  const [g] = pair(key); await g.receive(f.hello)
  await expect(g.receive(old.response!)).rejects.toThrow()
})
it('rejects altered rooms and peer identities', async () => {
  const key = newSessionKey()
  for (const other of [new SecureChannel(key, bob, bob, alice, sdp('22'), sdp('11')), new SecureChannel(key, room, bob, room, sdp('22'), sdp('11'))]) {
    const [a] = pair(key); await a.receive(other.hello)
    await expect(a.receive((await other.receive(a.hello)).response!)).rejects.toThrow()
  }
})
it('accepts only unambiguous SHA256 fingerprints and distinct UUID identities', () => {
  expect(fingerprint(sdp('AB'))).toBe('ab'.repeat(32))
  for (const value of ['', sdp('11').replace('sha-256', 'sha-1'), sdp('11') + 'a=fingerprint:sha-256 ' + Array(32).fill('22').join(':') + '\r\n', sdp('GG')]) expect(() => fingerprint(value)).toThrow()
  expect(() => new SecureChannel(newSessionKey(), room, alice, alice, sdp('11'), sdp('22'))).toThrow()
})

it('matches the independent JVM handshake confirmation vector', async () => {
  const random = vi.spyOn(crypto, 'getRandomValues')
  try {
    random.mockImplementationOnce(bytes => { (bytes as Uint8Array).fill(1); return bytes })
    const a = new SecureChannel('A'.repeat(43), room, alice, bob, sdp('11'), sdp('22'))
    random.mockImplementationOnce(bytes => { (bytes as Uint8Array).fill(2); return bytes })
    const b = new SecureChannel('A'.repeat(43), room, bob, alice, sdp('22'), sdp('11'))
    expect(Buffer.from((await a.receive(b.hello)).response!).toString('hex')).toBe('4d42450100000000000000004dbdefb9a050490bd5dcc0a7d1f9e958e8732708')
  } finally { random.mockRestore() }
})
