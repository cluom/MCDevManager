package com.lemon.mcdevmanagermp

import androidx.compose.material3.ExperimentalMaterial3Api
import com.lemon.mcdevmanagermp.ui.components.ChineseDatePickerFormatter
import com.lemon.mcdevmanagermp.ui.components.dateFromPickerUtcMillis
import com.lemon.mcdevmanagermp.ui.components.datePickerUtcMillis
import java.util.Locale
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalMaterial3Api::class)
class AppDatePickerLocalizationTest {
    @Test fun allMonthsUseChineseLabelsEvenWithEnglishLocale() {
        for (month in 1..12) {
            val millis = datePickerUtcMillis(LocalDate(2026, month, 1))
            assertEquals("2026年${month}月", ChineseDatePickerFormatter.formatMonthYear(millis, Locale.US))
        }
    }

    @Test fun selectedDateAndAccessibleDescriptionUseChinese() {
        val millis = datePickerUtcMillis(LocalDate(2026, 10, 21))
        assertEquals("2026年10月21日", ChineseDatePickerFormatter.formatDate(millis, Locale.US, false))
        assertEquals("2026年10月21日 星期三", ChineseDatePickerFormatter.formatDate(millis, Locale.US, true))
    }

    @Test fun leapDayAndYearBoundariesRemainTheSameDate() {
        listOf("2024-02-29", "2026-01-01", "2026-12-31").forEach { text ->
            val date = LocalDate.parse(text)
            val millis = datePickerUtcMillis(date)
            assertEquals("${date.year}年${date.month.number}月${date.day}日",
                ChineseDatePickerFormatter.formatDate(millis, Locale.US, false))
            assertEquals(date, dateFromPickerUtcMillis(millis))
        }
    }

    @Test fun emptySelectionStaysEmpty() {
        assertNull(ChineseDatePickerFormatter.formatMonthYear(null, Locale.US))
        assertNull(ChineseDatePickerFormatter.formatDate(null, Locale.US, false))
    }
}
