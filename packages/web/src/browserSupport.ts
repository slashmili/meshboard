/** Required browser capabilities. Version policy lives in docs/support.md.
 * Check before importing the board, whose modules initialize browser APIs. */
export function browserProblem(): string | null {
  if (!globalThis.isSecureContext) return 'Open Meshboard over HTTPS. Local development can use localhost.'
  if (typeof crypto === 'undefined' || typeof crypto.getRandomValues !== 'function' ||
      typeof crypto.randomUUID !== 'function' || !crypto.subtle) {
    return 'This browser cannot provide the encryption Meshboard needs. Update your browser or use another supported browser.'
  }
  if (typeof RTCPeerConnection !== 'function' || typeof RTCDataChannel !== 'function' ||
      typeof WebSocket !== 'function') {
    return 'This browser cannot connect to other participants. Enable WebRTC or use another supported browser.'
  }
  if (typeof PointerEvent !== 'function' || typeof SVGSVGElement !== 'function' ||
      typeof TextEncoder !== 'function' || typeof TextDecoder !== 'function' ||
      typeof matchMedia !== 'function' || typeof createImageBitmap !== 'function' ||
      typeof FileReader !== 'function' || typeof File === 'undefined' ||
      typeof Blob === 'undefined' || typeof Blob.prototype.arrayBuffer !== 'function' ||
      typeof URLSearchParams === 'undefined' || new URLSearchParams('a=1').size !== 1) {
    return 'This browser is missing drawing or file features Meshboard needs. Update your browser or use another supported browser.'
  }
  return null
}
