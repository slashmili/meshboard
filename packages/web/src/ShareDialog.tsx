import { useEffect, useRef, useState } from 'react'
import QRCode from 'qrcode'
import { Check, Copy, LogOut, X } from 'lucide-react'

export function ShareDialog({ open, invite, onClose, onLeave }: { open: boolean; invite: string; onClose: () => void; onLeave: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null)
  const [qr, setQr] = useState('')
  const [copied, setCopied] = useState(false)
  const [copyError, setCopyError] = useState(false)
  const loopback = ['127.0.0.1', 'localhost', '[::1]'].includes(location.hostname)
  useEffect(() => {
    if (open && !dialog.current?.open) dialog.current?.showModal()
    if (!open && dialog.current?.open) dialog.current?.close()
  }, [open])
  useEffect(() => {
    let cancelled = false
    setQr('')
    setCopied(false)
    if (invite) void QRCode.toDataURL(invite, { width: 232, margin: 2, color: { dark: '#254d3c', light: '#ffffff' }, errorCorrectionLevel: 'M' }).then(value => { if (!cancelled) setQr(value) }).catch(() => { /* The selectable link remains available. */ })
    return () => { cancelled = true }
  }, [invite])
  async function copy() {
    try { await navigator.clipboard.writeText(invite); setCopied(true); setCopyError(false) }
    catch { setCopyError(true) }
  }
  return <dialog ref={dialog} className="dialog share-dialog" aria-labelledby="share-title" onClose={onClose}>
    <button className="dialog-close" aria-label="Close sharing" onClick={onClose}><X size={20} /></button>
    <span className="eyebrow">BETTER WITH COMPANY</span>
    <h2 id="share-title">Make room for a friend.</h2>
    <p>Open this link in another browser or tab to draw together. Anyone with the link can join.</p>
    {qr && <img className="invite-qr" src={qr} width={232} height={232} alt="QR code for this board’s invite link" />}
    <label className="invite-label" htmlFor="invite-link">Board invite</label>
    <div className="invite-copy"><input id="invite-link" readOnly value={invite} onFocus={event => event.currentTarget.select()} /><button className="primary-button" aria-label="Copy invite link" onClick={() => void copy()}>{copied ? <Check size={17} /> : <Copy size={17} />}</button></div>
    <div className="copy-status" role="status">{copyError ? 'Select the link above and copy it manually.' : copied ? 'Link copied. Send it to someone you trust.' : '\u00a0'}</div>
    {loopback && <p className="local-link-note">This address works on this computer only. Use two tabs or browser windows for this checkpoint.</p>}
    <div className="local-notice"><strong>Connection prototype</strong><p>WebRTC encrypts transport, but invitations are not yet authenticated and application-layer encryption is not implemented. Use this checkpoint for test drawings.</p></div>
    <p className="session-lifetime">The board lives with its participants. It disappears when everyone leaves.</p>
    <button className="quiet-button leave-button" onClick={onLeave}><LogOut size={15} /> Leave this board</button>
  </dialog>
}
