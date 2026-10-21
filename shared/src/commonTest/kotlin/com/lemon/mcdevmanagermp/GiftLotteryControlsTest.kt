package com.lemon.mcdevmanagermp

import com.lemon.mcdevmanagermp.ui.components.ModSelectOption
import com.lemon.mcdevmanagermp.ui.components.dateFromPickerUtcMillis
import com.lemon.mcdevmanagermp.ui.components.datePickerUtcMillis
import com.lemon.mcdevmanagermp.ui.pages.community.lottery.filterLotteryMods
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class GiftLotteryControlsTest {
    @Test fun calendarUsesUtcMidnightRatherThanPreviousDayInChina() {
        val date = LocalDate(2026, 10, 21)
        val millis = datePickerUtcMillis(date)
        assertEquals(Instant.parse("2026-10-21T00:00:00Z").toEpochMilliseconds(), millis)
        assertEquals(date, dateFromPickerUtcMillis(millis))
    }

    @Test fun calendarRoundTripsLeapDayAndYearBoundaries() {
        listOf("2024-02-29", "2026-01-01", "2026-12-31").forEach { text ->
            val date = LocalDate.parse(text)
            assertEquals(date, dateFromPickerUtcMillis(datePickerUtcMillis(date)))
        }
    }

    @Test fun modSearchMatchesNameOrExactIdTextWithoutChangingSelectionIds() {
        val options = listOf(ModSelectOption("4688538944207380371", "免费凡人问道"),
            ModSelectOption("4690141251535232006", "灵气复苏"), ModSelectOption("2", "Test Mod"))
        assertEquals(listOf(options[0]), filterLotteryMods(options, " 凡人 "))
        assertEquals(listOf(options[1]), filterLotteryMods(options, "4690141251535232006"))
        assertEquals(listOf(options[2]), filterLotteryMods(options, "test"))
        assertEquals(options, filterLotteryMods(options, ""))
        assertEquals(emptyList(), filterLotteryMods(options, "不存在"))
    }
}
