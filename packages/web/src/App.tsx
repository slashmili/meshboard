import { importImage } from './images'
import { marquee, selectionBounds } from './selection'
import { useEffect, useRef, useState, type CSSProperties } from 'react'
import { ImagePlus, Undo2, Redo2, ArrowDownLeft, ArrowUpRight, Circle, CircleHelp, Eraser, Hand, Minus, MousePointer2, Type, StickyNote, Pencil, Plus, RotateCcw, Share2, Slash, Square, Trash2, X, type LucideIcon } from 'lucide-react'
import { Element } from './Element'
import { MAX_ZOOM, MIN_ZOOM, hitsElement, type Point, type BoardElement, type Tool } from './board'
import { textElement } from './textElements'
import { useBoard } from './useBoard'
import { CrdtDocument } from './sync/CrdtDocument'
import { useSession } from './sync/useSession'
import { ShareDialog } from './ShareDialog'
import { useTheme, type ThemeMode } from './useTheme'

const TOOLS: { id: Tool; label: string; key: string; icon: LucideIcon }[] = [
  { id: 'select', label: 'Select', key: 'V', icon: MousePointer2 },
  { id: 'pen', label: 'Pen', key: 'P', icon: Pencil },
  { id: 'eraser', label: 'Eraser', key: 'E', icon: Eraser },
  { id: 'rectangle', label: 'Rectangle', key: 'R', icon: Square },
  { id: 'ellipse', label: 'Ellipse', key: 'O', icon: Circle },
  { id: 'arrow', label: 'Arrow', key: 'A', icon: ArrowUpRight },
  { id: 'line', label: 'Line', key: 'L', icon: Slash },
  { id: 'text', label: 'Text', key: 'T', icon: Type },
  { id: 'sticky', label: 'Sticky note', key: 'N', icon: StickyNote },
  { id: 'hand', label: 'Pan', key: 'H', icon: Hand },
]
const COLORS = [
  { name: 'Ink', value: '#293b36' },
  { name: 'Fern', value: '#387c59' },
  { name: 'Blue', value: '#4878c8' },
  { name: 'Violet', value: '#9563be' },
  { name: 'Coral', value: '#d76857' },
  { name: 'Amber', value: '#c49226' },
]
const HINTS: Record<Tool, string> = {
  select: 'Click an object or drag a box around several, then drag to move. Shift-click toggles objects. Escape cancels.',
  pen: 'Click and drag to draw. A single click makes a dot.',
  eraser: 'Click or drag across a stroke to erase the whole object.',
  rectangle: 'Drag to draw a rectangle. Hold Shift for a square.',
  ellipse: 'Drag to draw an ellipse. Hold Shift for a circle.',
  arrow: 'Drag from the tail to the arrowhead. Hold Shift to snap the angle.',
  line: 'Drag to draw a line. Hold Shift to snap the angle.',
  text: 'Click to add text. Click existing text or a note to edit it.',
  sticky: 'Click to add a note. Click existing text or a note to edit it.',
  hand: 'Drag to move around. Pinch with two fingers to zoom.',
}

export function App() {
  const theme = useTheme()
  const [tool, setTool] = useState<Tool>('pen')
  const [color, setColor] = useState(COLORS[0].value)
  const [width, setWidth] = useState(3)
  const [document] = useState(() => new CrdtDocument())
  const session = useSession(document)
  const [textEdit, setTextEdit] = useState<{ element: BoardElement; existing: boolean } | null>(null)
  const [textValue, setTextValue] = useState('')
  const [textError, setTextError] = useState('')
  const textDialog = useRef<HTMLDialogElement>(null)
  function editText(at: Point, kind: 'text' | 'sticky') {
    const existing = [...document.getElements()].reverse().find(e => e.text !== undefined && hitsElement(e, at, at, 0))
    setTextEdit({ element: existing ?? textElement(crypto.randomUUID(), kind, at, color, ''), existing: !!existing })
    setTextValue(existing?.text ?? ''); setTextError('')
    textDialog.current?.showModal()
  }
  function saveText() {
    if (!textEdit || !textValue.trim()) return
    if (textEdit.existing && !document.getElements().some(e => e.id === textEdit.element.id)) {
      setTextError('This object was deleted by another participant. Cancel to close.'); return
    }
    const original = textEdit.element
    if (document.put(textElement(original.id, original.type as 'text' | 'sticky', original.points[0], original.color, textValue))) {
      textDialog.current?.close(); setTextEdit(null)
    } else setTextError('Could not save this text. The board may have reached its size limit.')
  }
  const board = useBoard(tool, color, width, document, session.preview, editText)
  const imageInput = useRef<HTMLInputElement>(null)
  const [imageError, setImageError] = useState('')
  const [imageBusy, setImageBusy] = useState(false)
  const [sharing, setSharing] = useState(false)
  const helpDialog = useRef<HTMLDialogElement>(null)
  const clearDialog = useRef<HTMLDialogElement>(null)
  const leaveDialog = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.target instanceof HTMLElement && (event.target.closest('input, textarea, select, dialog') || event.target.isContentEditable)) return
      if ((event.ctrlKey || event.metaKey) && !event.altKey && ['z', 'y'].includes(event.key.toLowerCase())) {
        event.preventDefault(); board.cancelGesture()
        if (event.shiftKey || event.key.toLowerCase() === 'y') document.redo(); else document.undo()
        return
      }
      if (event.ctrlKey || event.metaKey || event.altKey) return
      const next = TOOLS.find(item => item.key.toLowerCase() === event.key.toLowerCase())
      if (next) {
        board.cancelGesture()
        setTool(next.id)
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [board.cancelGesture, document])

  const { x, y, zoom } = board.view
  const currentTool = TOOLS.find(item => item.id === tool)!
  const drawing = tool !== 'hand' && tool !== 'eraser'
  const remoteDrafts = session.drafts.filter(draft => !board.elements.some(element => element.id === draft.id))
  const empty = board.elements.length === 0 && !board.draft && remoteDrafts.length === 0
  const connectionLabel = !session.room ? 'Local only' : session.status.connected ? `${session.status.connected} peer${session.status.connected === 1 ? '' : 's'} connected` : session.status.connecting ? 'Connecting…' : session.status.signaling ? 'Waiting for a peer' : 'Joining…'

  return (
    <main className="app">
      <header className="header">
        <div className="brand" aria-label="Meshboard">
          <img className="brand-mark" src="/meshboard-icon.svg" alt="" data-testid="brand-logo" />
          <span>Meshboard</span>
        </div>
        <span className="header-divider" />
        <div className="board-title">Untitled board <span>A space for ideas</span></div>
        <div className="header-actions">
          <span className="local-badge" role="status" data-testid="connection-status"><span className="status-dot" /> {connectionLabel}</span>
          <button className="primary-button share-button" aria-label="Share board" onClick={() => { session.share(); setSharing(true) }}><Share2 size={15} /><span>Share</span></button>
          <button className="quiet-button clear-button" aria-label="Clear board" disabled={board.elements.length === 0} onClick={() => clearDialog.current?.showModal()}><Trash2 size={16} /><span>Clear board</span></button>
        </div>
      </header>

      <section className="workspace" aria-label="Whiteboard workspace">
        <input ref={imageInput} type="file" accept="image/png,image/jpeg" aria-label="Image file" hidden onChange={async event => {
          const file = event.target.files?.[0]; event.target.value = ''
          if (!file) return
          setImageBusy(true); setImageError(''); board.cancelGesture()
          try {
            const canvas = window.document.querySelector('[aria-label="Drawing canvas"]')!.getBoundingClientRect()
            const element = await importImage(file, {x:(canvas.width/2-board.view.x)/board.view.zoom,y:(canvas.height/2-board.view.y)/board.view.zoom})
            if (!document.put(element)) setImageError('Could not insert image. This board has reached its size or image limit.')
          } catch { setImageError('Cannot insert this image. Choose a valid PNG or JPEG up to 512 KiB and 1024 × 1024 pixels.') }
          finally { setImageBusy(false) }
        }} />
        {imageError && <div className="connection-error" role="alert">{imageError}<button onClick={() => setImageError('')}>Dismiss</button></div>}
        {session.error && <div className="connection-error" role="alert"><span>{session.error}</span>{session.room && <button onClick={session.retry}>Retry</button>}</div>}
        <svg ref={board.svgRef} className={`canvas ${board.spaceDown || tool === 'hand' ? 'canvas-pan' : tool === 'eraser' ? 'canvas-eraser' : ''}`} aria-label="Drawing canvas" aria-describedby="canvas-instructions" tabIndex={0} {...board.pointerHandlers} onContextMenu={event => event.preventDefault()}>
          <defs><pattern id="grid" width={24 * zoom} height={24 * zoom} patternUnits="userSpaceOnUse" x={x} y={y}><circle cx={1} cy={1} r={0.8} fill="#cbd3ca" /></pattern></defs>
          <rect width="100%" height="100%" fill="url(#grid)" pointerEvents="none" />
          <g transform={`translate(${x} ${y}) scale(${zoom})`} pointerEvents="none" data-testid="drawing-elements">
            {board.elements.map(element => <Element key={element.id} element={element} />)}
            {board.draft && <Element element={board.draft} />}
          </g>
          {tool === 'select' && <g transform={`translate(${x} ${y}) scale(${zoom})`} pointerEvents="none" data-testid="selection-overlay">
            {board.elements.filter(e => board.selection.ids.includes(e.id)).map(e => {
              const box = selectionBounds(e), padding = 4 / zoom
              return <rect key={e.id} x={box.x-padding} y={box.y-padding} width={box.width+2*padding} height={box.height+2*padding} fill="none" stroke="#4878c8" strokeWidth={1.5 / zoom} strokeDasharray={`${5 / zoom} ${3 / zoom}`} />
            })}
            {marquee(board.selection) && <rect {...marquee(board.selection)!} fill="#4878c815" stroke="#4878c8" strokeWidth={1 / zoom} />}
          </g>}
          <g transform={`translate(${x} ${y}) scale(${zoom})`} pointerEvents="none" opacity={0.65} data-testid="remote-drafts">{remoteDrafts.map(element => <Element key={element.id} element={element} />)}</g>
        </svg>

        <div className="tool-panel" role="toolbar" aria-label="Drawing tools">
          {TOOLS.map(({ id, label, key, icon: Icon }) => <div key={id} className={id === 'hand' ? 'tool-item tool-item-separated' : 'tool-item'}>
            <button className={`tool-button ${tool === id ? 'selected' : ''}`} aria-label={`${label} (${key})`} aria-pressed={tool === id} title={`${label} (${key})`} onClick={() => { board.cancelGesture(); setTool(id) }}><Icon size={21} strokeWidth={1.7} /><span className="tool-key">{key}</span></button>
          </div>)}
        </div>

        <aside className="style-panel" aria-label="Stroke style">
          <div className="panel-caption">STROKE</div>
          <div className="color-options" role="radiogroup" aria-label="Stroke color">
            {COLORS.map(item => <button key={item.name} className={`color-button ${color === item.value ? 'active' : ''}`} role="radio" aria-checked={color === item.value} aria-label={item.name} title={item.name} disabled={!drawing} onClick={() => setColor(item.value)} style={{ '--swatch': item.value } as CSSProperties}><span /></button>)}
          </div>
          <div className="width-options" role="radiogroup" aria-label="Stroke width">
            {[{ value: 2, name: 'Fine' }, { value: 3, name: 'Medium' }, { value: 6, name: 'Bold' }].map(item => <button key={item.value} className={width === item.value ? 'active' : ''} role="radio" aria-checked={width === item.value} aria-label={`${item.name} stroke`} title={`${item.name} stroke`} disabled={!drawing || tool === 'text' || tool === 'sticky'} onClick={() => setWidth(item.value)}><span style={{ height: item.value }} /></button>)}
          </div>
        </aside>

        {empty && <div className="empty-state" aria-hidden="true">
          <div className="idea-sketch">
            <svg viewBox="0 0 180 116" fill="none"><path d="M35 37 94 26l10 62-61 8z" fill="#e3eddc" stroke="#77946d" strokeWidth="1.5" strokeLinejoin="round" /><path d="m67 18 62 8-8 64-62-8z" fill="#f8f9f2" stroke="#49745b" strokeWidth="1.8" strokeLinejoin="round" /><path d="m79 45 30 4M77 55l22 3M76 65l28 3" stroke="#7d9674" strokeWidth="2" strokeLinecap="round" /><path d="m133 67 14-12 5 7-13 12-9 3z" fill="#d6eca2" stroke="#49745b" strokeWidth="1.6" strokeLinejoin="round" /><path d="m143 31 4-7m5 13 8-1M36 70l-8 3M45 19l-5-6" stroke="#8ba273" strokeWidth="1.7" strokeLinecap="round" /></svg>
          </div>
          <span className="eyebrow">A LITTLE SPACE. ENDLESS POSSIBILITIES.</span>
          <h1>What’s on your mind?</h1>
          <p>A rough sketch. A big idea. A place to start.<br />Pick a tool and make it yours.</p>
          <span className="start-hint"><currentTool.icon size={14} /> {tool === 'pen' ? 'Your pen is ready' : `${currentTool.label} selected`}</span>
        </div>}

        <div className="session-note"><span className="note-dot" /><span>{session.room ? 'Shared live. Never saved.' : 'Just this tab, just for now.'}<br /><strong>{session.room ? 'Gone when everyone leaves.' : 'Refreshing clears your board.'}</strong></span></div>

        <div className="canvas-footer">
          <div className="zoom-panel" role="group" aria-label="Canvas view">
            <button aria-label="Zoom out" title="Zoom out" disabled={zoom <= MIN_ZOOM} onClick={() => board.zoomBy(1 / 1.2)}><Minus size={17} /></button>
            <span className="zoom-value" aria-live="polite">{Math.round(zoom * 100)}%</span>
            <button aria-label="Zoom in" title="Zoom in" disabled={zoom >= MAX_ZOOM} onClick={() => board.zoomBy(1.2)}><Plus size={17} /></button>
            <span className="zoom-divider" />
            <button aria-label="Reset view" title="Reset view to 100% and original position" onClick={board.resetView}><RotateCcw size={15} /></button>
          </div>
          <div className="zoom-panel" role="group" aria-label="Edit history">
            <button aria-label="Insert image" title="Insert PNG or JPEG" disabled={imageBusy} onClick={() => imageInput.current?.click()}><ImagePlus size={17} /></button>
            <button className="quiet-button history-button" aria-label="Undo" title="Undo (Ctrl/⌘ Z)" disabled={!document.canUndo} onClick={() => { board.cancelGesture(); document.undo() }}><Undo2 size={17} /></button>
            <button className="quiet-button history-button" aria-label="Redo" title="Redo (Ctrl/⌘ Shift Z)" disabled={!document.canRedo} onClick={() => { board.cancelGesture(); document.redo() }}><Redo2 size={17} /></button>
          </div>
          <button className="help-button" aria-label="Help and keyboard shortcuts" title="Help and keyboard shortcuts" onClick={() => helpDialog.current?.showModal()}><CircleHelp size={20} /></button>
        </div>
      </section>

      <footer className="status-bar">
        <div id="canvas-instructions"><span className="active-tool">{board.spaceDown ? 'Pan' : currentTool.label}</span><span className="tool-hint">{board.spaceDown ? 'Drag to move around the board.' : HINTS[tool]}</span></div>
        <span className="checkpoint-label" data-testid="connection-route">{session.status.connected ? session.status.relay ? 'VIA RELAY' : 'DIRECT CONNECTION' : 'DRAW TOGETHER'} <span>/</span> 02</span>
      </footer>

      {/* A delayed close event from the previous edit must not clear a newly opened editor. */}
      <dialog ref={textDialog} className="dialog" aria-labelledby="text-editor-title" onClose={event => { if (!event.currentTarget.open) setTextEdit(null) }}>
        <h2 id="text-editor-title">{textEdit?.element.type === 'sticky' ? 'Sticky note' : 'Text box'}</h2>
        <label className="invite-label" htmlFor="board-text">Text</label>
        <textarea id="board-text" autoFocus maxLength={500} rows={6} value={textValue} onChange={e => setTextValue(e.target.value)} />
        {textError && <p role="alert">{textError}</p>}
        <div className="dialog-actions"><button className="quiet-button" onClick={() => textDialog.current?.close()}>Cancel</button><button className="primary-button" disabled={!textValue.trim() || !!textError} onClick={saveText}>Save text</button></div>
      </dialog>
      <dialog ref={helpDialog} className="dialog help-dialog" aria-labelledby="help-title">
        <button className="dialog-close" aria-label="Close help" onClick={() => helpDialog.current?.close()}><X size={20} /></button>
        <span className="eyebrow">MAKE YOURSELF AT HOME</span>
        <h2 id="help-title">A few handy shortcuts</h2>
        <label className="theme-control">Appearance <select aria-label="Appearance" value={theme.mode} onChange={event => theme.setMode(event.target.value as ThemeMode)}><option value="system">System</option><option value="light">Light</option><option value="dark">Dark</option></select></label>
        <div className="shortcuts">{TOOLS.map(item => <div key={item.id}><span>{item.label}</span><kbd>{item.key}</kbd></div>)}<div><span>Undo / redo</span><kbd>Ctrl/⌘ Z / Shift Z</kbd></div><div><span>Temporary pan</span><kbd>Space + drag</kbd></div><div><span>Square, circle, snapped line</span><kbd>Shift + drag</kbd></div></div>
        <p>Scroll to pan. Use Ctrl/⌘ + scroll to zoom, or pinch with two fingers on a touch screen. The eraser removes whole strokes and shapes.</p>
        <div className="local-notice"><strong>A board that lives in the moment</strong><p>Use Share to invite another browser. Strokes and erasing sync live. Refreshing a shared board restores it from a connected participant; once everyone leaves, it is gone. Invites authenticate peers and board traffic is end-to-end encrypted. Anyone with the link can read and edit the board.</p></div>
        <button className="primary-button" onClick={() => helpDialog.current?.close()}>Back to the board <ArrowUpRight size={17} /></button>
      </dialog>

      <dialog ref={clearDialog} className="dialog" aria-labelledby="clear-title">
        <span className="eyebrow">A FRESH START</span>
        <h2 id="clear-title">Clear this board?</h2>
        <p>{session.room ? 'This removes the strokes and shapes you currently see for everyone on the board.' : 'This removes all your strokes and shapes.'} There’s no undo in this version.</p>
        <div className="dialog-actions"><button className="quiet-button" autoFocus onClick={() => clearDialog.current?.close()}><ArrowDownLeft size={16} /> Keep drawing</button><button className="danger-button" onClick={() => { board.clear(); clearDialog.current?.close() }}>Clear board</button></div>
      </dialog>
      <ShareDialog open={sharing} invite={session.invite} onClose={() => setSharing(false)} onLeave={() => { setSharing(false); leaveDialog.current?.showModal() }} />
      <dialog ref={leaveDialog} className="dialog" aria-labelledby="leave-title">
        <span className="eyebrow">UNTIL NEXT TIME</span><h2 id="leave-title">Leave this board?</h2>
        <p>Your copy will be discarded. Other connected participants can keep drawing. If you’re the last one, the board is gone.</p>
        <div className="dialog-actions"><button className="quiet-button" autoFocus onClick={() => leaveDialog.current?.close()}>Stay here</button><button className="danger-button" onClick={session.leave}>Leave board</button></div>
      </dialog>
    </main>
  )
}
