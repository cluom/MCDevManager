package com.lemon.mcdevmanagermp.platform

import java.util.prefs.Preferences

actual class GiftReplyPreferences actual constructor() {
    private val prefs by lazy { Preferences.userRoot().node("com/lemon/mcdevmanager/gift-replies") }
    actual fun get(key: String): String? { prefs.sync(); return prefs.get(key, null) }
    actual fun put(key: String, value: String) { prefs.put(key, value); prefs.flush() }
}
