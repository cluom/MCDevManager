package com.lemon.mcdevmanagermp.domain.main

import com.lemon.mcdevmanagermp.data.repository.monthKey
import com.lemon.mcdevmanagermp.utils.ECO_FEE_RATE
import com.lemon.mcdevmanagermp.utils.ProfitData
import com.lemon.mcdevmanagermp.utils.calculateProfit

/** 只参考紧邻上期的有效费率；不借用未来月份或跨过缺失月份。原始快照不变。 */
internal fun estimateWithPreviousPeriod(
    month: ProfitMonth,
    raw: ProfitData,
    rates: Map<String, Double?>
): ProfitData {
    if (raw.settlement != null) return raw
    val previous = monthKey(month.shift(-1))
    val rate = rates[previous]?.takeIf { it.isFinite() && it in 0.0..1.0 }
    return calculateProfit(raw.moduleDiamonds, raw.moduleNames, rate ?: ECO_FEE_RATE).copy(
        emeraldModDiamonds = raw.emeraldModDiamonds,
        isFlowComplete = raw.isFlowComplete,
        feeSourceMonth = previous.takeIf { rate != null }
    )
}
