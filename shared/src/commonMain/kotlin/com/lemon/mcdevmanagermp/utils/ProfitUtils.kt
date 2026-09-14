package com.lemon.mcdevmanagermp.utils

import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
data class ProfitData(
    val sumProfit: Double = 0.0,
    val totalProfit: Double = 0.0,
    val developerProfit: Double = 0.0,
    val profitSubsidy: Double = 0.0,
    val subsidyProfit: Map<String, Double> = emptyMap(),
    val subsidyPercent: Double = 0.0,
    val moduleDiamonds: Map<String, Double> = emptyMap(),
    val moduleNames: Map<String, String> = emptyMap(),
    val ecosystemFeeRatio: Double? = null,
    val feeSourceMonth: String? = null,
    val settlement: ProfitSettlement? = null,
    val unavailableReason: String? = null
) {
    val hasIncome: Boolean get() = ecosystemFeeRatio != null || settlement != null
    val exchangeRate: Double get() = settlement?.exchangeRate ?: 0.01
    val netIncome: Double get() = settlement?.netIncome ?: (totalProfit - getTaxMoney(totalProfit))
    val allocationFlowMatches: Boolean
        get() = settlement == null || abs(moduleDiamonds.values.sum() - sumProfit) <= 0.02
}

/** 月度官方总账，不使用可能累积了多个月份的 available_income。 */
@Serializable
data class ProfitSettlement(
    val month: String,
    val exchangeRate: Double,
    val incentiveIncome: Double,
    val tax: Double,
    val techServiceFee: Double,
    val totalUsagePrice: Double,
    val netIncome: Double,
    val sharableFlow: Double?
)

data class ModuleIncomeDetail(
    val moduleId: String,
    val moduleName: String,
    val flowIncome: Double,
    val developerShare: Double,
    val shareReturn: Double,
    val subsidyAmount: Double,
    val totalIncome: Double
)

private fun getSharedProfit(profit: Double): Double = when {
    profit < 1_000_000 -> 0.5
    profit < 10_000_000 -> 0.525
    else -> 0.55
}

fun ProfitData.toModuleIncomeDetails(): List<ModuleIncomeDetail> {
    val feeRatio = ecosystemFeeRatio ?: return emptyList()
    if (subsidyPercent == 0.0 && moduleDiamonds.isEmpty()) return emptyList()
    return moduleDiamonds
        .filter { (_, revenue) -> revenue > 0 }
        .map { (itemId, revenue) ->
            val sharedProfit = getSharedProfit(revenue)
            val shareReturnDiamonds = revenue * (1 - feeRatio) * (1 - sharedProfit) * subsidyPercent
            val shareRmb = getDeveloperProfit(revenue, subsidyPercent, feeRatio) * exchangeRate
            val subsidy = subsidyProfit[itemId] ?: 0.0
            ModuleIncomeDetail(
                moduleId = itemId,
                moduleName = moduleNames[itemId] ?: itemId,
                flowIncome = revenue * exchangeRate,
                developerShare = shareRmb,
                shareReturn = shareReturnDiamonds * exchangeRate,
                subsidyAmount = subsidy,
                totalIncome = shareRmb + subsidy
            )
        }.sortedByDescending { it.flowIncome }
}

fun calculateProfit(
    itemProfitMap: Map<String, Double>,
    moduleNames: Map<String, String> = emptyMap(),
    ecosystemFeeRatio: Double? = null
): ProfitData {
    val sumProfit = itemProfitMap.values.sum()
    if (ecosystemFeeRatio == null) return ProfitData(
        sumProfit = sumProfit,
        moduleDiamonds = itemProfitMap,
        moduleNames = moduleNames,
        unavailableReason = "未取得历史账单生态费率，暂不估算收益与分账"
    )
    require(ecosystemFeeRatio.isFinite() && ecosystemFeeRatio in 0.0..1.0)
    val subsidyPercent = when {
        sumProfit < 100_000 -> 0.5
        sumProfit < 300_000 -> 0.3
        sumProfit < 500_000 -> 0.2
        sumProfit < 1_000_000 -> 0.1
        else -> 0.0
    }

    var totalSharedProfit = 0.0
    for ((_, profit) in itemProfitMap) {
        val sharedProfit = getDeveloperProfit(profit, subsidyPercent, ecosystemFeeRatio)
        totalSharedProfit += sharedProfit
    }

    if (sumProfit >= 50_000_000) return ProfitData(
        sumProfit = sumProfit,
        totalProfit = totalSharedProfit / 100,
        developerProfit = totalSharedProfit,
        subsidyProfit = emptyMap(),
        subsidyPercent = 0.0,
        moduleDiamonds = itemProfitMap,
        moduleNames = moduleNames,
        ecosystemFeeRatio = ecosystemFeeRatio
    )

    val subsidyValues = mutableMapOf<String, Double>()
    val levelCounts = mutableMapOf<Int, Int>()
    val sortedItems = itemProfitMap.entries.sortedByDescending { it.value }

    for ((itemName, profit) in sortedItems) {
        var subsidyLevel = when {
            profit < 100_000 -> 0
            profit < 500_000 -> 1
            profit < 1_000_000 -> 2
            profit < 3_000_000 -> 3
            profit < 5_000_000 -> 4
            profit < 50_000_000 -> 5
            else -> 0
        }
        while (subsidyLevel > 0) {
            val count = levelCounts.getOrElse(subsidyLevel) { 0 }
            if (count < 3) {
                levelCounts[subsidyLevel] = count + 1
                break
            }
            subsidyLevel--
        }
        val subsidyAmount = getSubsidyAmount(subsidyLevel)
        if (subsidyAmount > 0.0) {
            subsidyValues[itemName] = subsidyAmount
        }
    }

    val totalSubsidy = subsidyValues.values.sum()
    val profitSubsidy = when {
        sumProfit < 1_000_000 -> 0.0
        sumProfit < 5_000_000 -> 200.0
        sumProfit < 10_000_000 -> 1000.0
        else -> 0.0
    }

    return ProfitData(
        sumProfit = sumProfit,
        totalProfit = totalSharedProfit / 100.0 + totalSubsidy + profitSubsidy,
        developerProfit = totalSharedProfit,
        profitSubsidy = profitSubsidy,
        subsidyProfit = subsidyValues,
        subsidyPercent = subsidyPercent,
        moduleDiamonds = itemProfitMap,
        moduleNames = moduleNames,
        ecosystemFeeRatio = ecosystemFeeRatio
    )
}

fun getSubsidyAmount(subsidyLevel: Int): Double = when (subsidyLevel) {
    1 -> 100.0; 2 -> 500.0; 3 -> 1_000.0; 4 -> 3_000.0; 5 -> 5_000.0; else -> 0.0
}

fun getTaxMoney(totalProfit: Double): Double {
    return when {
        totalProfit < 800 -> 0.0
        totalProfit < 4000 -> (totalProfit - 800) * 0.2
        else -> (totalProfit * 0.8) * 0.2
    }
}

fun getDeveloperProfit(profit: Double, subsidyPercent: Double, ecosystemFeeRatio: Double): Double {
    require(ecosystemFeeRatio.isFinite() && ecosystemFeeRatio in 0.0..1.0)
    val sharedProfit = when {
        profit < 1_000_000 -> 0.5
        profit < 10_000_000 -> 0.525
        else -> 0.55
    }
    return profit * (1 - ecosystemFeeRatio) * (sharedProfit + (1 - sharedProfit) * subsidyPercent)
}
