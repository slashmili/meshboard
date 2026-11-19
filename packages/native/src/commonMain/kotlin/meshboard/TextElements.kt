package meshboard

/** Same bounded line wrapping as the SVG renderer; never split a surrogate pair. */
fun textLines(text: String): List<String> = buildList {
    text.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { paragraph ->
        var line = StringBuilder(); var units = 0; var i = 0
        while (i < paragraph.length) {
            val count = if (paragraph[i].isHighSurrogate() && i + 1 < paragraph.length && paragraph[i + 1].isLowSurrogate()) 2 else 1
            val char = paragraph.substring(i, i + count)
            val size = if (paragraph[i].code <= 255) 1 else 2
            if (units + size > 24) { add(line.toString()); line = StringBuilder(); units = 0 }
            line.append(char); units += size; i += count
        }
        add(line.toString())
    }
}

fun textElement(id: String, type: String, at: Point, color: String, text: String): BoardElement {
    require(type == "text" || type == "sticky")
    val height = maxOf(72, textLines(text).size * 24 + 24)
    return BoardElement(id, type, color, 1.0, listOf(at, Point(at.x + 288, at.y + height)), text)
}

fun validText(text: String): Boolean {
    if (text.length !in 1..500) return false
    var i = 0
    while (i < text.length) {
        val c = text[i++]
        if (c.isHighSurrogate()) {
            if (i == text.length || !text[i++].isLowSurrogate()) return false
        } else if (c.isLowSurrogate()) return false
    }
    return true
}
