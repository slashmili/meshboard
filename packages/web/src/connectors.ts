import type { BoardElement, Point } from './board'
export type Attachment = NonNullable<BoardElement['startAttachment']>
const targets = new Set(['rectangle','ellipse','text','sticky','image'])
function box(e: BoardElement) { const [a,b] = e.points; return {x:Math.min(a.x,b.x),y:Math.min(a.y,b.y),w:Math.abs(b.x-a.x),h:Math.abs(b.y-a.y)} }
export function attach(elements: BoardElement[], at: Point, radius: number): Attachment | undefined {
  const target = [...elements].reverse().find(e => { if (!targets.has(e.type)) return false; const b=box(e); return at.x>=b.x-radius && at.x<=b.x+b.w+radius && at.y>=b.y-radius && at.y<=b.y+b.h+radius })
  if (!target) return
  const b=box(target); let x=b.w ? Math.max(0,Math.min(1,(at.x-b.x)/b.w)) : .5, y=b.h ? Math.max(0,Math.min(1,(at.y-b.y)/b.h)) : .5
  if (target.type==='ellipse') { const angle=Math.atan2(y-.5,x-.5); x=.5+.5*Math.cos(angle); y=.5+.5*Math.sin(angle) }
  else { const distances=[x,1-x,y,1-y]; const edge=distances.indexOf(Math.min(...distances)); if(edge===0)x=0; if(edge===1)x=1; if(edge===2)y=0; if(edge===3)y=1 }
  return {id:target.id,x,y}
}
export function resolveConnector(e: BoardElement, elements: BoardElement[]): BoardElement | null {
  if (e.type!=='arrow') return e
  const points=[...e.points]
  for (const [index,a] of [e.startAttachment,e.endAttachment].entries()) {
    if (!a) continue
    const target=elements.find(t=>t.id===a.id && targets.has(t.type))
    if (!target) return null
    const b=box(target); points[index]={x:b.x+b.w*a.x,y:b.y+b.h*a.y}
  }
  return {...e,points}
}
export const resolveConnectors = (elements: BoardElement[]) => elements.map(e=>resolveConnector(e,elements)).filter((e): e is BoardElement=>e!==null)
export function beginArrow(elements: BoardElement[], at: Point, radius: number, fresh: BoardElement) {
  for (const e of resolveConnectors(elements).reverse()) if(e.type==='arrow') for(const index of [0,1]) {
    if(Math.hypot(e.points[index].x-at.x,e.points[index].y-at.y)<=radius) return {element:e,index,existing:true}
  }
  const anchor=attach(elements,at,radius)
  return {element:anchor ? {...fresh,startAttachment:anchor} : fresh,index:1,existing:false}
}
export function dragArrow(e: BoardElement, index: number, at: Point, elements: BoardElement[], radius: number): BoardElement {
  const points=[...e.points]; points[index]=at
  const next={...e,points}
  const key=index===0?'startAttachment':'endAttachment'
  const anchor=attach(elements,at,radius)
  if(anchor) next[key]=anchor; else delete next[key]
  return next
}
