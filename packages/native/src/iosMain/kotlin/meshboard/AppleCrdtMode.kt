package meshboard

import meshboard.crdt.CrdtBoard

internal expect fun appleCrdtFactory(): () -> CrdtBoard
