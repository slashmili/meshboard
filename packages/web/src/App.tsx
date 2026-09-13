import { useEffect, useRef, useState, type CSSProperties } from 'react'
import { ArrowDownLeft, ArrowUpRight, Circle, CircleHelp, Eraser, Hand, Minus, MousePointer2, Pencil, Plus, RotateCcw, Slash, Square, Trash2, X, type LucideIcon } from 'lucide-react'
import { Element } from './Element'
import { MAX_ZOOM, MIN_ZOOM, type Tool } from './board'
import { useBoard } from './useBoard'

const TOOLS: { id: Tool; label: string; key: string; icon: LucideIcon }[] = [
  { id: 'pen', label: 'Pen', key: 'P', icon: Pencil },
  { id: 'eraser', label: 'Eraser', key: 'E', icon: Eraser },
  { id: 'rectangle', label: 'Rectangle', key: 'R', icon: Square },
  { id: 'ellipse', label: 'Ellipse', key: 'O', icon: Circle },
  { id: 'line', label: 'Line', key: 'L', icon: Slash },
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
  pen: 'Click and drag to draw. A single click makes a dot.',
  eraser: 'Click or drag across a stroke to erase the whole object.',
  rectangle: 'Drag to draw a rectangle. Hold Shift for a square.',
  ellipse: 'Drag to draw an ellipse. Hold Shift for a circle.',
  line: 'Drag to draw a line. Hold Shift to snap the angle.',
  hand: 'Drag to move around. Pinch with two fingers to zoom.',
}

export function App() {
  const [tool, setTool] = useState<Tool>('pen')
  const [color, setColor] = useState(COLORS[0].value)
  const [width, setWidth] = useState(3)
  const board = useBoard(tool, color, width)
  const helpDialog = useRef<HTMLDialogElement>(null)
  const clearDialog = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.ctrlKey || event.metaKey || event.altKey) return
      if (event.target instanceof HTMLElement && (event.target.closest('input, textarea, select, dialog') || event.target.isContentEditable)) return
      const next = TOOLS.find(item => item.key.toLowerCase() === event.key.toLowerCase())
      if (next) {
        board.cancelGesture()
        setTool(next.id)
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [board.cancelGesture])

  const { x, y, zoom } = board.view
  const currentTool = TOOLS.find(item => item.id === tool)!
  const drawing = tool !== 'hand' && tool !== 'eraser'
  const empty = board.elements.length === 0 && !board.draft

  return (
    <main className="app">
      <header className="header">
        <div className="brand" aria-label="Meshboard">
          <svg className="brand-mark" viewBox="0 0 36 36" aria-hidden="true"><rect width="36" height="36" rx="11" /><path d="M9 25V12l9 9 9-9v13" /></svg>
          <span>meshboard<span className="brand-dot">.</span></span>
        </div>
        <span className="header-divider" />
        <div className="board-title">Untitled board <span>A space for ideas</span></div>
        <div className="header-actions">
          <span className="local-badge"><span className="status-dot" /> Local only</span>
          <button className="quiet-button clear-button" aria-label="Clear board" disabled={board.elements.length === 0} onClick={() => clearDialog.current?.showModal()}><Trash2 size={16} /><span>Clear board</span></button>
        </div>
      </header>

      <section className="workspace" aria-label="Whiteboard workspace">
        <svg ref={board.svgRef} className={`canvas ${board.spaceDown || tool === 'hand' ? 'canvas-pan' : tool === 'eraser' ? 'canvas-eraser' : ''}`} aria-label="Drawing canvas" aria-describedby="canvas-instructions" tabIndex={0} {...board.pointerHandlers} onContextMenu={event => event.preventDefault()}>
          <defs><pattern id="grid" width={24 * zoom} height={24 * zoom} patternUnits="userSpaceOnUse" x={x} y={y}><circle cx={1} cy={1} r={0.8} fill="#cbd3ca" /></pattern></defs>
          <rect width="100%" height="100%" fill="url(#grid)" pointerEvents="none" />
          <g transform={`translate(${x} ${y}) scale(${zoom})`} pointerEvents="none" data-testid="drawing-elements">
            {board.elements.map(element => <Element key={element.id} element={element} />)}
            {board.draft && <Element element={board.draft} />}
          </g>
        </svg>

        <div className="tool-panel" role="toolbar" aria-label="Drawing tools">
          {TOOLS.map(({ id, label, key, icon: Icon }, i) => <div key={id} className={i === 5 ? 'tool-item tool-item-separated' : 'tool-item'}>
            <button className={`tool-button ${tool === id ? 'selected' : ''}`} aria-label={`${label} (${key})`} aria-pressed={tool === id} title={`${label} (${key})`} onClick={() => { board.cancelGesture(); setTool(id) }}><Icon size={21} strokeWidth={1.7} /><span className="tool-key">{key}</span></button>
          </div>)}
        </div>

        <aside className="style-panel" aria-label="Stroke style">
          <div className="panel-caption">STROKE</div>
          <div className="color-options" role="radiogroup" aria-label="Stroke color">
            {COLORS.map(item => <button key={item.name} className={`color-button ${color === item.value ? 'active' : ''}`} role="radio" aria-checked={color === item.value} aria-label={item.name} title={item.name} disabled={!drawing} onClick={() => setColor(item.value)} style={{ '--swatch': item.value } as CSSProperties}><span /></button>)}
          </div>
          <div className="width-options" role="radiogroup" aria-label="Stroke width">
            {[{ value: 2, name: 'Fine' }, { value: 3, name: 'Medium' }, { value: 6, name: 'Bold' }].map(item => <button key={item.value} className={width === item.value ? 'active' : ''} role="radio" aria-checked={width === item.value} aria-label={`${item.name} stroke`} title={`${item.name} stroke`} disabled={!drawing} onClick={() => setWidth(item.value)}><span style={{ height: item.value }} /></button>)}
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

        <div className="session-note"><span className="note-dot" /><span>Just this tab, just for now.<br /><strong>Refreshing clears your board.</strong></span></div>

        <div className="canvas-footer">
          <div className="zoom-panel" role="group" aria-label="Canvas view">
            <button aria-label="Zoom out" title="Zoom out" disabled={zoom <= MIN_ZOOM} onClick={() => board.zoomBy(1 / 1.2)}><Minus size={17} /></button>
            <span className="zoom-value" aria-live="polite">{Math.round(zoom * 100)}%</span>
            <button aria-label="Zoom in" title="Zoom in" disabled={zoom >= MAX_ZOOM} onClick={() => board.zoomBy(1.2)}><Plus size={17} /></button>
            <span className="zoom-divider" />
            <button aria-label="Reset view" title="Reset view to 100% and original position" onClick={board.resetView}><RotateCcw size={15} /></button>
          </div>
          <span className="navigation-hint"><MousePointer2 size={13} /> Scroll to pan <span>·</span> Ctrl/⌘ + scroll to zoom</span>
          <button className="help-button" aria-label="Help and keyboard shortcuts" title="Help and keyboard shortcuts" onClick={() => helpDialog.current?.showModal()}><CircleHelp size={20} /></button>
        </div>
      </section>

      <footer className="status-bar">
        <div id="canvas-instructions"><span className="active-tool">{board.spaceDown ? 'Pan' : currentTool.label}</span><span className="tool-hint">{board.spaceDown ? 'Drag to move around the board.' : HINTS[tool]}</span></div>
        <span className="checkpoint-label">FIRST SKETCH <span>/</span> 01</span>
      </footer>

      <dialog ref={helpDialog} className="dialog help-dialog" aria-labelledby="help-title">
        <button className="dialog-close" aria-label="Close help" onClick={() => helpDialog.current?.close()}><X size={20} /></button>
        <span className="eyebrow">MAKE YOURSELF AT HOME</span>
        <h2 id="help-title">A few handy shortcuts</h2>
        <div className="shortcuts">{TOOLS.map(item => <div key={item.id}><span>{item.label}</span><kbd>{item.key}</kbd></div>)}<div><span>Temporary pan</span><kbd>Space + drag</kbd></div><div><span>Square, circle, snapped line</span><kbd>Shift + drag</kbd></div></div>
        <p>Scroll to pan. Use Ctrl/⌘ + scroll to zoom, or pinch with two fingers on a touch screen. The eraser removes whole strokes and shapes.</p>
        <div className="local-notice"><strong>Your first local sketch</strong><p>This checkpoint runs in this tab only. Drawing stays in memory and disappears on refresh or when you close the tab. Sharing, sync, export, and encryption are coming in later checkpoints.</p></div>
        <button className="primary-button" onClick={() => helpDialog.current?.close()}>Back to the board <ArrowUpRight size={17} /></button>
      </dialog>

      <dialog ref={clearDialog} className="dialog" aria-labelledby="clear-title">
        <span className="eyebrow">A FRESH START</span>
        <h2 id="clear-title">Clear this board?</h2>
        <p>This removes all your strokes and shapes. There’s no undo in this first version.</p>
        <div className="dialog-actions"><button className="quiet-button" autoFocus onClick={() => clearDialog.current?.close()}><ArrowDownLeft size={16} /> Keep drawing</button><button className="danger-button" onClick={() => { board.clear(); clearDialog.current?.close() }}>Clear board</button></div>
      </dialog>
    </main>
  )
}
