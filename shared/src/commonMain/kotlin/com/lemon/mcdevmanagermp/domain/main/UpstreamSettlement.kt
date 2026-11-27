package com.lemon.mcdevmanagermp.domain.main

import com.lemon.mcdevmanagermp.data.repository.normalizeMonthKey
import com.lemon.mcdevmanagermp.data.repository.reverseInputIncome
import com.lemon.mcdevmanagermp.data.vo.netease.income.IncomeVO
import com.lemon.mcdevmanagermp.utils.ProfitData
import com.lemon.mcdevmanagermp.utils.ProfitSettlement
import com.lemon.mcdevmanagermp.utils.reverseEcoFeeRate
import com.lemon.mcdevmanagermp.utils.calculateProfit

/** 上游预估/反推负责模组占比，官方净额负责人员分账总池。 */
internal fun applyUpstreamSettlement(month: ProfitMonth, estimate: ProfitData, bills: List<IncomeVO>): ProfitData {
    val key = "${month.year}-${month.month.toString().padStart(2, '0')}"
    val bill = bills.filter { it.platform == "pe" && normalizeMonthKey(it.dataMonth) == key }.singleOrNull()
        ?: return estimate
    val total = bill.income.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return estimate
    val net = bill.monthlyNetIncome.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return estimate
    val rate = estimate.reverseEcoFeeRate(bill.reverseInputIncome)?.takeIf { it.isFinite() && it in 0.0..1.0 }
    val recalculated = calculateProfit(estimate.moduleDiamonds, estimate.moduleNames, rate)
    return recalculated.copy(
        emeraldModDiamonds = estimate.emeraldModDiamonds,
        isFlowComplete = estimate.isFlowComplete,
        sumProfit = bill.totalDiamond.toDouble() + bill.adjustDiamond,
        totalProfit = total,
        ecosystemFeeRatio = rate,
        feeSourceMonth = key,
        settlement = ProfitSettlement(key, 0.01, bill.incentiveIncome.toDoubleOrNull() ?: 0.0,
            bill.tax.toDoubleOrNull() ?: 0.0, bill.techServiceFee, bill.totalUsagePrice, net, bill.sharableFlow),
        unavailableReason = if (rate == null) "流水不完整或反推费率无效，暂不分配官方净收益" else null
    )
}
