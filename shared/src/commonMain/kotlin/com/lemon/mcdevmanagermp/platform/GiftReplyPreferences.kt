package com.lemon.mcdevmanagermp.platform

/** 持久化公开回复的发送状态，不保存 Cookie；写入失败时禁止发送。 */
expect class GiftReplyPreferences() {
    fun get(key: String): String?
    fun put(key: String, value: String)
}
