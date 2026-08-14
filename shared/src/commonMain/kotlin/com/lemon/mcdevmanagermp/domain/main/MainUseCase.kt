package com.lemon.mcdevmanagermp.domain.main

import com.lemon.mcdevmanagermp.data.common.NetworkState
import com.lemon.mcdevmanagermp.data.consts.CookiesExpiredException
import com.lemon.mcdevmanagermp.data.consts.LoginException
import com.lemon.mcdevmanagermp.data.vo.netease.user.LevelInfoVO
import com.lemon.mcdevmanagermp.data.vo.netease.user.OverviewVO
import com.lemon.mcdevmanagermp.data.vo.netease.user.UserInfoVO
import com.lemon.mcdevmanagermp.domain.analyze.AnalyzeRepository
import com.lemon.mcdevmanagermp.domain.profitsharing.ProfitAllocationSummary
import com.lemon.mcdevmanagermp.domain.profitsharing.ProfitSharingRepository
import com.lemon.mcdevmanagermp.domain.profitsharing.calculateProfitAllocation
import com.lemon.mcdevmanagermp.domain.resource.GetResourceListUseCase
import com.lemon.mcdevmanagermp.domain.user.UserRepository
import com.lemon.mcdevmanagermp.utils.ProfitData
import com.lemon.mcdevmanagermp.utils.calculateProfit
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
    val lastMonth: ProfitData,
    val thisMonthPeriod: ProfitPeriod,
    val lastMonthPeriod: ProfitPeriod,
    val lastMonthAllocation: ProfitAllocationSummary
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

private data class MonthDiamondData(
    val moduleDiamonds: Map<String, Double>,
    val moduleNames: Map<String, String>,
    val dataThroughDate: LocalDate?
)

class MainUseCase(
    private val userRepository: UserRepository,
    private val analyzeRepository: AnalyzeRepository,
    private val getResourceListUseCase: GetResourceListUseCase,
    private val profitSharingRepository: ProfitSharingRepository
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
        accountKey: String
    ): ProfitResult = coroutineScope {
        val thisMonthData = getOneMonthComponentDiamonds(year, month)
        val lastMonthDate = LocalDate(year, month, 1).minus(1, DateTimeUnit.MONTH)
        val lastMonthData = getOneMonthComponentDiamonds(
            lastMonthDate.year,
            lastMonthDate.month.number
        )

        val thisMonthProfit = calculateProfit(
            thisMonthData.moduleDiamonds,
            thisMonthData.moduleNames
        )
        val lastMonthProfit = calculateProfit(
            lastMonthData.moduleDiamonds,
            lastMonthData.moduleNames
        )

        ProfitResult(
            thisMonth = thisMonthProfit,
            lastMonth = lastMonthProfit,
            thisMonthPeriod = createProfitPeriod(year, month, thisMonthData.dataThroughDate, today),
            lastMonthPeriod = createProfitPeriod(
                lastMonthDate.year,
                lastMonthDate.month.number,
                lastMonthData.dataThroughDate,
                today
            ),
            lastMonthAllocation = calculateAllocation(accountKey, lastMonthProfit)
        )
    }

    suspend fun calculateAllocation(
        accountKey: String,
        profitData: ProfitData
    ): ProfitAllocationSummary {
        if (accountKey.isBlank()) return ProfitAllocationSummary()
        return calculateProfitAllocation(
            profitData = profitData,
            people = profitSharingRepository.getPeople(accountKey),
            ownerships = profitSharingRepository.getOwnerships(accountKey)
        )
    }

    private suspend fun getOneMonthComponentDiamonds(year: Int, month: Int): MonthDiamondData =
        coroutineScope {
            val resList = when (val resources = getResourceListUseCase("pe", onlineOnly = true)) {
                is NetworkState.Success -> resources.data ?: emptyList()
                is NetworkState.Error -> emptyList()
            }

            val dateRange = monthDateRange(year, month)

            val componentData = resList.map { res ->
                async {
                    val result = analyzeRepository.getDayDetail(
                        platform = "pe",
                        category = "pe",
                        startDate = formatDateParam(dateRange.first),
                        endDate = formatDateParam(dateRange.second),
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

private data class ComponentDiamondData(
    val itemId: String,
    val moduleName: String,
    val diamonds: Double,
    val dataThroughDate: LocalDate?
)

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

private fun formatDateParam(date: LocalDate): String = date.toString().replace("-", "")

private fun parseDateParamOrNull(value: String): LocalDate? {
    if (value.length != 8) return null
    val isoDate = "${value.substring(0, 4)}-${value.substring(4, 6)}-${value.substring(6, 8)}"
    return runCatching { LocalDate.parse(isoDate) }.getOrNull()
}
