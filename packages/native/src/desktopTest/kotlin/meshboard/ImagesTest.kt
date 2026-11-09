package meshboard

import java.io.File
import kotlinx.serialization.json.*
import meshboard.crdt.JvmCrdtBoard
import kotlin.test.*

class ImagesTest {
    private fun fixtures() = Json.parseToJsonElement(File(System.getProperty("meshboard.fixtures"),"image-elements.json").readText()).jsonObject
    @Test fun sharedImagesDecodeAndRoundTripThroughJniAndUndo() {
        JvmCrdtBoard().use { a -> JvmCrdtBoard().use { b ->
            for (value in fixtures().getValue("valid").jsonArray) {
                val element = Wire.element(value)
                val bitmap = decodeBoardImage(imageBytes(element.image!!))
                assertEquals(40,bitmap.width); assertEquals(30,bitmap.height)
                a.put(element)
                assertTrue(hits(element,Point(420.0,215.0),Point(420.0,215.0),0.0))
            }
            b.apply(a.update()); assertEquals(a.elements(),b.elements())
            a.move(a.elements().map { it.id },Point(20.0,10.0))
            val moved = a.elements(); a.undo(); a.redo(); assertEquals(moved,a.elements())
            for (value in fixtures().getValue("invalid").jsonArray) assertFails { Wire.element(value) }
        } }
    }
    @Test fun pickerReadsAreBoundedEvenWhenFileMetadataIsUntrusted() = kotlinx.coroutines.runBlocking {
        val source = File(System.getProperty("meshboard.fixtures"),"image.png")
        assertContentEquals(source.readBytes(),readImageFile(io.github.vinceglb.filekit.core.PlatformFile(source)))
        val large = File.createTempFile("meshboard-image", ".png")
        try {
            large.writeBytes(ByteArray(MAX_IMAGE_BYTES+1))
            assertFails { readImageFile(io.github.vinceglb.filekit.core.PlatformFile(large)) }
        } finally { large.delete() }
        Unit
    }
    @Test fun invalidImagesAndOversizedFilesFailBeforeImport() {
        assertFails { decodeBoardImage(ByteArray(10)) }
        assertFails { imageInfo(ByteArray(MAX_IMAGE_BYTES+1)) }
        val bytes = File(System.getProperty("meshboard.fixtures"),"image.png").readBytes()
        val element = imageElement("imported",bytes,Point(100.0,100.0))
        assertEquals(listOf(Point(80.0,85.0),Point(120.0,115.0)),element.points)
    }
}
