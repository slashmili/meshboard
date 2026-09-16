import { useCallback, useEffect, useRef, useState, useSyncExternalStore, type PointerEvent as ReactPointerEvent } from 'react'
import { MAX_POINTS } from '@meshboard/shared-protocol'
import { constrainEnd, hitsElement, INITIAL_VIEW, toWorld, zoomAt, type BoardElement, type Point, type Tool, type View } from './board'
import type { SessionDocument } from './sync/SessionDocument'

type Gesture =
  | { type: 'draw'; pointer: number; element: BoardElement }
  | { type: 'erase'; pointer: number; last: Point }
  | { type: 'pan'; pointer: number; start: Point; view: View }
  | { type: 'pinch'; distance: number; anchor: Point; view: View }

export function useBoard(tool: Tool, color: string, width: number, document: SessionDocument, preview: (element: BoardElement | null) => void) {
  const svgRef = useRef<SVGSVGElement>(null)
  const elements = useSyncExternalStore(document.subscribe, document.getElements)
  const [draft, setDraft] = useState<BoardElement | null>(null)
  const [view, setViewState] = useState(INITIAL_VIEW)
  const viewRef = useRef(view)
  const gesture = useRef<Gesture | null>(null)
  const pointers = useRef(new Map<number, Point>())
  const [spaceDown, setSpaceDown] = useState(false)

  const setView = useCallback((next: View) => {
    viewRef.current = next
    setViewState(next)
  }, [])

  const cancelGesture = useCallback(() => {
    gesture.current = null
    pointers.current.clear()
    setDraft(null)
    preview(null)
  }, [preview])

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.target instanceof HTMLElement && (event.target.closest('input, textarea, select, dialog') || event.target.isContentEditable)) return
      if (event.code === 'Space' && !(event.target instanceof HTMLButtonElement)) {
        event.preventDefault()
        setSpaceDown(true)
      }
      if (event.key === 'Escape') cancelGesture()
    }
    const onKeyUp = (event: KeyboardEvent) => {
      if (event.code === 'Space') setSpaceDown(false)
    }
    const onBlur = () => { setSpaceDown(false); cancelGesture() }
    window.addEventListener('keydown', onKeyDown)
    window.addEventListener('keyup', onKeyUp)
    window.addEventListener('blur', onBlur)
    return () => {
      window.removeEventListener('keydown', onKeyDown)
      window.removeEventListener('keyup', onKeyUp)
      window.removeEventListener('blur', onBlur)
    }
  }, [cancelGesture])

  useEffect(() => {
    const svg = svgRef.current
    if (!svg) return
    const onWheel = (event: WheelEvent) => {
      event.preventDefault()
      if (gesture.current) return
      const current = viewRef.current
      const unit = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? svg.clientHeight : 1
      if (event.ctrlKey || event.metaKey) {
        const rect = svg.getBoundingClientRect()
        setView(zoomAt(current, { x: event.clientX - rect.left, y: event.clientY - rect.top }, current.zoom * Math.exp(-event.deltaY * unit * 0.008)))
      } else {
        setView({ ...current, x: current.x - event.deltaX * unit, y: current.y - event.deltaY * unit })
      }
    }
    svg.addEventListener('wheel', onWheel, { passive: false })
    return () => svg.removeEventListener('wheel', onWheel)
  }, [setView])

  function localPoint(event: { clientX: number; clientY: number }): Point {
    const rect = svgRef.current!.getBoundingClientRect()
    return { x: event.clientX - rect.left, y: event.clientY - rect.top }
  }

  function erase(from: Point, to: Point) {
    document.remove(document.getElements().filter(element => hitsElement(element, from, to, 9 / viewRef.current.zoom)).map(element => element.id))
  }

  function onPointerDown(event: ReactPointerEvent<SVGSVGElement>) {
    if (event.button !== 0 && event.button !== 1) return
    event.preventDefault()
    event.currentTarget.focus({ preventScroll: true })
    event.currentTarget.setPointerCapture(event.pointerId)
    const point = localPoint(event)
    pointers.current.set(event.pointerId, point)
    if (pointers.current.size === 2) {
      const [a, b] = [...pointers.current.values()]
      setDraft(null)
      preview(null)
      gesture.current = { type: 'pinch', distance: Math.max(1, Math.hypot(b.x - a.x, b.y - a.y)), anchor: { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }, view: viewRef.current }
      return
    }
    if (pointers.current.size > 2) return
    if (tool === 'hand' || spaceDown || event.button === 1) {
      gesture.current = { type: 'pan', pointer: event.pointerId, start: point, view: viewRef.current }
      return
    }
    const world = toWorld(point, viewRef.current)
    if (tool === 'eraser') {
      gesture.current = { type: 'erase', pointer: event.pointerId, last: world }
      // Let a second touch begin a pinch before removing anything on a tap.
      if (event.pointerType !== 'touch') erase(world, world)
      return
    }
    const element: BoardElement = { id: `${Date.now().toString(36)}-${crypto.randomUUID()}`, type: tool, color, width, points: tool === 'pen' ? [world] : [world, world] }
    gesture.current = { type: 'draw', pointer: event.pointerId, element }
    setDraft(element)
    preview(element)
  }

  function onPointerMove(event: ReactPointerEvent<SVGSVGElement>) {
    if (!pointers.current.has(event.pointerId)) return
    const point = localPoint(event)
    pointers.current.set(event.pointerId, point)
    const active = gesture.current
    if (!active) return
    if (active.type === 'pinch') {
      const [a, b] = [...pointers.current.values()]
      if (!a || !b) return
      const midpoint = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }
      const next = zoomAt(active.view, active.anchor, active.view.zoom * Math.hypot(b.x - a.x, b.y - a.y) / active.distance)
      setView({ ...next, x: next.x + midpoint.x - active.anchor.x, y: next.y + midpoint.y - active.anchor.y })
      return
    }
    if (event.pointerId !== active.pointer) return
    if (active.type === 'pan') {
      setView({ ...active.view, x: active.view.x + point.x - active.start.x, y: active.view.y + point.y - active.start.y })
      return
    }
    const world = toWorld(point, viewRef.current)
    if (active.type === 'erase') {
      erase(active.last, world)
      active.last = world
      return
    }
    const element = active.element
    if (element.type === 'pen') {
      const samples = event.nativeEvent.getCoalescedEvents?.() ?? []
      const newPoints = (samples.length ? samples : [event]).map(sample => toWorld(localPoint(sample), viewRef.current))
      // Split a very long stroke into bounded objects without interrupting input.
      if (element.points.length + newPoints.length > MAX_POINTS) {
        document.put(element)
        active.element = { ...element, id: `${Date.now().toString(36)}-${crypto.randomUUID()}`, points: [element.points.at(-1)!, ...newPoints.slice(0, MAX_POINTS - 1)] }
      } else active.element = { ...element, points: [...element.points, ...newPoints] }
    } else {
      active.element = { ...element, points: [element.points[0], event.shiftKey ? constrainEnd(element.points[0], world, element.type) : world] }
    }
    setDraft(active.element)
    preview(active.element)
  }

  function onPointerUp(event: ReactPointerEvent<SVGSVGElement>) {
    const active = gesture.current
    if (active && active.type !== 'pinch' && active.pointer === event.pointerId) {
      // Include the final position even when the browser coalesces the last move.
      const finalPoint = toWorld(localPoint(event), viewRef.current)
      const start = active.type === 'draw' ? active.element.points[0] : null
      if (active.type !== 'draw' || active.element.type !== 'pen' || active.element.points.length > 1 || start?.x !== finalPoint.x || start?.y !== finalPoint.y) onPointerMove(event)
      if (active.type === 'draw') {
        const element = active.element
        const [start, end = start] = element.points
        if (element.type === 'pen' || Math.hypot(end.x - start.x, end.y - start.y) > 1 / viewRef.current.zoom) {
          document.put(element)
        }
      }
      gesture.current = null
      setDraft(null)
      preview(null)
    }
    pointers.current.delete(event.pointerId)
    if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId)
    if (active?.type === 'pinch' && pointers.current.size < 2) {
      const remaining = [...pointers.current.entries()][0]
      gesture.current = remaining ? { type: 'pan', pointer: remaining[0], start: remaining[1], view: viewRef.current } : null
    }
  }

  function zoomBy(factor: number) {
    cancelGesture()
    const svg = svgRef.current
    if (svg) setView(zoomAt(viewRef.current, { x: svg.clientWidth / 2, y: svg.clientHeight / 2 }, viewRef.current.zoom * factor))
  }

  function resetView() { cancelGesture(); setView(INITIAL_VIEW) }
  function clear() { cancelGesture(); document.clear() }

  return { svgRef, elements, draft, view, spaceDown, zoomBy, resetView, clear, cancelGesture,
    pointerHandlers: { onPointerDown, onPointerMove, onPointerUp, onPointerCancel: cancelGesture, onLostPointerCapture: () => { if (pointers.current.size === 0) gesture.current = null } } }
}
