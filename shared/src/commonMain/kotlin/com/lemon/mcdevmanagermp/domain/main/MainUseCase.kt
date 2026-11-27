package com.lemon.mcdevmanagermp.domain.main

import com.lemon.mcdevmanagermp.data.common.NetworkState
import com.lemon.mcdevmanagermp.data.consts.CookiesExpiredException
import com.lemon.mcdevmanagermp.data.consts.LoginException
import com.lemon.mcdevmanagermp.data.vo.netease.analyze.ResAnalyzeData
import com.lemon.mcdevmanagermp.data.vo.netease.income.IncomeVO
import com.lemon.mcdevmanagermp.data.vo.netease.user.LevelInfoVO
import com.lemon.mcdevmanagermp.data.vo.netease.user.OverviewVO
import com.lemon.mcdevmanagermp.data.vo.netease.user.UserInfoVO
import com.lemon.mcdevmanagermp.domain.income.IncomeRepository
import com.lemon.mcdevmanagermp.data.consts.enums.PriceTypeEnum
import com.lemon.mcdevmanagermp.data.repository.EcoFeeRecordRepository
import com.lemon.mcdevmanagermp.data.repository.isEcoFeeReverseSupported
import com.lemon.mcdevmanagermp.data.repository.parseMonthKey
import com.lemon.mcdevmanagermp.utils.calculateProfit
import com.lemon.mcdevmanagermp.utils.ECO_FEE_RATE
import com.lemon.mcdevmanagermp.domain.analyze.AnalyzeRepository
import com.lemon.mcdevmanagermp.domain.profitsharing.ProfitAllocationSummary
import com.lemon.mcdevmanagermp.domain.profitsharing.ProfitSharingRepository
import com.lemon.mcdevmanagermp.domain.profitsharing.calculateProfitAllocation
import com.lemon.mcdevmanagermp.domain.resource.GetResourceListUseCase
import com.lemon.mcdevmanagermp.domain.user.UserRepository
import com.lemon.mcdevmanagermp.utils.ProfitData
import com.lemon.mcdevmanagermp.utils.Logger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

/** 计算周期是否已截止：该月已完整结束（当前自然月之前的月份） */
internal fun isMonthClosed(month: ProfitMonth, today: LocalDate): Boolean =
    month < ProfitMonth(today.year, today.month.number)

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
    val dataThroughDate: LocalDate?,
    val emeraldModDiamonds: Map<String, Double> = emptyMap(),
    val isComplete: Boolean = true
)

private data class ComponentDiamondData(
    val itemId: String,
    val moduleName: String,
    val diamonds: Double,
    val dataThroughDate: LocalDate?,
    val isComplete: Boolean = true
)

class MainUseCase(
    private val userRepository: UserRepository,
    private val analyzeRepository: AnalyzeRepository,
    private val getResourceListUseCase: GetResourceListUseCase,
    private val incomeRepository: IncomeRepository? = null,
    private val profitSharingRepository: ProfitSharingRepository? = null
) {
    private val ecoFeeRecordRepository get() = EcoFeeRecordRepository.INSTANCE
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
        // 同一轮先补齐上期费率，避免首页先算完、后台同步完成后却仍显示 30%。
        val previousMonths = listOfNotNull(thisMonth, lastMonthResult?.first, nextMonthResult?.first)
            .map { it.shift(-1) }.toSet()
        if (accountKey.isNotBlank()) {
            syncEcoFeeRecords(accountKey, bills.filter {
                it.platform == "pe" && parseMonthKey(it.dataMonth) in previousMonths
            }, today)
        }
        val rates = if (accountKey.isBlank()) emptyMap() else
            ecoFeeRecordRepository.getRated(accountKey).associate { it.dataMonth to it.ecoFeeRate }
        suspend fun profit(target: ProfitMonth, data: MonthDiamondData): ProfitData {
            val estimate = calculateProfit(data.moduleDiamonds, data.moduleNames, ECO_FEE_RATE)
                .copy(emeraldModDiamonds = data.emeraldModDiamonds, isFlowComplete = data.isComplete)
            val snapshot = if (accountKey.isNotBlank() && isMonthClosed(target, today)) {
                ecoFeeRecordRepository.getSnapshot(accountKey, target)
                    ?: estimate.also { ecoFeeRecordRepository.saveSnapshot(accountKey, target, it) }
            } else estimate
            return applyUpstreamSettlement(target, estimateWithPreviousPeriod(target, snapshot, rates), bills)
        }
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
        if (incomeRepository == null) return@coroutineScope computeEstimatedMonthProfit(year, month)
        val billsDeferred = async { loadSettlementBills() }
        val data = getOneMonthComponentDiamonds(year, month)
        val (bills, billError) = billsDeferred.await()
        val estimate = calculateProfit(data.moduleDiamonds, data.moduleNames, ECO_FEE_RATE)
            .copy(emeraldModDiamonds = data.emeraldModDiamonds, isFlowComplete = data.isComplete)
        applyUpstreamSettlement(ProfitMonth(year, month), estimate, bills)
    }

    suspend fun computeEstimatedMonthProfit(year: Int, month: Int): ProfitData {
        val data = getOneMonthComponentDiamonds(year, month)
        return calculateProfit(data.moduleDiamonds, data.moduleNames, ECO_FEE_RATE)
            .copy(emeraldModDiamonds = data.emeraldModDiamonds, isFlowComplete = data.isComplete)
    }

    private suspend fun loadSettlementBills(): Pair<List<IncomeVO>, String?> =
        when (val result = requireNotNull(incomeRepository).getIncome("all")) {
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
            val normalResources = async {
                getResourceListUseCase("pe", onlineOnly = true, excludePrerequisites = true)
            }
            val lobbyResources = async { analyzeRepository.getLobbyIncomeResources() }
            val resourceResult = normalResources.await()
            val lobbyResult = lobbyResources.await()
            val resList = when (val resources = resourceResult) {
                is NetworkState.Success -> resources.data ?: emptyList()
                is NetworkState.Error -> emptyList()
            }
            val lobbyResList = when (val resources = lobbyResult) {
                is NetworkState.Success -> resources.data?.items.orEmpty()
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
                        ComponentDiamondData(res.itemId, res.itemName, 0.0, null, false)
                    }
                }
            }.map { it.await() }

            val lobbyData = lobbyResList.map { res ->
                async {
                    // 查询使用商品 ID，分账仍归属于作品 ID，不按可能重名的作品名分组。
                    val goodsResult = analyzeRepository.getLobbyGoodsList(res.itemId)
                    val goodsIds = when (val goods = goodsResult) {
                        is NetworkState.Success -> goods.data?.goods.orEmpty()
                            .map { it.goodsId }.filter(String::isNotBlank).distinct()
                        is NetworkState.Error -> emptyList()
                    }
                    if (goodsIds.isEmpty()) {
                        return@async ComponentDiamondData(res.itemId, res.itemName, 0.0, null, goodsResult is NetworkState.Success)
                    }
                    val result = analyzeRepository.getDayDetail(
                        platform = "pe",
                        category = "pe",
                        startDate = startDateParam,
                        endDate = endDateParam,
                        itemListStr = goodsIds.joinToString(","),
                        isLobby = true
                    )
                    if (result is NetworkState.Success) {
                        val goodsToOwner = goodsIds.associateWith { res.itemId }
                        val dayData = result.data?.data.orEmpty().filter { it.iid in goodsToOwner }
                        ComponentDiamondData(
                            itemId = res.itemId,
                            moduleName = res.itemName,
                            diamonds = aggregateGoodsDiamondsByOwner(dayData, goodsToOwner)[res.itemId] ?: 0.0,
                            dataThroughDate = dayData.mapNotNull { parseDateParamOrNull(it.dateId) }.maxOrNull()
                        )
                    } else {
                        ComponentDiamondData(res.itemId, res.itemName, 0.0, null, false)
                    }
                }
            }.map { it.await() }

            val resById = resList.associateBy { it.itemId }
            val (diamondGoods, emeraldGoods) = lobbyData.partition {
                resById[it.itemId]?.getPriceType() == PriceTypeEnum.DIAMOND
            }
            val componentData = (normalData + diamondGoods)
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
                dataThroughDate = componentData.mapNotNull { it.dataThroughDate }.maxOrNull(),
                emeraldModDiamonds = emeraldGoods.associate { it.itemId to it.diamonds },
                isComplete = resourceResult is NetworkState.Success && lobbyResult is NetworkState.Success &&
                    (normalData + diamondGoods).all { it.isComplete }
            )
        }

    internal suspend fun syncEcoFeeRecords(
        accountKey: String,
        settlements: List<IncomeVO>,
        today: LocalDate
    ): List<ProfitMonth> {
        if (accountKey.isBlank()) return emptyList()
        val pending = settlements.mapNotNull { settlement ->
            val month = parseMonthKey(settlement.dataMonth) ?: return@mapNotNull null
            if (!isEcoFeeReverseSupported(month)) return@mapNotNull null
            if (ecoFeeRecordRepository.isRated(accountKey, month)) null else month to settlement
        }
        if (pending.isEmpty()) return emptyList()

        // 逐月串行：同一账号写同一张表，且每月的流水请求本身已是并发发起的
        return pending.mapNotNull { (month, settlement) ->
            val cached = ecoFeeRecordRepository.getSnapshot(accountKey, month)
            val profitData = cached ?: computeEstimatedMonthProfit(month.year, month.month)
            // 已截止月份实时计算后补存快照，后续无需重复请求
            if (cached == null && isMonthClosed(month, today)) {
                ecoFeeRecordRepository.saveSnapshot(accountKey, month, profitData)
            }
            if (ecoFeeRecordRepository.saveReverseResult(accountKey, month, profitData, settlement)) {
                month
            } else {
                null
            }
        }
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
    goods: Map<String, Double>
): Map<String, Double> = buildMap {
    putAll(normal)
    goods.forEach { (name, diamonds) -> put(name, (get(name) ?: 0.0) + diamonds) }
}

private fun formatDateParam(date: LocalDate): String = date.toString().replace("-", "")

private fun parseDateParamOrNull(value: String): LocalDate? {
    if (value.length != 8) return null
    val isoDate = "${value.substring(0, 4)}-${value.substring(4, 6)}-${value.substring(6, 8)}"
    return runCatching { LocalDate.parse(isoDate) }.getOrNull()
}

/**
 * 把商品维度的销售明细按所属作品累加：作品键 → 该作品下全部商品的钻石之和。
 *
 * @param rows 商品口径销售明细（每行的 [ResAnalyzeData.iid] 是商品 ID）
 * @param goodsToOwner 商品 ID → 作品键（分账使用作品 ID）
 */
internal fun aggregateGoodsDiamondsByOwner(
    rows: List<ResAnalyzeData>,
    goodsToOwner: Map<String, String>
): Map<String, Double> =
    rows.mapNotNull { row ->
        goodsToOwner[row.iid]?.let { it to row.diamond * (1 - row.refundRate) }
    }.groupBy({ it.first }, { it.second })
        .mapValues { (_, values) -> values.sum() }
