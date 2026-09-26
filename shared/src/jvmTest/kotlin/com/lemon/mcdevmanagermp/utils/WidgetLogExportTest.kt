package com.lemon.mcdevmanagermp.utils

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WidgetLogExportTest {
    @Test
    fun normalLogReaderAppendsNativeDiagnosticsForExistingExportUi() = runBlocking {
        val file = Files.createTempFile("widget-log-export-", ".log")
        try {
            Files.writeString(file, "[INFO] main app\n")
            Logger.installSupplementalSource({ "[DEBUG] WIDGET_DIAG event=reserveSkipped" }, {})
            val lines = Logger.readLogLines(file.toString().toPath()).toList().flatten()
            assertEquals("[INFO] main app", lines.first())
            assertTrue(lines.last().contains("WIDGET_DIAG event=reserveSkipped"))
        } finally {
            Logger.installSupplementalSource({ "" }, {})
            Files.deleteIfExists(file)
        }
    }
}
