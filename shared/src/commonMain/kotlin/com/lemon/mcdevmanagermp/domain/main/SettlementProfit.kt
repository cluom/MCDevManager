package com.lemon.mcdevmanagermp.domain.main

import com.lemon.mcdevmanagermp.data.vo.netease.income.IncomeVO
import com.lemon.mcdevmanagermp.utils.ProfitData
import com.lemon.mcdevmanagermp.utils.ProfitSettlement
import com.lemon.mcdevmanagermp.utils.calculateProfit
import kotlin.math.abs

/** 先核对钻石口径，再计算生态费；不能用统一开发者分成档位倒推。 */
internal fun IncomeVO.actualEcosystemFeeRatio(): Double? {
    val fee = channelIpCost?.takeIf { it.isFinite() && it >= 0 } ?: return null
    val sharable = sharableFlow?.takeIf { it.isFinite() && it >= 0 } ?: return null
    val gross = totalDiamond.toDouble() + adjustDiamond
    if (gross <= 0 || abs(gross - fee - sharable) > 0.02) return null
    return (fee / gross).takeIf { it in 0.0..1.0 }
}

private fun String.nonNegativeMoney(): Double? =
    toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }

private fun IncomeVO.toSettlement(): ProfitSettlement? {
    if (totalDiamond.toDouble() + adjustDiamond < 0) return null
    if (developerShare?.let { it.isFinite() && it >= 0 } != true) return null
    val rate = exchangeRate?.nonNegativeMoney()?.takeIf { it > 0 } ?: return null
    val total = income.nonNegativeMoney() ?: return null
    val taxAmount = tax.nonNegativeMoney() ?: return null
    val incentive = incentiveIncome.nonNegativeMoney() ?: return null
    if (!techServiceFee.isFinite() || !totalUsagePrice.isFinite()) return null
    if (techServiceFee < 0 || totalUsagePrice < 0) return null
    return ProfitSettlement(
        month = dataMonth,
        exchangeRate = rate,
        incentiveIncome = incentive,
        tax = taxAmount,
        techServiceFee = techServiceFee,
        totalUsagePrice = totalUsagePrice,
        netIncome = total - taxAmount - techServiceFee - totalUsagePrice,
        sharableFlow = sharableFlow?.takeIf { it.isFinite() && it >= 0 }
    )
}

internal fun calculateMonthlyProfit(
    year: Int,
    month: Int,
    moduleDiamonds: Map<String, Double>,
    moduleNames: Map<String, String>,
    incomes: List<IncomeVO>,
    billError: String? = null
): ProfitData {
    val targetMonth = "$year-${month.toString().padStart(2, '0')}"
    // 首页是 PE 模组及联机大厅流水，不能混入 PC 等平台的账单。
    // 同月多条账单时不擅自挑一条，也不依赖接口返回顺序。
    val bills = incomes.filter { it.platform == "pe" && Regex("[0-9]{4}-(0[1-9]|1[0-2])").matches(it.dataMonth) }
        .groupBy { it.dataMonth }.mapNotNull { (_, records) -> records.singleOrNull() }
    val official = bills.singleOrNull { it.dataMonth == targetMonth }
    val settlement = official?.toSettlement()
    val previous = bills.filter { it.dataMonth < targetMonth && it.actualEcosystemFeeRatio() != null }
        .maxByOrNull { it.dataMonth }
    val source = if (settlement != null && official.actualEcosystemFeeRatio() != null) official else previous
    val estimate = calculateProfit(moduleDiamonds, moduleNames, source?.actualEcosystemFeeRatio())
        .copy(feeSourceMonth = source?.dataMonth)

    if (settlement != null) return estimate.copy(
        sumProfit = official.totalDiamond.toDouble() + official.adjustDiamond,
        totalProfit = official.income.toDouble(),
        developerProfit = official.developerShare!!,
        settlement = settlement,
        unavailableReason = null
    )
    return if (source == null && billError != null) estimate.copy(unavailableReason = billError) else estimate
}
