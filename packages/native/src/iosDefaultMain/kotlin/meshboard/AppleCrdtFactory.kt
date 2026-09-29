package meshboard

import meshboard.crdt.CrdtBoard

internal actual fun appleCrdtFactory(): (() -> CrdtBoard)? = null
