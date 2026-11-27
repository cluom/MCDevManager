package com.lemon.mcdevmanagermp

import com.lemon.mcdevmanagermp.data.vo.netease.income.IncomeVO
import com.lemon.mcdevmanagermp.domain.main.ProfitMonth
import com.lemon.mcdevmanagermp.domain.main.applyUpstreamSettlement
import com.lemon.mcdevmanagermp.domain.profitsharing.*
import com.lemon.mcdevmanagermp.utils.*
import kotlin.test.*

/** 人员分账与上游反推算法的衔接，替代已移除的账单字段比例算法测试。 */
class SettlementProfitTest {
    private val month = ProfitMonth(2026, 10)
    private val estimate = calculateProfit(mapOf("a" to 20000.0, "b" to 5000.0), mapOf("a" to "同名", "b" to "同名"))
    private val bill = IncomeVO(dataMonth="202610", income="151.25", playPlanIncome="20.00",
        tax="10.00", techServiceFee=1.0, totalUsagePrice=2.0, totalDiamond=26000,
        channelIpCost=0.0, sharableFlow=26000.0)
    private val people = listOf(ProfitPerson(1,"acct","甲",0), ProfitPerson(2,"acct","乙",0))

    @Test fun usesReverseFormulaNotOfficialFeeFields() {
        val result = applyUpstreamSettlement(month, estimate, listOf(bill))
        assertEquals(0.3, result.ecosystemFeeRatio!!, 1e-9)
        assertEquals(151.25, result.totalProfit)
        assertEquals(138.25, result.netIncome)
        assertEquals(26000.0, result.sumProfit)
    }
    @Test fun weightsAndDuplicateNamesPreserveOfficialPool() {
        val result = applyUpstreamSettlement(month, estimate, listOf(bill))
        val allocation = calculateProfitAllocation(result, people,
            listOf(ModuleOwnership("a",1,25.0),ModuleOwnership("a",2,75.0),ModuleOwnership("b",2,1.0)))
        assertEquals(138.25, allocation.payouts.sumOf { it.amount } + allocation.unassignedAmount, 1e-9)
        assertEquals(138.25*0.8*0.25, allocation.payouts.single { it.personId==1L }.amount, 1e-9)
    }
    @Test fun unownedShareRemainsUnassigned() {
        val result = applyUpstreamSettlement(month, estimate, listOf(bill))
        val allocation = calculateProfitAllocation(result, people,listOf(ModuleOwnership("a",1,1.0)))
        assertEquals(138.25*0.2, allocation.unassignedAmount, 1e-9)
    }
    @Test fun incompleteReverseCannotAllocate() {
        for (data in listOf(estimate.copy(isFlowComplete=false), calculateProfit(emptyMap()))) {
            val result=applyUpstreamSettlement(month,data,listOf(bill))
            assertNull(result.ecosystemFeeRatio)
            assertEquals(138.25,calculateProfitAllocation(result,people,emptyList()).unassignedAmount)
        }
    }
    @Test fun unpublishedMonthKeepsUpstreamEstimate() {
        assertEquals(estimate,applyUpstreamSettlement(month,estimate,emptyList()))
        assertEquals(estimate,applyUpstreamSettlement(month,estimate,listOf(bill.copy(platform="pc"))))
        assertEquals(estimate,applyUpstreamSettlement(month,estimate,listOf(bill,bill)))
    }
    @Test fun differenceShowsDirectionAndAmounts() {
        val result=applyUpstreamSettlement(month,estimate,listOf(bill))
        assertTrue(result.allocationNotice()!!.contains("比官方少 1000.00 钻石"))
        assertTrue(result.allocationNotice()!!.contains("10.00 元"))
        val excess=applyUpstreamSettlement(month,estimate,listOf(bill.copy(totalDiamond=24000)))
        assertTrue(excess.allocationNotice()!!.contains("比官方多 1000.00 钻石"))
        val match=applyUpstreamSettlement(month,estimate,listOf(bill.copy(totalDiamond=25000)))
        assertFalse(match.allocationNotice()!!.contains("流水差额"))
    }
}
