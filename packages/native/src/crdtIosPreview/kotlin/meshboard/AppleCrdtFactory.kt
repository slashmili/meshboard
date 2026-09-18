package meshboard

import meshboard.crdt.AppleCrdtBoard
import meshboard.crdt.CrdtBoard

internal actual fun appleCrdtFactory(): (() -> CrdtBoard)? = { AppleCrdtBoard() }
