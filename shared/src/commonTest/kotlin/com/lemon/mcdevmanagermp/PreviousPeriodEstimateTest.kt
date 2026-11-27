package com.lemon.mcdevmanagermp

import com.lemon.mcdevmanagermp.data.vo.netease.income.IncomeVO
import com.lemon.mcdevmanagermp.domain.main.*
import com.lemon.mcdevmanagermp.utils.*
import kotlin.test.*

class PreviousPeriodEstimateTest {
    private val raw = calculateProfit(mapOf("a" to 20000.0, "b" to 5000.0))
    private val month = ProfitMonth(2026, 11)

    @Test fun previousPeriodChangesAmountsNotJustLabel() {
        val result = estimateWithPreviousPeriod(month, raw, mapOf("2026-10" to 0.4))
        assertEquals(0.4, result.ecosystemFeeRatio)
        assertEquals("2026-10", result.feeSourceMonth)
        assertEquals(calculateProfit(raw.moduleDiamonds, ecosystemFeeRatio = 0.4).totalProfit, result.totalProfit)
        assertEquals(result.developerProfit / 100, result.toModuleIncomeDetails().sumOf { it.developerShare }, 1e-9)
        assertEquals(0.3, raw.ecosystemFeeRatio)
        assertTrue(result.calculationLabel().contains("参考 2026-10"))
    }
    @Test fun missingInvalidOrFutureRatesUseDefault() {
        for (rates in listOf(emptyMap(), mapOf("2026-09" to 0.4, "2026-11" to 0.5),
            mapOf("2026-10" to Double.NaN), mapOf("2026-10" to -0.1), mapOf("2026-10" to 1.1))) {
            val result = estimateWithPreviousPeriod(month, raw, rates)
            assertEquals(0.3, result.ecosystemFeeRatio)
            assertNull(result.feeSourceMonth)
        }
    }
    @Test fun yearBoundaryAndZeroRateAreValid() {
        val result = estimateWithPreviousPeriod(ProfitMonth(2027, 1), raw, mapOf("2026-12" to 0.0))
        assertEquals(0.0, result.ecosystemFeeRatio)
        assertEquals("2026-12", result.feeSourceMonth)
    }
    @Test fun currentSettlementOverridesPreviousWithoutChangingReverseFormula() {
        val estimated = estimateWithPreviousPeriod(month, raw, mapOf("2026-10" to 0.4))
        val bill = IncomeVO(dataMonth="202611", income="151.25", playPlanIncome="20.00", tax="10.00", totalDiamond=25000)
        val result = applyUpstreamSettlement(month, estimated, listOf(bill))
        assertEquals(0.3, result.ecosystemFeeRatio!!, 1e-9)
        assertEquals("2026-11", result.feeSourceMonth)
        assertEquals(151.25, result.totalProfit)
        assertEquals(raw.developerProfit, result.developerProfit, 1e-9)
        assertEquals(result, estimateWithPreviousPeriod(month, result, mapOf("2026-10" to 0.4)))
        val summary = estimated.computeActualModuleSummary(151.25, 20.0, result.netIncome)!!
        assertEquals(estimated.toModuleIncomeDetails().sumOf { it.totalIncome }, summary.items.sumOf { it.estimatedTotalIncome }, 1e-9)
    }
    @Test fun incompleteFlowFlagAndEmeraldAmountsRemainUnchanged() {
        val input = raw.copy(isFlowComplete=false, emeraldModDiamonds=mapOf("e" to 900.0))
        val result = estimateWithPreviousPeriod(month, input, mapOf("2026-10" to 0.4))
        assertFalse(result.isFlowComplete)
        assertEquals(input.emeraldModDiamonds, result.emeraldModDiamonds)
        assertNull(result.reverseEcoFeeRate(100.0))
    }
}
