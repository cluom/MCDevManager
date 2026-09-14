package com.lemon.mcdevmanagermp.utils

import com.lemon.mcdevmanagermp.utils.extension.formatDecimal
import kotlin.math.abs

fun ProfitData.calculationLabel(): String {
    val fee = ecosystemFeeRatio?.let { (it * 100).formatDecimal(4) + "%" }
    return when {
        settlement != null -> "${settlement.month} 官方总账" +
            (fee?.let { " · $feeSourceMonth 生态费 $it" } ?: "")
        fee != null -> "参考 $feeSourceMonth 实际生态费 $fee · 本期预估"
        else -> unavailableReason ?: "等待官方账单数据"
    }
}

fun ProfitData.summaryRows(): List<Pair<String, String>> = buildList {
    add("月总流水(元)" to (sumProfit * exchangeRate).formatDecimal(2))
    val flow = settlement?.sharableFlow ?: ecosystemFeeRatio?.let { sumProfit * (1 - it) }
    add("可分成流水(元)" to (flow?.let { (it * exchangeRate).formatDecimal(2) } ?: "—"))
    add("开发者分成(元)" to if (hasIncome) (developerProfit * exchangeRate).formatDecimal(2) else "—")
    val bill = settlement
    if (bill != null) {
        add("官方激励金(元)" to bill.incentiveIncome.formatDecimal(2))
        val adjustment = totalProfit - developerProfit * exchangeRate - bill.incentiveIncome
        if (abs(adjustment) >= 0.005) add("其他结算项目及调整(元)" to adjustment.formatDecimal(2))
        add("官方税费(元)" to bill.tax.formatDecimal(2))
        if (bill.techServiceFee > 0) add("技术服务费(元)" to bill.techServiceFee.formatDecimal(2))
        if (bill.totalUsagePrice > 0) add("使用费(元)" to bill.totalUsagePrice.formatDecimal(2))
    } else if (hasIncome) {
        add("模组激励合计(元)" to subsidyProfit.values.sum().formatDecimal(2))
        add("流水激励(元)" to profitSubsidy.formatDecimal(2))
        val shareReturn = toModuleIncomeDetails().sumOf { it.shareReturn }
        if (shareReturn > 0) add("分成返还(已含于开发者分成)" to shareReturn.formatDecimal(2))
    }
}

fun ProfitData.allocationNotice(): String? = when {
    settlement == null -> null
    !allocationFlowMatches -> "逐模组流水与官方总账未对齐，暂不自动分账，全部净收益保留为未分配。"
    else -> "分账总额采用官方净收益；各模组占比仍为估算，并非官方逐模组结算。"
}
