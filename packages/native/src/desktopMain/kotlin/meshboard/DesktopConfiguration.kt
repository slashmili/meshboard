package meshboard

/** Environment overrides remain available to people hosting their own server. */
fun desktopAppOrigin(
    environment: String? = System.getenv("MESHBOARD_APP_ORIGIN"),
    packaged: String? = System.getProperty("meshboard.app.origin"),
): String = environment?.takeIf { it.isNotBlank() }
    ?: packaged?.takeIf { it.isNotBlank() }
    ?: "http://127.0.0.1:5173"
