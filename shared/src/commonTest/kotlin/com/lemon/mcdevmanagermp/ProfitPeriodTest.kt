package com.lemon.mcdevmanagermp

import com.lemon.mcdevmanagermp.domain.main.createProfitPeriod
import com.lemon.mcdevmanagermp.domain.main.monthDateRange
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfitPeriodTest {

    @Test
    fun august_period_matches_existing_profit_query_range() {
        val (startDate, endDate) = monthDateRange(2026, 8)

        assertEquals(LocalDate(2026, 7, 23), startDate)
        assertEquals(LocalDate(2026, 8, 22), endDate)
    }

    @Test
    fun estimate_uses_days_returned_by_platform() {
        val period = createProfitPeriod(
            year = 2026,
            month = 8,
            dataThroughDate = LocalDate(2026, 8, 9),
            today = LocalDate(2026, 8, 10)
        )

        assertEquals(31, period.totalDays)
        assertEquals(18, period.elapsedDays)
        assertEquals(
            expected = 7652.19 / 18 * 31,
            actual = period.estimateSettlement(7652.19)!!,
            absoluteTolerance = 0.001
        )
    }

    @Test
    fun future_placeholder_rows_are_capped_at_yesterday() {
        val period = createProfitPeriod(
            year = 2026,
            month = 8,
            dataThroughDate = LocalDate(2026, 8, 22),
            today = LocalDate(2026, 8, 10)
        )

        assertEquals(LocalDate(2026, 8, 9), period.dataThroughDate)
        assertEquals(18, period.elapsedDays)
        assertEquals(
            expected = 7652.19 / 18 * 31,
            actual = period.estimateSettlement(7652.19)!!,
            absoluteTolerance = 0.001
        )
    }

    @Test
    fun completed_period_has_no_projection() {
        val period = createProfitPeriod(
            year = 2026,
            month = 8,
            dataThroughDate = LocalDate(2026, 8, 22),
            today = LocalDate(2026, 8, 23)
        )

        assertEquals(31, period.elapsedDays)
        assertNull(period.estimateSettlement(7652.19))
    }

    @Test
    fun period_without_daily_data_has_no_projection() {
        val period = createProfitPeriod(
            year = 2026,
            month = 8,
            dataThroughDate = null,
            today = LocalDate(2026, 8, 10)
        )

        assertEquals(0, period.elapsedDays)
        assertNull(period.estimateSettlement(0.0))
    }
}
