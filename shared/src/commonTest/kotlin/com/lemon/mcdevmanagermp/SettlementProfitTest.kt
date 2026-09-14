package com.lemon.mcdevmanagermp

import com.lemon.mcdevmanagermp.data.vo.netease.income.IncomeDetailVO
import com.lemon.mcdevmanagermp.data.vo.netease.income.IncomeVO
import com.lemon.mcdevmanagermp.domain.main.actualEcosystemFeeRatio
import com.lemon.mcdevmanagermp.domain.main.calculateMonthlyProfit
import com.lemon.mcdevmanagermp.domain.profitsharing.ModuleOwnership
import com.lemon.mcdevmanagermp.domain.profitsharing.ProfitPerson
import com.lemon.mcdevmanagermp.domain.profitsharing.calculateProfitAllocation
import com.lemon.mcdevmanagermp.utils.calculateProfit
import com.lemon.mcdevmanagermp.utils.calculationLabel
import com.lemon.mcdevmanagermp.utils.getDeveloperProfit
import com.lemon.mcdevmanagermp.utils.toModuleIncomeDetails
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettlementProfitTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val august = IncomeVO(
        dataMonth = "2026-08", platform = "pe", totalDiamond = 2_859_639,
        channelIpCost = 962_437.59, sharableFlow = 1_897_201.41, developerShare = 967_972.29,
        exchangeRate = "0.01", income = "10979.75", incentiveIncome = "1300.00", tax = "1756.76"
    )
    private val july = august.copy(
        dataMonth = "2026-07", totalDiamond = 901_361, channelIpCost = 289_995.77,
        sharableFlow = 611_365.23, developerShare = 305_682.62,
        income = "4168.19", incentiveIncome = "1111.37", tax = "666.91", availableIncome = "4513.57"
    )

    @Test fun api_fields_preserve_units_and_missing_values() {
        val data = json.decodeFromString<IncomeDetailVO>("""
            {"count":1,"incomes":[{"data_month":"2026-08","channel_ip_cost":962437.59,
            "sharable_flow":1897201.41,"developer_share":967972.29,"exchange_rate":"0.01",
            "total_diamond":2859639,"other_field":"ignored"}]}
        """.trimIndent()).incomes.single()
        assertEquals(967_972.29, data.developerShare)
        assertEquals(0.3365591216233937, data.actualEcosystemFeeRatio()!!, 1e-12)
        assertNull(Json.decodeFromString<IncomeVO>("{}").actualEcosystemFeeRatio())
        assertNull(Json.decodeFromString<IncomeVO>("{}").developerShare)
    }

    @Test fun actual_fee_does_not_assume_a_single_developer_tier() {
        val fee = august.actualEcosystemFeeRatio()!!
        assertEquals(0.3365591216233937, fee, 1e-12)
        assertEquals(0.5102106106910389, august.developerShare!! / august.sharableFlow!!, 1e-12)
        assertTrue(kotlin.math.abs(fee - (1 - august.developerShare / 0.525 / august.totalDiamond)) > 0.01)
    }

    @Test fun published_month_uses_official_developer_total_and_tax() {
        val profit = calculateMonthlyProfit(2026, 8, mapOf("a" to 2_859_639.0), emptyMap(), listOf(july, august))
        assertNotNull(profit.settlement)
        assertEquals(967_972.29, profit.developerProfit)
        assertEquals(10979.75, profit.totalProfit)
        assertEquals(9222.99, profit.netIncome, 1e-9)
        assertEquals(1300.0, profit.settlement.incentiveIncome)
        assertEquals(2_859_639.0, profit.sumProfit)
        assertEquals("2026-08", profit.feeSourceMonth)
    }

    @Test fun monthly_income_never_uses_accumulated_available_income() {
        val profit = calculateMonthlyProfit(2026, 7, emptyMap(), emptyMap(), listOf(july))
        assertEquals(4168.19, profit.totalProfit)
        assertEquals(3501.28, profit.netIncome, 1e-9)
    }

    @Test fun unpublished_month_uses_previous_month_fee_but_current_module_tiers() {
        val current = mapOf("small" to 400_000.0, "large" to 1_600_000.0)
        val profit = calculateMonthlyProfit(2026, 9, current, emptyMap(), listOf(august, july))
        val fee = august.actualEcosystemFeeRatio()!!
        assertNull(profit.settlement)
        assertEquals("2026-08", profit.feeSourceMonth)
        assertEquals(fee, profit.ecosystemFeeRatio)
        assertEquals((400_000 * 0.5 + 1_600_000 * 0.525) * (1 - fee), profit.developerProfit, 1e-7)
        assertEquals(1100.0, profit.subsidyProfit.values.sum())
        assertEquals(200.0, profit.profitSubsidy)
        assertEquals(profit.totalProfit, profit.toModuleIncomeDetails().sumOf { it.totalIncome } + profit.profitSubsidy, 1e-7)
    }

    @Test fun missing_previous_month_uses_latest_earlier_valid_bill_with_visible_source() {
        val future = august.copy(dataMonth = "2026-10")
        val pc = august.copy(platform = "pc", dataMonth = "2026-08")
        val profit = calculateMonthlyProfit(2026, 9, mapOf("a" to 500_000.0), emptyMap(), listOf(future, pc, july))
        assertEquals("2026-07", profit.feeSourceMonth)
        assertTrue(profit.calculationLabel().contains("2026-07"))
        assertEquals(july.actualEcosystemFeeRatio(), profit.ecosystemFeeRatio)
    }

    @Test fun no_bill_or_ambiguous_bill_does_not_silently_use_a_fixed_fee() {
        for (records in listOf(emptyList(), listOf(august, august.copy(id = "duplicate")))) {
            val profit = calculateMonthlyProfit(2026, 9, mapOf("a" to 100_000.0), emptyMap(), records)
            assertFalse(profit.hasIncome)
            assertNull(profit.ecosystemFeeRatio)
            assertEquals(100_000.0, profit.sumProfit)
            assertTrue(profit.toModuleIncomeDetails().isEmpty())
        }
    }

    @Test fun invalid_fee_identity_and_nonfinite_values_are_rejected() {
        assertNull(august.copy(channelIpCost = null).actualEcosystemFeeRatio())
        assertNull(august.copy(channelIpCost = Double.NaN).actualEcosystemFeeRatio())
        assertNull(august.copy(sharableFlow = Double.POSITIVE_INFINITY).actualEcosystemFeeRatio())
        assertNull(august.copy(channelIpCost = -1.0).actualEcosystemFeeRatio())
        assertNull(august.copy(sharableFlow = 0.0).actualEcosystemFeeRatio())
        assertNull(august.copy(totalDiamond = 0).actualEcosystemFeeRatio())
        assertEquals(0.2, august.copy(totalDiamond = 100, adjustDiamond = 50,
            sharableFlow = 120.0, channelIpCost = 30.0).actualEcosystemFeeRatio())
    }

    @Test fun tier_boundaries_share_return_and_subsidy_cap_use_the_supplied_fee() {
        for (fee in listOf(0.0, 0.3365591216233937, 1.0)) {
            assertEquals(900_000 * (1 - fee) * 0.5, getDeveloperProfit(900_000.0, 0.0, fee), 1e-7)
            assertEquals(1_000_000 * (1 - fee) * 0.525, getDeveloperProfit(1_000_000.0, 0.0, fee), 1e-7)
            assertEquals(10_000_000 * (1 - fee) * 0.55, getDeveloperProfit(10_000_000.0, 0.0, fee), 1e-7)
            val small = calculateProfit(mapOf("a" to 30_000.0), ecosystemFeeRatio = fee)
            assertEquals(30_000 * (1 - fee) * 0.75, small.developerProfit, 1e-7)
            assertEquals(300 * (1 - fee) * 0.25, small.toModuleIncomeDetails().single().shareReturn, 1e-7)
            val capped = calculateProfit(mapOf("a" to 60_000_000.0), ecosystemFeeRatio = fee)
            assertEquals(fee, capped.ecosystemFeeRatio)
            assertTrue(capped.subsidyProfit.isEmpty())
            assertEquals(capped.totalProfit, capped.toModuleIncomeDetails().single().totalIncome, 1e-7)
        }
        assertFailsWith<IllegalArgumentException> { calculateProfit(emptyMap(), ecosystemFeeRatio = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { getDeveloperProfit(100.0, 0.0, 1.1) }
    }

    @Test fun allocation_uses_official_net_total_without_recomputing_tax() {
        val raw = mapOf("a" to 2_000_000.0, "b" to 859_639.0)
        val profit = calculateMonthlyProfit(2026, 8, raw, emptyMap(), listOf(august))
        val owners = listOf(ProfitPerson(1, "test", "甲", 1), ProfitPerson(2, "test", "乙", 2))
        val result = calculateProfitAllocation(profit, owners,
            listOf(ModuleOwnership("a", 1, 1.0), ModuleOwnership("b", 2, 1.0)))
        assertEquals(9222.99, result.netTotal, 1e-9)
        assertEquals(result.netTotal, result.payouts.sumOf { it.amount }, 1e-7)
        assertEquals(0.0, result.unassignedAmount, 1e-7)
    }

    @Test fun incomplete_module_flows_keep_official_pool_unassigned() {
        val profit = calculateMonthlyProfit(2026, 8, mapOf("a" to 2_000_000.0), emptyMap(), listOf(august))
        val result = calculateProfitAllocation(profit, listOf(ProfitPerson(1, "test", "甲", 1)),
            listOf(ModuleOwnership("a", 1, 1.0)))
        assertFalse(profit.allocationFlowMatches)
        assertEquals(0.0, result.payouts.single().amount)
        assertEquals(9222.99, result.unassignedAmount, 1e-9)
    }

    @Test fun fees_and_currency_come_from_bill_not_fixed_conversion_or_estimated_tax() {
        val official = august.copy(exchangeRate = "0.02", tax = "100.00", techServiceFee = 20.0, totalUsagePrice = 5.0)
        val profit = calculateMonthlyProfit(2026, 8, emptyMap(), emptyMap(), listOf(official))
        assertEquals(0.02, profit.exchangeRate)
        assertEquals(10854.75, profit.netIncome, 1e-9)
    }

    @Test fun bill_failure_is_visible_and_never_presented_as_zero_income() {
        val profit = calculateMonthlyProfit(2026, 9, mapOf("a" to 100_000.0), emptyMap(),
            emptyList(), billError = "官方账单获取失败")
        assertFalse(profit.hasIncome)
        assertEquals("官方账单获取失败", profit.calculationLabel())
    }

    @Test fun year_boundary_uses_previous_december_not_future_bill() {
        val december = august.copy(dataMonth = "2025-12")
        val profit = calculateMonthlyProfit(2026, 1, mapOf("a" to 100_000.0), emptyMap(),
            listOf(august, december))
        assertEquals("2025-12", profit.feeSourceMonth)
        assertNull(profit.settlement)
    }
}
