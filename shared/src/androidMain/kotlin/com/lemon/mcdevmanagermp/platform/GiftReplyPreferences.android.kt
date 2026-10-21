package com.lemon.mcdevmanagermp.platform

import android.content.Context

actual class GiftReplyPreferences actual constructor() {
    private val prefs by lazy {
        val context = AndroidLogContext.getContext() ?: error("应用尚未初始化")
        context.getSharedPreferences("mc_dev_gift_replies", Context.MODE_PRIVATE)
    }
    actual fun get(key: String): String? = prefs.getString(key, null)
    actual fun put(key: String, value: String) {
        check(prefs.edit().putString(key, value).commit()) { "回复状态保存失败，已停止发送" }
    }
}
