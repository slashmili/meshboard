package meshboard

import java.util.prefs.Preferences

/** Persists only the local appearance choice; board data is never stored here. */
fun desktopAppearanceStore(node: Preferences = Preferences.userRoot().node("dev/meshboard/desktop")): AppearanceStore =
    KeyValueAppearanceStore(read = { node.get(it, null) }, write = { key, value -> node.put(key, value); runCatching { node.flush() } })
