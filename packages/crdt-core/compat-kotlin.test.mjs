// Reuse exactly the same scenarios through Kotlin's in-process JNI bridge.
process.env.MESHBOARD_CRDT_JVM = '1'
await import('./compat.test.mjs')
