package com.lemon.mcdevmanagermp.ui.pages.community.lottery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lemon.mcdevmanagermp.data.api.CommentApi
import com.lemon.mcdevmanagermp.data.common.NetworkState
import com.lemon.mcdevmanagermp.data.repository.ResourceRepositoryImpl
import com.lemon.mcdevmanagermp.data.vo.netease.comment.CommentListVO
import com.lemon.mcdevmanagermp.domain.lottery.CommentLotteryLoader
import com.lemon.mcdevmanagermp.domain.lottery.GiftLottery
import com.lemon.mcdevmanagermp.domain.lottery.GiftLotteryCriteria
import com.lemon.mcdevmanagermp.domain.lottery.GiftLotteryResult
import com.lemon.mcdevmanagermp.domain.lottery.LotteryUidImport
import com.lemon.mcdevmanagermp.domain.lottery.parseLotteryDate
import com.lemon.mcdevmanagermp.domain.lottery.validLotteryId
import com.lemon.mcdevmanagermp.domain.resource.GetResourceListUseCase
import com.lemon.mcdevmanagermp.ui.components.ModSelectOption
import com.lemon.mcdevmanagermp.utils.CookiesStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.random.Random
import kotlin.time.Clock

data class GiftLotteryForm(
    val sourceItemId: String = "4688538944207380371",
    val rewardItemId: String = "4690141251535232006",
    val startDate: String = Clock.System.now().toLocalDateTime(TimeZone.of("Asia/Shanghai")).date.minus(30, DateTimeUnit.DAY).toString(),
    val endDate: String = Clock.System.now().toLocalDateTime(TimeZone.of("Asia/Shanghai")).date.toString(),
    val stars: Set<Int> = setOf(5),
    val tag: String = "",
    val includeText: String = "",
    val excludeText: String = "",
    val excludedText: String = "",
    val winnerCount: String = "200",
    val seed: String = "",
) {
    fun criteria() = GiftLotteryCriteria(
        sourceItemId.trim(), rewardItemId.trim(), startDate.trim(), endDate.trim(), stars,
        tag.trim(), includeText.trim(), excludeText.trim(),
        winnerCount.trim().toIntOrNull() ?: throw IllegalArgumentException("抽取人数须为整数"),
        LotteryUidImport.fromText(excludedText),
    ).also { it.validate() }
}

data class GiftLotteryState(
    val form: GiftLotteryForm = GiftLotteryForm(),
    val busy: Boolean = false,
    val progress: String = "",
    val error: String? = null,
    val message: String? = null,
    val result: GiftLotteryResult? = null,
    val mods: List<ModSelectOption> = emptyList(),
    val modsLoading: Boolean = false,
    val modsLoaded: Boolean = false,
    val modsError: String? = null,
)

class GiftLotteryViewModel(
    private val fetchPage: suspend (String, String, String, Int, Int) -> CommentListVO = { item, start, end, offset, span ->
        val response = CommentApi.INSTANCE.getCommentList(start = offset, span = span, key = item, startDate = start, endDate = end)
        check(response.status == "ok" || response.status == "success") {
            if (response.status == "no_login") "登录已失效，请重新登录后重试" else "评论读取失败，未进行抽奖"
        }
        response.data ?: throw IllegalStateException("评论接口没有返回数据")
    },
    private val sessionVersion: () -> Pair<Long?, Long> = CookiesStore::accountSessionVersion,
    sessionVersions: Flow<Pair<Long?, Long>> = CookiesStore.accountSessionVersions(),
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val fetchMods: suspend () -> List<ModSelectOption> = {
        when (val response = GetResourceListUseCase(ResourceRepositoryImpl.INSTANCE)("pe")) {
            is NetworkState.Success -> response.data.orEmpty().map { ModSelectOption(it.itemId, it.itemName) }
            is NetworkState.Error -> throw IllegalStateException("作品列表读取失败：${response.msg}")
        }
    },
) : ViewModel() {
    private val mutableState = MutableStateFlow(GiftLotteryState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var revision = 0
    private var session = sessionVersion()
    private var resultSession: Pair<Long?, Long>? = null
    private var modsJob: Job? = null
    private var modsRevision = 0
    private var modsRequested = false

    init {
        viewModelScope.launch {
            sessionVersions.collect { current ->
                if (session != current) {
                    session = current
                    invalidate()
                    modsRevision++
                    modsJob?.cancel()
                    mutableState.value = GiftLotteryState(
                        form = GiftLotteryForm(sourceItemId = "", rewardItemId = ""),
                        error = "账号已切换，旧名单和模组选择已清空，请重新选择后抽奖",
                    )
                    if (modsRequested) loadMods()
                }
            }
        }
    }

    private fun invalidate() {
        revision++
        job?.cancel()
        resultSession = null
        mutableState.update { it.copy(busy = false, progress = "", result = null, error = null, message = null) }
    }

    fun edit(change: (GiftLotteryForm) -> GiftLotteryForm) {
        val form = change(state.value.form)
        if (form == state.value.form) return
        invalidate()
        mutableState.update { it.copy(form = form) }
    }

    fun loadMods() {
        modsRequested = true
        val expectedRevision = ++modsRevision
        val expectedSession = sessionVersion()
        modsJob?.cancel()
        mutableState.update { it.copy(mods = emptyList(), modsLoading = true, modsLoaded = false, modsError = null) }
        modsJob = viewModelScope.launch {
            try {
                val mods = fetchMods().filter { validLotteryId(it.id) }.distinctBy { it.id }
                    .sortedWith(compareBy<ModSelectOption> { it.name }.thenBy { it.id })
                if (expectedRevision != modsRevision || expectedSession != sessionVersion()) return@launch
                val ids = mods.map { it.id }.toSet()
                edit { it.copy(sourceItemId = it.sourceItemId.takeIf(ids::contains).orEmpty(),
                    rewardItemId = it.rewardItemId.takeIf(ids::contains).orEmpty()) }
                mutableState.update { it.copy(mods = mods, modsLoading = false, modsLoaded = true) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (expectedRevision == modsRevision && expectedSession == sessionVersion()) {
                    mutableState.update { it.copy(modsLoading = false,
                        modsError = exception.message ?: "作品列表读取失败，请重试") }
                }
            }
        }
    }

    fun selectMod(id: String, source: Boolean) {
        if (!state.value.modsLoaded || state.value.mods.none { it.id == id }) {
            error("请从当前账号的作品列表中选择模组")
            return
        }
        edit { if (source) it.copy(sourceItemId = id) else it.copy(rewardItemId = id) }
    }

    fun selectDate(date: LocalDate, start: Boolean) {
        val today = Clock.System.now().toLocalDateTime(TimeZone.of("Asia/Shanghai")).date
        val form = state.value.form
        val startDate = if (start) date else parseLotteryDate(form.startDate)
        val endDate = if (start) parseLotteryDate(form.endDate) else date
        if (startDate > endDate || endDate > today) {
            error("开始日期不能晚于结束日期，且不能选择未来日期")
            return
        }
        edit { if (start) it.copy(startDate = date.toString()) else it.copy(endDate = date.toString()) }
    }

    fun cancel() {
        invalidate()
        mutableState.update { it.copy(message = "已取消；未使用部分评论抽奖") }
    }

    fun error(message: String) { mutableState.update { it.copy(error = message, message = null) } }
    fun message(message: String) { mutableState.update { it.copy(message = message, error = null) } }

    fun importExcludedCsv(text: String) {
        runCatching { LotteryUidImport.fromCsv(text) + LotteryUidImport.fromText(state.value.form.excludedText) }
            .onSuccess { ids ->
                edit { it.copy(excludedText = ids.sorted().joinToString("\n")) }
                message("已合并 ${ids.size} 个排除 UID；这不代表自动核验了已购记录")
            }
            .onFailure { error(it.message ?: "名单导入失败") }
    }

    fun draw() {
        val form = state.value.form
        if (modsRequested && (!state.value.modsLoaded || state.value.modsLoading ||
            state.value.mods.none { it.id == form.sourceItemId } || state.value.mods.none { it.id == form.rewardItemId })) {
            error("请先加载作品列表，并选择来源模组与赠送模组")
            return
        }
        val criteria: GiftLotteryCriteria
        val seed: Long
        try {
            criteria = form.criteria()
            seed = if (form.seed.isBlank()) Random.Default.nextLong()
            else form.seed.trim().toLongOrNull() ?: throw IllegalArgumentException("种子须为 64 位整数，留空自动生成")
        } catch (exception: IllegalArgumentException) {
            error(exception.message ?: "条件不正确")
            return
        }
        invalidate()
        val expectedRevision = revision
        val expectedSession = sessionVersion()
        mutableState.update { it.copy(busy = true, progress = "准备读取评论…") }
        job = viewModelScope.launch {
            try {
                fun checkSession() = check(sessionVersion() == expectedSession) {
                    "账号已切换，本次数据已废弃，请重新抽奖"
                }
                val loader = CommentLotteryLoader({ item, start, end, offset, span ->
                    checkSession()
                    val response = fetchPage(item, start, end, offset, span)
                    checkSession()
                    response
                })
                val comments = loader.load(criteria) { progress ->
                    if (revision == expectedRevision) mutableState.update { it.copy(progress = progress) }
                }
                val result = withContext(computeDispatcher) { GiftLottery.draw(comments, criteria, seed) }
                checkSession()
                if (revision != expectedRevision) return@launch
                resultSession = expectedSession
                mutableState.update { it.copy(busy = false, progress = "", result = result,
                    message = "已抽取 ${result.winnerUids.size} 人；未校验是否已购，可标记后补抽") }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (revision == expectedRevision) mutableState.update { it.copy(busy = false, progress = "",
                    result = null, error = exception.message ?: "读取失败，本次未抽奖") }
            }
        }
    }

    fun exportSnapshot(): GiftLotteryResult? {
        val result = state.value.result ?: return null
        return if (validateSnapshot(result)) result else null
    }

    fun validateSnapshot(expected: GiftLotteryResult): Boolean {
        if (sessionVersion() != resultSession || state.value.result !== expected || state.value.busy) {
            error("账号、条件或中奖名单已变化，请使用最新抽奖结果")
            return false
        }
        return true
    }

    fun replacePurchased(uids: Set<String>) {
        val result = exportSnapshot() ?: return
        runCatching { result.replacePurchased(uids) }
            .onSuccess { replacement -> mutableState.update { it.copy(result = replacement, error = null,
                message = "已替换 ${uids.size} 人，其他中奖者不变；请重新导出 CSV") } }
            .onFailure { error(it.message ?: "补抽失败") }
    }

    /** 只记录本地成功导出的行；不代表已赠送或已扣费。 */
    fun recordCsvExport(snapshot: GiftLotteryResult, uids: Collection<String>) {
        if (validateSnapshot(snapshot)) mutableState.update {
            it.copy(result = snapshot.copy(exportedUids = snapshot.exportedUids + uids))
        }
    }
}
