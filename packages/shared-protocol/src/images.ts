export const MAX_IMAGE_BYTES = 512 * 1024
export const MAX_IMAGE_EDGE = 1024
/** Inspect dimensions before allocating decoded pixels. No external URLs or SVG. */
export function imageInfo(bytes: Uint8Array): { mime: string; width: number; height: number } {
  if (!bytes.length || bytes.length > MAX_IMAGE_BYTES) throw new Error('Choose a PNG or JPEG up to 512 KiB and 1024 × 1024 pixels.')
  const u16 = (i: number) => bytes[i] * 256 + bytes[i + 1]
  const u32 = (i: number) => u16(i) * 65536 + u16(i + 2)
  let mime = '', width = 0, height = 0
  if (bytes.length >= 33 && [137,80,78,71,13,10,26,10].every((b,i)=>bytes[i]===b) && u32(8) === 13 && String.fromCharCode(...bytes.slice(12,16)) === 'IHDR') {
    mime = 'image/png'; width = u32(16); height = u32(20)
    let ended = false
    for (let i=8;i+12<=bytes.length;) {
      const length = u32(i), type = String.fromCharCode(...bytes.slice(i+4,i+8))
      if (i+12+length > bytes.length || type === 'acTL' || (i !== 8 && type === 'IHDR')) throw new Error('Invalid or animated PNG.')
      i += 12+length
      if (type === 'IEND') { ended = length === 0 && i === bytes.length; break }
    }
    if (!ended) throw new Error('Incomplete PNG.')
  } else if (bytes[0] === 255 && bytes[1] === 216) {
    mime = 'image/jpeg'
    for (let i = 2; i + 3 < bytes.length;) {
      if (bytes[i++] !== 255) break
      while (bytes[i] === 255) i++
      const marker = bytes[i++]; if (marker === 218 || marker === 217) break
      const length = u16(i)
      if (length < 2 || i + length > bytes.length) break
      if ((marker === 192 || marker === 194) && length >= 8) { height = u16(i+3); width = u16(i+5); break }
      i += length
    }
  }
  if (width < 1 || height < 1 || width > MAX_IMAGE_EDGE || height > MAX_IMAGE_EDGE) throw new Error('Choose a PNG or JPEG up to 512 KiB and 1024 × 1024 pixels.')
  return {mime,width,height}
}
export function imageDataInfo(data: string) {
  if (data.length > 699100 || !/^data:image\/(png|jpeg);base64,[A-Za-z0-9+/]+={0,2}$/.test(data)) throw new Error('Invalid embedded image.')
  const [prefix, encoded] = data.split(',')
  const raw = atob(encoded)
  if (btoa(raw) !== encoded) throw new Error('Invalid image encoding.')
  const info = imageInfo(Uint8Array.from(raw, c=>c.charCodeAt(0)))
  if (prefix !== `data:${info.mime};base64`) throw new Error('Image type mismatch.')
  return info
}
export function validImageData(data: string) { try { imageDataInfo(data); return true } catch { return false } }
