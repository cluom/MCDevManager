package com.lemon.mcdevmanagermp.utils

/** A bounded, sanitized native diagnostic snapshot appended to the existing log export. */
internal class SupplementalLogSource(
    private val read: () -> String,
    private val clear: () -> Unit,
) {
    fun lines(): List<String> = try {
        val snapshot = read()
        if (snapshot.isBlank()) emptyList()
        else listOf("[INFO] --- 小组件诊断快照（UTC 时间，读取时附加） ---") +
            snapshot.takeLast(256 * 1024).lineSequence().filter { it.isNotBlank() }.take(514).toList()
    } catch (_: Exception) {
        // Native exception descriptions can contain private paths or credentials.
        listOf("[WARN] WIDGET_DIAG export_failed")
    }

    fun clearSafely() {
        try { clear() } catch (_: Exception) { /* Main log cleanup must still work. */ }
    }
}
