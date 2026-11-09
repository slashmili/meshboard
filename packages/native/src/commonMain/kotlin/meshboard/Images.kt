package meshboard

import kotlin.io.encoding.Base64

const val MAX_IMAGE_BYTES = 512 * 1024
const val IMAGE_HELP = "Choose a PNG or JPEG up to 512 KiB and 1024 × 1024 pixels."
data class ImageInfo(val mime: String, val width: Int, val height: Int)
fun imageInfo(bytes: ByteArray): ImageInfo {
    require(bytes.size in 1..MAX_IMAGE_BYTES) { IMAGE_HELP }
    fun byte(i: Int) = bytes.getOrNull(i)?.toInt()?.and(255) ?: -1
    fun u16(i: Int) = byte(i) * 256 + byte(i + 1)
    fun u32(i: Int) = u16(i).toLong() * 65536 + u16(i + 2)
    var mime = ""; var width = 0L; var height = 0L
    if (bytes.size >= 33 && listOf(137,80,78,71,13,10,26,10).withIndex().all { (i,b) -> byte(i) == b } && u32(8) == 13L && bytes.copyOfRange(12,16).decodeToString() == "IHDR") {
        mime = "image/png"; width = u32(16); height = u32(20)
        var ended = false; var i = 8
        while (i+12 <= bytes.size) {
            val length = u32(i); val type = bytes.copyOfRange(i+4,i+8).decodeToString()
            require(i+12+length <= bytes.size && type != "acTL" && (i == 8 || type != "IHDR")) { IMAGE_HELP }
            i += 12+length.toInt()
            if (type == "IEND") { ended = length == 0L && i == bytes.size; break }
        }
        require(ended) { IMAGE_HELP }
    } else if (byte(0) == 255 && byte(1) == 216) {
        mime = "image/jpeg"
        var i = 2
        while (i + 3 < bytes.size) {
            if (byte(i++) != 255) break
            while (byte(i) == 255) i++
            val marker = byte(i++); if (marker == 218 || marker == 217) break
            val length = u16(i)
            if (length < 2 || i + length > bytes.size) break
            if (marker in listOf(192,194) && length >= 8) { height = u16(i+3).toLong(); width = u16(i+5).toLong(); break }
            i += length
        }
    }
    require(width in 1L..1024L && height in 1L..1024L) { IMAGE_HELP }
    return ImageInfo(mime,width.toInt(),height.toInt())
}
fun imageBytes(data: String): ByteArray {
    require(data.length <= 699100 && Regex("^data:image/(png|jpeg);base64,[A-Za-z0-9+/]+={0,2}$").matches(data)) { IMAGE_HELP }
    val encoded = data.substringAfter(','); val bytes = Base64.decode(encoded)
    require(Base64.encode(bytes) == encoded && data.substringBefore(',') == "data:${imageInfo(bytes).mime};base64") { IMAGE_HELP }
    return bytes
}
fun imageElement(id: String, bytes: ByteArray, center: Point): BoardElement {
    val info = imageInfo(bytes)
    val scale = minOf(1.0, 400.0 / maxOf(info.width,info.height))
    val w = info.width * scale; val h = info.height * scale
    return BoardElement(id,"image","#293b36",1.0,listOf(Point(center.x-w/2,center.y-h/2),Point(center.x+w/2,center.y+h/2)), image = "data:${info.mime};base64,${Base64.encode(bytes)}")
}
