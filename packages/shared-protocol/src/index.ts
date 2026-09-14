import { z } from 'zod'

export const PROTOCOL_VERSION = 1
export const MAX_PEERS = 8
export const MAX_ELEMENTS = 2_000
export const MAX_REMOVED = 10_000
export const MAX_POINTS = 12_000
export const MAX_MESSAGE_BYTES = 4 * 1024 * 1024
export const MAX_FRAME_BYTES = 16 * 1024
export const MAX_SIGNAL_BYTES = 32 * 1024

const id = z.string().min(1).max(80).regex(/^[a-zA-Z0-9-]+$/)
const peerId = z.uuid()
const point = z.object({ x: z.number().min(-1e7).max(1e7), y: z.number().min(-1e7).max(1e7) }).strict()
export const elementSchema = z.object({
  id,
  type: z.enum(['pen', 'rectangle', 'ellipse', 'line']),
  color: z.string().regex(/^#[0-9a-fA-F]{6}$/),
  width: z.number().min(1).max(32),
  points: z.array(point).min(1).max(MAX_POINTS),
}).strict().refine(value => value.type === 'pen' || value.points.length === 2, 'Shapes need two points')
export type BoardElement = z.infer<typeof elementSchema>

export const boardMessageSchema = z.discriminatedUnion('type', [
  z.object({ v: z.literal(1), type: z.literal('put'), element: elementSchema }).strict(),
  z.object({ v: z.literal(1), type: z.literal('remove'), ids: z.array(id).max(MAX_REMOVED) }).strict(),
  z.object({ v: z.literal(1), type: z.literal('snapshot'), elements: z.array(elementSchema).max(MAX_ELEMENTS), removed: z.array(id).max(MAX_REMOVED) }).strict(),
  z.object({ v: z.literal(1), type: z.literal('preview'), element: elementSchema.nullable() }).strict(),
])
export type BoardMessage = z.infer<typeof boardMessageSchema>
export type Snapshot = Extract<BoardMessage, { type: 'snapshot' }>

export const frameSchema = z.object({
  v: z.literal(1), id: z.uuid(), index: z.number().int().min(0).max(1023),
  total: z.number().int().min(1).max(1024), data: z.string().max(8_000),
}).strict()

const description = z.object({ type: z.enum(['offer', 'answer']), sdp: z.string().startsWith('v=0').max(24_000) }).strict()
const candidate = z.object({
  candidate: z.string().max(2_048), sdpMid: z.string().max(64).nullable(),
  sdpMLineIndex: z.number().int().min(0).max(16).nullable(), usernameFragment: z.string().max(256).nullable().optional(),
}).strict()
export const signalPayloadSchema = z.union([
  z.object({ description }).strict(), z.object({ candidate }).strict(),
])
export type SignalPayload = z.infer<typeof signalPayloadSchema>
export const clientSignalSchema = z.discriminatedUnion('type', [
  z.object({ v: z.literal(1), type: z.literal('join'), room: z.uuid() }).strict(),
  z.object({ v: z.literal(1), type: z.literal('signal'), to: peerId, payload: signalPayloadSchema }).strict(),
])
export const serverSignalSchema = z.discriminatedUnion('type', [
  z.object({ v: z.literal(1), type: z.literal('welcome'), self: peerId, peers: z.array(peerId).max(MAX_PEERS - 1) }).strict(),
  z.object({ v: z.literal(1), type: z.literal('peer-joined'), peer: peerId }).strict(),
  z.object({ v: z.literal(1), type: z.literal('peer-left'), peer: peerId }).strict(),
  z.object({ v: z.literal(1), type: z.literal('signal'), from: peerId, payload: signalPayloadSchema }).strict(),
  z.object({ v: z.literal(1), type: z.literal('error'), code: z.enum(['room-full', 'invalid-message', 'rate-limit', 'server-full']) }).strict(),
])
export type ServerSignal = z.infer<typeof serverSignalSchema>

export const rtcConfigSchema = z.object({
  iceServers: z.array(z.object({ urls: z.array(z.string().regex(/^(stun|stuns|turn|turns):/)).min(1).max(8), username: z.string().optional(), credential: z.string().optional() })).max(8),
  iceTransportPolicy: z.enum(['all', 'relay']),
  localDevelopment: z.boolean(),
}).strict()
export type RtcConfig = z.infer<typeof rtcConfigSchema>
