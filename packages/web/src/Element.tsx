import { textLines } from './textElements'
import { bounds, outline, type BoardElement } from './board'

export function Element({ element }: { element: BoardElement }) {
  const style = { fill: 'none', stroke: element.color, strokeWidth: element.width, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const }
  const [start, end = start] = element.points
  if (element.text !== undefined) {
    const box = bounds(element)
    return <g data-element-type={element.type}>
      {element.type === 'sticky' && <rect {...box} rx={4} fill="#fff0a8" stroke="#c49226" strokeWidth={1} />}
      <text fill={element.color} fontFamily="monospace" fontSize={18} xmlSpace="preserve">
        {textLines(element.text).map((line, i) => <tspan key={i} x={box.x + 12} y={box.y + 30 + i * 24}>{line}</tspan>)}
      </text>
    </g>
  }
  if (element.type === 'rectangle') return <rect {...bounds(element)} {...style} />
  if (element.type === 'ellipse') {
    const { x, y, width, height } = bounds(element)
    return <ellipse cx={x + width / 2} cy={y + height / 2} rx={width / 2} ry={height / 2} {...style} />
  }
  if (element.type === 'arrow') return <polyline data-element-type="arrow" points={outline(element).map(({ x, y }) => `${x},${y}`).join(' ')} {...style} />
  if (element.type === 'line') return <line x1={start.x} y1={start.y} x2={end.x} y2={end.y} {...style} />
  if (element.points.length === 1) return <circle cx={start.x} cy={start.y} r={element.width / 2} fill={element.color} />
  return <polyline points={element.points.map(({ x, y }) => `${x},${y}`).join(' ')} {...style} />
}
