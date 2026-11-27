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
    val emeraldModDiamonds: Map<String, Double> = emptyMap(),
    val isFlowComplete: Boolean = true,
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

data class ActualModuleIncomeItem(
    val moduleName: String,
    val flowIncome: Double,
    val actualDeveloperShare: Double,
    val actualShareReturn: Double,
    val subsidyAmount: Double,
    val actualTotalIncome: Double,
    val estimatedTotalIncome: Double,
    val incomeShare: Double
) {
    val deltaFromEstimate: Double get() = actualTotalIncome - estimatedTotalIncome
}

data class ActualModuleIncomeSummary(
    val actualEcoFeeRate: Double,
    val settlementIncome: Double,
    val playPlanIncome: Double,
    val profitSubsidy: Double,
    val modulesTotalIncome: Double,
    val settlementNetIncome: Double,
    val items: List<ActualModuleIncomeItem>
)

/** 预估使用的生态费率：流水先扣除该比例，剩余部分再按分成比例结算给开发者 */
const val ECO_FEE_RATE = 0.3

private fun getSharedProfit(profit: Double): Double = when {
    profit < 1_000_000 -> 0.5
    profit < 10_000_000 -> 0.525
    else -> 0.55
}

fun ProfitData.toModuleIncomeDetails(ecoFeeRate: Double? = ecosystemFeeRatio): List<ModuleIncomeDetail> {
    val feeRatio = ecoFeeRate ?: return emptyList()
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
    ecosystemFeeRatio: Double? = ECO_FEE_RATE
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

fun ProfitData.reverseEcoFeeRate(actualIncome: Double): Double? {
    if (!isFlowComplete) return null
    // developerProfit 已按 ECO_FEE_RATE 扣过生态费，先还原为"零生态费"时的分成（元）
    val shareWithoutFee = moduleDiamonds.values.sumOf { getDeveloperProfit(it, subsidyPercent, 0.0) } * exchangeRate
    if (shareWithoutFee <= 0.0) return null
    val fixedSubsidy = subsidyProfit.values.sum() + profitSubsidy
    return 1 - (actualIncome - fixedSubsidy) / shareWithoutFee
}

/**
 * 针对已实际出账的月份，基于反推出的实际生态费率按模组拆分实际税前收益。
 * 所有模组实际小计之和 + 流水激励 + 畅玩计划收益等于结算单实际税前收益 [settlementIncome]。
 *
 * 流水不完整、无流水或反推费率超出 0~1 有效范围时返回 null。
 */
fun ProfitData.computeActualModuleSummary(
    settlementIncome: Double,
    playPlanIncome: Double,
    settlementNetIncome: Double
): ActualModuleIncomeSummary? {
    val reverseInput = settlementIncome - playPlanIncome
    val actualRate = reverseEcoFeeRate(reverseInput) ?: return null
    if (actualRate !in 0.0..1.0) return null

    val estimatedById = toModuleIncomeDetails(ECO_FEE_RATE).associateBy { it.moduleId }
    val actualModules = toModuleIncomeDetails(actualRate)
    if (actualModules.isEmpty()) return null

    val modulesTotal = actualModules.sumOf { it.totalIncome }
    val items = actualModules.map { actual ->
        val estimatedTotal = estimatedById[actual.moduleId]?.totalIncome ?: actual.totalIncome
        val share = if (modulesTotal > 0.0) (actual.totalIncome / modulesTotal).coerceIn(0.0, 1.0) else 0.0
        ActualModuleIncomeItem(
            moduleName = actual.moduleName,
            flowIncome = actual.flowIncome,
            actualDeveloperShare = actual.developerShare,
            actualShareReturn = actual.shareReturn,
            subsidyAmount = actual.subsidyAmount,
            actualTotalIncome = actual.totalIncome,
            estimatedTotalIncome = estimatedTotal,
            incomeShare = share
        )
    }.sortedByDescending { it.actualTotalIncome }

    return ActualModuleIncomeSummary(
        actualEcoFeeRate = actualRate,
        settlementIncome = settlementIncome,
        playPlanIncome = playPlanIncome,
        profitSubsidy = profitSubsidy,
        modulesTotalIncome = modulesTotal,
        settlementNetIncome = settlementNetIncome,
        items = items
    )
}
