package com.lemon.mcdevmanagermp.domain.main

import com.lemon.mcdevmanagermp.data.common.NetworkState
import com.lemon.mcdevmanagermp.data.consts.CookiesExpiredException
import com.lemon.mcdevmanagermp.data.consts.LoginException
import com.lemon.mcdevmanagermp.data.vo.netease.user.LevelInfoVO
import com.lemon.mcdevmanagermp.data.vo.netease.user.OverviewVO
import com.lemon.mcdevmanagermp.data.vo.netease.user.UserInfoVO
import com.lemon.mcdevmanagermp.data.vo.netease.income.IncomeVO
import com.lemon.mcdevmanagermp.domain.income.IncomeRepository
import com.lemon.mcdevmanagermp.domain.analyze.AnalyzeRepository
import com.lemon.mcdevmanagermp.domain.profitsharing.ProfitAllocationSummary
import com.lemon.mcdevmanagermp.domain.profitsharing.ProfitSharingRepository
import com.lemon.mcdevmanagermp.domain.profitsharing.calculateProfitAllocation
import com.lemon.mcdevmanagermp.domain.resource.GetResourceListUseCase
import com.lemon.mcdevmanagermp.domain.user.UserRepository
import com.lemon.mcdevmanagermp.utils.ProfitData
import com.lemon.mcdevmanagermp.utils.Logger
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus

data class MainDashboardData(
    val userInfo: NetworkState<UserInfoVO>,
    val overview: NetworkState<OverviewVO>,
    val levelInfo: NetworkState<LevelInfoVO>
)

data class ProfitResult(
    val thisMonth: ProfitData,
    val lastMonth: ProfitData? = null,
    val nextMonth: ProfitData? = null,
    val thisMonthPeriod: ProfitPeriod,
    val lastMonthPeriod: ProfitPeriod? = null,
    val nextMonthPeriod: ProfitPeriod? = null,
    val thisMonthAllocation: ProfitAllocationSummary,
    val lastMonthAllocation: ProfitAllocationSummary? = null,
    val nextMonthAllocation: ProfitAllocationSummary? = null
)

data class ProfitPeriod(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val dataThroughDate: LocalDate?
) {
    val totalDays: Int
        get() = (endDate.toEpochDays() - startDate.toEpochDays() + 1).toInt()

    val elapsedDays: Int
        get() {
            val latestDate = dataThroughDate ?: return 0
            if (latestDate < startDate) return 0
            val effectiveEndDate = minOf(latestDate, endDate)
            return (effectiveEndDate.toEpochDays() - startDate.toEpochDays() + 1).toInt()
        }

    fun estimateSettlement(currentProfit: Double): Double? {
        if (elapsedDays !in 1 until totalDays) return null
        return currentProfit / elapsedDays * totalDays
    }
}

internal data class ProfitMonth(val year: Int, val month: Int) : Comparable<ProfitMonth> {
    override fun compareTo(other: ProfitMonth): Int =
        compareValuesBy(this, other, ProfitMonth::year, ProfitMonth::month)

    fun shift(months: Int): ProfitMonth {
        val firstDay = LocalDate(year, month, 1)
        val shifted = if (months >= 0) {
            firstDay.plus(months, DateTimeUnit.MONTH)
        } else {
            firstDay.minus(-months, DateTimeUnit.MONTH)
        }
        return ProfitMonth(shifted.year, shifted.month.number)
    }
}

internal data class ProfitMonthWindow(
    val current: ProfitMonth,
    val showLastMonth: Boolean,
    val showNextMonth: Boolean
)

internal fun profitMonthWindow(today: LocalDate): ProfitMonthWindow {
    val current = ProfitMonth(today.year, today.month.number)
    val nextMonth = current.shift(1)
    val nextMonthStart = LocalDate(nextMonth.year, nextMonth.month, 1)
    return ProfitMonthWindow(
        current = current,
        showLastMonth = today.day <= 15,
        showNextMonth = today >= nextMonthStart.minus(9, DateTimeUnit.DAY)
    )
}

private data class MonthDiamondData(
    val moduleDiamonds: Map<String, Double>,
    val moduleNames: Map<String, String>,
    val dataThroughDate: LocalDate?
)

private data class ComponentDiamondData(
    val itemId: String,
    val moduleName: String,
    val diamonds: Double,
    val dataThroughDate: LocalDate?
)

class MainUseCase(
    private val userRepository: UserRepository,
    private val analyzeRepository: AnalyzeRepository,
    private val getResourceListUseCase: GetResourceListUseCase,
    private val incomeRepository: IncomeRepository,
    private val profitSharingRepository: ProfitSharingRepository? = null
) {
    suspend fun loadDashboard(): MainDashboardData = coroutineScope {
        val userInfoDeferred = async { userRepository.getUserInfo() }
        val overviewDeferred = async { userRepository.getOverview() }
        val levelDeferred = async { userRepository.getLevelInfo() }

        MainDashboardData(
            userInfo = userInfoDeferred.await(),
            overview = overviewDeferred.await(),
            levelInfo = levelDeferred.await()
        )
    }

    suspend fun computeProfit(
        year: Int,
        month: Int,
        today: LocalDate,
        accountKey: String,
        includeLastMonth: Boolean,
        includeNextMonth: Boolean
    ): ProfitResult = coroutineScope {
        val billsDeferred = async { loadSettlementBills() }
        val currentMonth = ProfitMonth(year, month)
        val thisMonthDeferred = async {
            currentMonth to getOneMonthComponentDiamonds(currentMonth.year, currentMonth.month)
        }
        val lastMonthDeferred = if (includeLastMonth) {
            val target = currentMonth.shift(-1)
            async { target to getOneMonthComponentDiamonds(target.year, target.month) }
        } else {
            null
        }
        val nextMonthDeferred = if (includeNextMonth) {
            val target = currentMonth.shift(1)
            async { target to getOneMonthComponentDiamonds(target.year, target.month) }
        } else {
            null
        }

        val (thisMonth, thisMonthData) = thisMonthDeferred.await()
        val lastMonthResult = lastMonthDeferred?.await()
        val nextMonthResult = nextMonthDeferred?.await()
        val (bills, billError) = billsDeferred.await()
        fun profit(target: ProfitMonth, data: MonthDiamondData) = calculateMonthlyProfit(
            target.year, target.month, data.moduleDiamonds, data.moduleNames, bills, billError
        )
        val thisMonthProfit = profit(thisMonth, thisMonthData)
        val lastMonthProfit = lastMonthResult?.let { (target, data) -> profit(target, data) }
        val nextMonthProfit = nextMonthResult?.let { (target, data) -> profit(target, data) }

        ProfitResult(
            thisMonth = thisMonthProfit,
            lastMonth = lastMonthProfit,
            nextMonth = nextMonthProfit,
            thisMonthPeriod = thisMonth.toPeriod(thisMonthData, today),
            lastMonthPeriod = lastMonthResult?.let { (target, data) -> target.toPeriod(data, today) },
            nextMonthPeriod = nextMonthResult?.let { (target, data) -> target.toPeriod(data, today) },
            thisMonthAllocation = calculateAllocation(accountKey, thisMonthProfit),
            lastMonthAllocation = lastMonthProfit?.let { calculateAllocation(accountKey, it) },
            nextMonthAllocation = nextMonthProfit?.let { calculateAllocation(accountKey, it) }
        )
    }

    suspend fun computeMonthProfit(year: Int, month: Int): ProfitData = coroutineScope {
        val billsDeferred = async { loadSettlementBills() }
        val data = getOneMonthComponentDiamonds(year, month)
        val (bills, billError) = billsDeferred.await()
        calculateMonthlyProfit(year, month, data.moduleDiamonds, data.moduleNames, bills, billError)
    }

    private suspend fun loadSettlementBills(): Pair<List<IncomeVO>, String?> =
        when (val result = incomeRepository.getIncome("all")) {
            is NetworkState.Success -> result.data?.incomes.orEmpty() to null
            is NetworkState.Error -> {
                Logger.e("收益速算的官方账单加载失败", result.e)
                emptyList<IncomeVO>() to "官方账单获取失败，请刷新重试；暂不使用固定费率估算"
            }
        }

    suspend fun calculateAllocation(
        accountKey: String,
        profitData: ProfitData
    ): ProfitAllocationSummary {
        val repository = profitSharingRepository ?: return ProfitAllocationSummary()
        if (accountKey.isBlank()) return ProfitAllocationSummary()
        return calculateProfitAllocation(
            profitData = profitData,
            people = repository.getPeople(accountKey),
            ownerships = repository.getOwnerships(accountKey)
        )
    }

    private suspend fun getOneMonthComponentDiamonds(year: Int, month: Int): MonthDiamondData =
        coroutineScope {
            val normalResources = async { getResourceListUseCase("pe", onlineOnly = true) }
            val lobbyResources = async { analyzeRepository.getLobbyIncomeResources() }
            val resList = when (val resources = normalResources.await()) {
                is NetworkState.Success -> resources.data ?: emptyList()
                is NetworkState.Error -> emptyList()
            }
            val lobbyResList = when (val resources = lobbyResources.await()) {
                is NetworkState.Success -> resources.data?.items ?: emptyList()
                is NetworkState.Error -> emptyList()
            }
            val (startDate, endDate) = monthDateRange(year, month)
            val startDateParam = formatDateParam(startDate)
            val endDateParam = formatDateParam(endDate)

            val normalData = resList.map { res ->
                async {
                    val result = analyzeRepository.getDayDetail(
                        platform = "pe",
                        category = "pe",
                        startDate = startDateParam,
                        endDate = endDateParam,
                        itemListStr = res.itemId
                    )
                    if (result is NetworkState.Success) {
                        val dayData = result.data?.data.orEmpty()
                        ComponentDiamondData(
                            itemId = res.itemId,
                            moduleName = res.itemName,
                            diamonds = dayData.sumOf { it.diamond * (1 - it.refundRate) },
                            dataThroughDate = dayData.mapNotNull { parseDateParamOrNull(it.dateId) }.maxOrNull()
                        )
                    } else {
                        ComponentDiamondData(res.itemId, res.itemName, 0.0, null)
                    }
                }
            }.map { it.await() }

            val lobbyData = lobbyResList.map { res ->
                async {
                    val result = analyzeRepository.getDayDetail(
                        platform = "pe",
                        category = "pe",
                        startDate = startDateParam,
                        endDate = endDateParam,
                        itemListStr = res.itemId,
                        isLobby = true
                    )
                    if (result is NetworkState.Success) {
                        val dayData = result.data?.data.orEmpty()
                        ComponentDiamondData(
                            itemId = res.itemId,
                            moduleName = res.itemName,
                            diamonds = dayData.sumOf { it.diamond.toDouble() },
                            dataThroughDate = dayData.mapNotNull { parseDateParamOrNull(it.dateId) }.maxOrNull()
                        )
                    } else {
                        ComponentDiamondData(res.itemId, res.itemName, 0.0, null)
                    }
                }
            }.map { it.await() }

            val componentData = (normalData + lobbyData)
                .groupBy { it.itemId }
                .map { (itemId, items) ->
                    ComponentDiamondData(
                        itemId = itemId,
                        moduleName = items.firstNotNullOfOrNull { it.moduleName.takeIf(String::isNotBlank) }
                            ?: itemId,
                        diamonds = items.sumOf { it.diamonds },
                        dataThroughDate = items.mapNotNull { it.dataThroughDate }.maxOrNull()
                    )
                }

            MonthDiamondData(
                moduleDiamonds = componentData.associate { it.itemId to it.diamonds },
                moduleNames = componentData.associate { it.itemId to it.moduleName },
                dataThroughDate = componentData.mapNotNull { it.dataThroughDate }.maxOrNull()
            )
        }

    fun isSessionExpired(vararg states: NetworkState<*>): Boolean {
        return states.any { state ->
            state is NetworkState.Error &&
                    (state.e is CookiesExpiredException || state.e is LoginException)
        }
    }
}

private fun ProfitMonth.toPeriod(data: MonthDiamondData, today: LocalDate): ProfitPeriod =
    createProfitPeriod(year, month, data.dataThroughDate, today)

internal fun monthDateRange(year: Int, month: Int): Pair<LocalDate, LocalDate> {
    val firstDay = LocalDate(year, month, 1)
    val endDate = firstDay.plus(1, DateTimeUnit.MONTH).minus(10, DateTimeUnit.DAY)
    val startDate = firstDay.minus(9, DateTimeUnit.DAY)
    return startDate to endDate
}

internal fun createProfitPeriod(
    year: Int,
    month: Int,
    dataThroughDate: LocalDate?,
    today: LocalDate
): ProfitPeriod {
    val (startDate, endDate) = monthDateRange(year, month)
    // 接口会返回查询区间内的未来占位行，统计进度最多只能到昨天。
    val latestCompletedDate = today.minus(1, DateTimeUnit.DAY)
    val effectiveDataThroughDate = dataThroughDate?.let { minOf(it, latestCompletedDate) }
    return ProfitPeriod(startDate, endDate, effectiveDataThroughDate)
}

internal fun mergeProfitDiamonds(
    normal: Map<String, Double>,
    lobby: Map<String, Double>
): Map<String, Double> = buildMap {
    putAll(normal)
    lobby.forEach { (name, diamonds) -> put(name, (get(name) ?: 0.0) + diamonds) }
}

private fun formatDateParam(date: LocalDate): String = date.toString().replace("-", "")

private fun parseDateParamOrNull(value: String): LocalDate? {
    if (value.length != 8) return null
    val isoDate = "${value.substring(0, 4)}-${value.substring(4, 6)}-${value.substring(6, 8)}"
    return runCatching { LocalDate.parse(isoDate) }.getOrNull()
}
