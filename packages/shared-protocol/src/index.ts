import { validImageData } from './images.ts'
export { imageInfo, imageDataInfo, MAX_IMAGE_BYTES } from './images.ts'
import { z } from 'zod'

export const MAX_PEERS = 8
export const MAX_ELEMENTS = 2_000
export const MAX_POINTS = 12_000
export const MAX_SIGNAL_BYTES = 32 * 1024

const id = z.string().min(1).max(80).regex(/^[a-zA-Z0-9-]+$/)
const point = z.object({ x: z.number().min(-1e7).max(1e7), y: z.number().min(-1e7).max(1e7), pressure: z.number().min(0).max(1).optional() }).strict()
const attachment = z.object({ id, x: z.number().min(0).max(1), y: z.number().min(0).max(1) }).strict()
export const elementSchema = z.object({
  id,
  type: z.enum(['pen', 'rectangle', 'ellipse', 'line', 'arrow', 'text', 'sticky', 'image']),
  color: z.string().regex(/^#[0-9a-fA-F]{6}$/),
  width: z.number().min(1).max(32),
  points: z.array(point).min(1).max(MAX_POINTS),
  startAttachment: attachment.optional(),
  endAttachment: attachment.optional(),
  image: z.string().refine(validImageData, 'Invalid image').optional(),
  text: z.string().min(1).max(500).refine(value => !/[\uD800-\uDFFF]/u.test(value), 'Text must contain valid Unicode').optional(),
}).strict().refine(value => [value.startAttachment,value.endAttachment].every(a => !a || (value.type === 'arrow' && a.id !== value.id)), 'Only arrows attach to other objects').refine(value => (value.type === 'image') === (value.image !== undefined), 'Only images require image data').refine(value => value.type === 'pen' || value.points.length === 2, 'Shapes need two points').refine(value => (value.type === 'text' || value.type === 'sticky') === (value.text !== undefined), 'Only text boxes and notes require text').refine(value => value.type === 'pen' || value.points.every(p => p.pressure === undefined), 'Only pen strokes support pressure')
export type BoardElement = z.infer<typeof elementSchema>

const description = z.object({ type: z.enum(['offer', 'answer']), sdp: z.string().startsWith('v=0').max(24_000) }).strict()
const candidate = z.object({
  candidate: z.string().max(2_048), sdpMid: z.string().max(64).nullable(),
  sdpMLineIndex: z.number().int().min(0).max(16).nullable(), usernameFragment: z.string().max(256).nullable().optional(),
}).strict()
export const signalPayloadSchema = z.union([
  z.object({ description }).strict(), z.object({ candidate }).strict(),
])
export type SignalPayload = z.infer<typeof signalPayloadSchema>
export const rtcConfigSchema = z.object({
  iceServers: z.array(z.object({ urls: z.array(z.string().regex(/^(stun|stuns|turn|turns):/)).min(1).max(8), username: z.string().optional(), credential: z.string().optional() })).max(8),
  iceTransportPolicy: z.enum(['all', 'relay']),
  localDevelopment: z.boolean(),
}).strict()
export type RtcConfig = z.infer<typeof rtcConfigSchema>
