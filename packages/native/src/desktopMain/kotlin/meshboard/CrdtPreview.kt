package meshboard

import meshboard.crdt.CrdtBoard

/** Explicit local-only checkpoint; normal builds never resolve the optional class. */
fun crdtPreviewFactory(): (() -> CrdtBoard)? {
    if (System.getProperty("meshboard.crdt.preview") != "true") return null
    return { Class.forName("meshboard.crdt.JvmCrdtBoard").getConstructor().newInstance() as CrdtBoard }
}
