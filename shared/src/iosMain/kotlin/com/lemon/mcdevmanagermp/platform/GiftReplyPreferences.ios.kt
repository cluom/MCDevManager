package com.lemon.mcdevmanagermp.platform

import platform.Foundation.NSUserDefaults

actual class GiftReplyPreferences actual constructor() {
    private val defaults = NSUserDefaults.standardUserDefaults
    actual fun get(key: String): String? = defaults.stringForKey("gift_reply_$key")
    actual fun put(key: String, value: String) {
        defaults.setObject(value, forKey = "gift_reply_$key")
        check(defaults.synchronize()) { "回复状态保存失败，已停止发送" }
    }
}
