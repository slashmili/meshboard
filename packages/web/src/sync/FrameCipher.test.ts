import { expect, it } from 'vitest'
import { createCipheriv, hkdfSync } from 'node:crypto'
import { FrameCipher, decodeSessionKey, newSessionKey, MAX_CLEAR_FRAME, MAX_CIPHER_FRAMES } from './FrameCipher'

const secret = new Uint8Array(32).fill(7), salt = new Uint8Array(32).fill(9)
const context = 'meshboard/phase3/vector/sender->receiver'
const clear = new Uint8Array([0, 0, 1, 0])
const vectors = [
  '4d424501000000000000000001b1593506cf39475764eccdb4115385127f7844',
  '4d42450100000000000000017a1534afd35753a2733f173cc06ed3eecbc319e4',
]
const make = (mode: 'encrypt' | 'decrypt', info = context, key = secret, nonce = salt) => FrameCipher.create(key, nonce, info, mode)

it('matches the JVM vectors and independent Node HKDF/GCM implementation', async () => {
  const sender = await make('encrypt'), receiver = await make('decrypt')
  const key = hkdfSync('sha256', secret, salt, context, 32)
  for (let sequence = 0; sequence < vectors.length; sequence++) {
    const frame = await sender.process(clear)
    expect(Buffer.from(frame).toString('hex')).toBe(vectors[sequence])
    const iv = Buffer.alloc(12); iv.writeBigUInt64BE(BigInt(sequence), 4)
    const independent = createCipheriv('aes-256-gcm', key, iv)
    independent.setAAD(Buffer.concat([frame.subarray(0, 12), Buffer.from(context)]))
    const ciphertext = Buffer.concat([independent.update(clear), independent.final(), independent.getAuthTag()])
    expect(frame.subarray(12)).toEqual(new Uint8Array(ciphertext))
    expect(await receiver.process(frame)).toEqual(clear)
  }
})

it('rejects wrong keys, salts, rooms, directions and fingerprint bindings', async () => {
  const frame = await (await make('encrypt')).process(clear)
  for (const receiver of [
    await make('decrypt', context, new Uint8Array(32)),
    await make('decrypt', context, secret, new Uint8Array(32)),
    await make('decrypt', context + '/other-room'),
    await make('decrypt', 'meshboard/phase3/vector/receiver->sender'),
    await make('decrypt', context + '/other-fingerprint'),
  ]) await expect(receiver.process(frame)).rejects.toThrow('Encrypted frame rejected')
})

it('rejects tampering with every byte and closes on failure', async () => {
  const frame = await (await make('encrypt')).process(clear)
  for (let i = 0; i < frame.length; i++) {
    const changed = frame.slice(); changed[i] ^= 1
    const receiver = await make('decrypt')
    await expect(receiver.process(changed)).rejects.toThrow()
    await expect(receiver.process(frame)).rejects.toThrow()
  }
})

it('rejects replay, gaps, plaintext, truncation and oversized frames', async () => {
  const sender = await make('encrypt'), receiver = await make('decrypt')
  const first = await sender.process(clear), second = await sender.process(clear)
  await receiver.process(first)
  await expect(receiver.process(first)).rejects.toThrow()
  for (const frame of [second, clear, first.slice(0, -1), new Uint8Array(MAX_CLEAR_FRAME + 29)]) {
    await expect((await make('decrypt')).process(frame)).rejects.toThrow()
  }
  for (const frame of [new Uint8Array(), new Uint8Array(MAX_CLEAR_FRAME + 1)]) {
    await expect((await make('encrypt')).process(frame)).rejects.toThrow()
  }
  const max = new Uint8Array(MAX_CLEAR_FRAME).fill(42)
  expect(await (await make('decrypt')).process(await (await make('encrypt')).process(max))).toEqual(max)
})

it('fails closed on concurrent use or disposal during an operation', async () => {
  const sender = await make('encrypt')
  const first = sender.process(clear)
  const checks = [expect(first).rejects.toThrow(), expect(sender.process(clear)).rejects.toThrow()]
  await Promise.all(checks)
  const disposed = await make('encrypt')
  const result = disposed.process(clear); disposed.close()
  await expect(result).rejects.toThrow()
})

it('exhausts the connection budget without resetting or reusing a nonce', async () => {
  const sender = await make('encrypt'), receiver = await make('decrypt')
  // Advance to the boundary without a million expensive cryptographic operations.
  Reflect.set(sender, 'sequence', MAX_CIPHER_FRAMES - 1)
  Reflect.set(receiver, 'sequence', MAX_CIPHER_FRAMES - 1)
  const last = await sender.process(clear)
  expect(await receiver.process(last)).toEqual(clear)
  await expect(sender.process(clear)).rejects.toThrow()
  await expect(receiver.process(last)).rejects.toThrow()
})

it('uses canonical 256-bit fragment keys and validates derivation inputs', async () => {
  const keys = Array.from({ length: 20 }, newSessionKey)
  expect(new Set(keys).size).toBe(20)
  for (const key of keys) expect(decodeSessionKey(key)).toHaveLength(32)
  for (const key of ['', 'a'.repeat(42), 'A'.repeat(42) + 'B', 'A'.repeat(43) + '=', '+'.repeat(43)]) expect(() => decodeSessionKey(key)).toThrow()
  for (const [key, nonce, info] of [[new Uint8Array(31), salt, context], [secret, new Uint8Array(31), context], [secret, salt, ''], [secret, salt, 'x'.repeat(4097)]] as const) {
    await expect(FrameCipher.create(key, nonce, info, 'encrypt')).rejects.toThrow()
  }
})
