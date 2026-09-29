package meshboard

import meshboard.crdt.CrdtBoard

internal expect fun appleCrdtFactory(): (() -> CrdtBoard)?
fun appleCrdtPreviewEnabled(): Boolean = appleCrdtFactory() != null
