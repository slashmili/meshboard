import { imageInfo, MAX_IMAGE_BYTES, type BoardElement } from '@meshboard/shared-protocol'
import type { Point } from './board'
export async function importImage(file: File, center: Point): Promise<BoardElement> {
  if (file.size > MAX_IMAGE_BYTES) throw new Error('Choose a PNG or JPEG up to 512 KiB and 1024 × 1024 pixels.')
  const bytes = new Uint8Array(await file.arrayBuffer())
  const info = imageInfo(bytes)
  const bitmap = await createImageBitmap(new Blob([bytes], {type:info.mime}))
  bitmap.close()
  let raw = ''
  for (let i=0;i<bytes.length;i+=8192) raw += String.fromCharCode(...bytes.subarray(i,i+8192))
  const scale = Math.min(1,400/Math.max(info.width,info.height)), w=info.width*scale, h=info.height*scale
  return {id:crypto.randomUUID(),type:'image',color:'#293b36',width:1,points:[{x:center.x-w/2,y:center.y-h/2},{x:center.x+w/2,y:center.y+h/2}],image:`data:${info.mime};base64,${btoa(raw)}`}
}
