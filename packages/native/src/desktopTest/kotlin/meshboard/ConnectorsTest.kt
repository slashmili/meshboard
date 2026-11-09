package meshboard
import java.io.File
import kotlinx.serialization.json.*
import kotlin.test.*
import meshboard.crdt.JvmCrdtBoard
class ConnectorsTest {
 private val box=BoardElement("box","rectangle","#123456",3.0,listOf(Point(100.0,100.0),Point(200.0,200.0)))
 @Test fun sharedAttachmentsFollowTargetsAndSurviveJniUndo() {
  val f=Json.parseToJsonElement(File(System.getProperty("meshboard.fixtures"),"connectors.json").readText()).jsonObject
  f.getValue("invalid").jsonArray.forEach { assertFails { Wire.element(it) } }
  val arrow=Wire.element(f.getValue("valid").jsonArray[0])
  JvmCrdtBoard().use { a -> JvmCrdtBoard().use { b ->
   a.put(box);a.put(arrow);a.move(listOf("box"),Point(50.0,20.0));b.apply(a.update())
   assertEquals(Point(250.0,170.0),resolveConnectors(b.elements()).first { it.id=="arrow" }.points.first())
   assertEquals(arrow,b.elements().first { it.id=="arrow" })
   a.remove("box");assertTrue(resolveConnectors(a.elements()).isEmpty())
   a.undo();assertEquals(2,resolveConnectors(a.elements()).size)
  } }
  val gesture=beginArrow(listOf(box,arrow),Point(200.0,150.0),10.0,arrow.copy(id="unused"))
  assertTrue(gesture.existing);assertEquals(0,gesture.index)
  assertNull(dragArrow(gesture.element,0,Point(500.0,500.0),listOf(box),10.0).startAttachment)
  assertEquals(Attachment("box",0.0,0.5),dragArrow(gesture.element,0,Point(100.0,150.0),listOf(box),10.0).startAttachment)
  val moved=translated(listOf(box,arrow),listOf("box","arrow"),Point(50.0,20.0))
  assertEquals(listOf(Point(250.0,170.0),Point(450.0,170.0)),resolveConnectors(moved).first { it.id=="arrow" }.points)
 }
}
