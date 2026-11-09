package meshboard

import kotlin.math.*
data class Attachment(val id: String, val x: Double, val y: Double)
private val connectorTargets = setOf("rectangle","ellipse","text","sticky","image")
private fun targetBox(e: BoardElement): SelectionBox {
    val a=e.points.first(); val b=e.points.last()
    return SelectionBox(min(a.x,b.x),min(a.y,b.y),abs(a.x-b.x),abs(a.y-b.y))
}
fun attach(elements: List<BoardElement>, at: Point, radius: Double): Attachment? {
    val target=elements.lastOrNull { e -> e.type in connectorTargets && targetBox(e).let { b -> at.x in b.x-radius..b.x+b.width+radius && at.y in b.y-radius..b.y+b.height+radius } } ?: return null
    val b=targetBox(target)
    var x=if(b.width==0.0) .5 else ((at.x-b.x)/b.width).coerceIn(0.0,1.0)
    var y=if(b.height==0.0) .5 else ((at.y-b.y)/b.height).coerceIn(0.0,1.0)
    if(target.type=="ellipse") { val angle=atan2(y-.5,x-.5); x=.5+.5*cos(angle); y=.5+.5*sin(angle) }
    else { val distances=listOf(x,1-x,y,1-y); when(distances.indexOf(distances.min())) { 0->x=0.0; 1->x=1.0; 2->y=0.0; 3->y=1.0 } }
    return Attachment(target.id,x,y)
}
fun resolveConnector(e: BoardElement, elements: List<BoardElement>): BoardElement? {
    if(e.type!="arrow") return e
    val points=e.points.toMutableList()
    listOf(e.startAttachment,e.endAttachment).forEachIndexed { index,a -> if(a!=null) {
        val target=elements.find { it.id==a.id && it.type in connectorTargets } ?: return null
        val b=targetBox(target); points[index]=Point(b.x+b.width*a.x,b.y+b.height*a.y)
    } }
    return e.copy(points=points)
}
fun resolveConnectors(elements: List<BoardElement>) = elements.mapNotNull { resolveConnector(it,elements) }
data class ArrowGesture(val element: BoardElement,val index: Int,val existing: Boolean)
fun beginArrow(elements: List<BoardElement>, at: Point, radius: Double, fresh: BoardElement): ArrowGesture {
    for(e in resolveConnectors(elements).asReversed()) if(e.type=="arrow") for(index in 0..1) {
        if(hypot(e.points[index].x-at.x,e.points[index].y-at.y)<=radius) return ArrowGesture(e,index,true)
    }
    return ArrowGesture(fresh.copy(startAttachment=attach(elements,at,radius)),1,false)
}
fun dragArrow(e: BoardElement,index: Int,at: Point,elements: List<BoardElement>,radius: Double): BoardElement {
    val points=e.points.toMutableList(); points[index]=at
    return if(index==0) e.copy(points=points,startAttachment=attach(elements,at,radius)) else e.copy(points=points,endAttachment=attach(elements,at,radius))
}
