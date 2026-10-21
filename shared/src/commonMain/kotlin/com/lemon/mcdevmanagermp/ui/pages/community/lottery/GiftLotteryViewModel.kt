package com.lemon.mcdevmanagermp.ui.pages.community.lottery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lemon.mcdevmanagermp.data.api.CommentApi
import com.lemon.mcdevmanagermp.data.api.GiftCommentReplyClient
import com.lemon.mcdevmanagermp.data.common.NetworkState
import com.lemon.mcdevmanagermp.data.repository.ResourceRepositoryImpl
import com.lemon.mcdevmanagermp.data.vo.netease.comment.CommentListVO
import com.lemon.mcdevmanagermp.domain.lottery.CommentLotteryLoader
import com.lemon.mcdevmanagermp.domain.lottery.GiftLottery
import com.lemon.mcdevmanagermp.domain.lottery.GiftLotteryCriteria
import com.lemon.mcdevmanagermp.domain.lottery.GiftLotteryResult
import com.lemon.mcdevmanagermp.domain.lottery.GiftReportAudit
import com.lemon.mcdevmanagermp.domain.lottery.GiftReportImport
import com.lemon.mcdevmanagermp.domain.lottery.GiftCommentReplyBatch
import com.lemon.mcdevmanagermp.domain.lottery.GiftReplyLedger
import com.lemon.mcdevmanagermp.domain.lottery.GiftReplySender
import com.lemon.mcdevmanagermp.domain.lottery.GiftReplyStatus
import com.lemon.mcdevmanagermp.domain.lottery.GiftReplyText
import com.lemon.mcdevmanagermp.domain.lottery.PreferencesGiftReplyLedger
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
    val reportSummary: GiftReportAudit? = null,
    val replyTemplate: String = "",
    val replyEnabled: Boolean = false,
    val replyRunning: Boolean = false,
    val replyProgress: String = "",
    val replyNotice: String? = null,
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
    private val replyLedger: GiftReplyLedger = PreferencesGiftReplyLedger(),
    private val replySenderFactory: () -> GiftReplySender = { GiftCommentReplyClient(CookiesStore.getAllCookiesMap()) },
    private val replyIntervalMillis: Long = 1500,
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
    private var replyJob: Job? = null

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
        replyJob?.cancel()
        resultSession = null
        mutableState.update { it.copy(busy = false, progress = "", result = null, error = null, message = null,
            reportSummary = null, replyEnabled = false, replyRunning = false, replyProgress = "", replyNotice = null) }
    }

    fun edit(change: (GiftLotteryForm) -> GiftLotteryForm) {
        if (state.value.replyRunning) { error("请先停止批量回复再修改条件"); return }
        val form = change(state.value.form)
        if (form == state.value.form) return
        invalidate()
        mutableState.update { it.copy(form = form) }
    }

    fun loadMods() {
        if (state.value.replyRunning) return
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
        if (state.value.replyRunning) { error("请先停止批量回复再重新抽奖"); return }
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
        if (state.value.replyRunning) { error("请先停止批量回复"); return }
        val result = exportSnapshot() ?: return
        runCatching { result.replacePurchased(uids) }
            .onSuccess { replacement -> mutableState.update { it.copy(result = replacement, error = null,
                message = "已替换 ${uids.size} 人，其他中奖者不变；请重新导出 CSV") } }
            .onFailure { error(it.message ?: "补抽失败") }
    }

    fun importGiftReport(text: String, snapshot: GiftLotteryResult) {
        if (state.value.replyRunning) { error("请先停止批量回复再导入报告"); return }
        if (!validateSnapshot(snapshot)) return
        runCatching { GiftReportImport.apply(snapshot, text) }
            .onSuccess { application ->
                if (!validateSnapshot(snapshot)) return@onSuccess
                val audit = application.audit
                mutableState.update { it.copy(result = application.result, reportSummary = audit, error = null,
                    message = if (audit.replacedFailureUids.isEmpty()) "报告已读取，没有可补抽的明确失败名额，中奖者保持不变"
                    else "已按报告自动补抽 ${audit.replacedFailureUids.size} 人，成功和其他名额保持不变；请导出新名单 CSV") }
                startCommentReplies()
            }
            .onFailure { error(it.message ?: "赠送报告处理失败，原名单未修改") }
    }

    fun setReplyTemplate(text: String) {
        if (state.value.replyRunning) return
        mutableState.update { it.copy(replyTemplate = text, replyEnabled = false,
            replyNotice = if (it.replyEnabled) "文案已修改，请预览后重新启用自动回复" else null) }
    }

    fun setAutoReply(enabled: Boolean) {
        if (state.value.replyRunning) return
        if (enabled && !validateReplyText()) return
        mutableState.update { it.copy(replyEnabled = enabled, replyNotice = null) }
        if (enabled) startCommentReplies()
    }

    private fun validateReplyText(): Boolean = runCatching {
        val result = state.value.result ?: throw IllegalStateException("请先完成抽奖")
        val name = state.value.mods.find { it.id == result.criteria.rewardItemId }?.name.orEmpty()
        result.winners().forEach { GiftReplyText.render(state.value.replyTemplate, it, name, result.criteria.rewardItemId) }
    }.onFailure { error(it.message ?: "回复文案不正确") }.isSuccess

    fun retryCommentReplies() {
        if (state.value.replyRunning || !validateReplyText()) return
        mutableState.update { it.copy(replyEnabled = true) }
        startCommentReplies(retryRejected = true)
    }

    fun stopCommentReplies() {
        mutableState.update { it.copy(replyEnabled = false, replyNotice = "正在停止；已发出的请求可能需要核对") }
        replyJob?.cancel()
    }

    private fun startCommentReplies(retryRejected: Boolean = false) {
        if (!state.value.replyEnabled || state.value.replyRunning) return
        val result = state.value.result ?: return
        if (!result.allGiftsConfirmed()) return
        if (!retryRejected && result.winners().all { result.replyRecords[it.commentId]?.status in
            setOf(GiftReplyStatus.SUCCESS, GiftReplyStatus.SKIPPED_EXISTING, GiftReplyStatus.SKIPPED_MISSING, GiftReplyStatus.UNCERTAIN) }) return
        if (!validateSnapshot(result) || !validateReplyText()) return
        val expectedRevision = revision
        val expectedSession = sessionVersion()
        val accountId = expectedSession.first ?: run { error("请先登录账号"); return }
        val template = state.value.replyTemplate
        val rewardName = state.value.mods.find { it.id == result.criteria.rewardItemId }?.name.orEmpty()
        fun sameContext() = revision == expectedRevision && sessionVersion() == expectedSession
        mutableState.update { it.copy(replyRunning = true, replyProgress = "正在核对源评论…", replyNotice = null) }
        replyJob = viewModelScope.launch {
            try {
                val batch = GiftCommentReplyBatch(replyLedger, { criteria ->
                    CommentLotteryLoader({ item, start, end, offset, span ->
                        check(sameContext()) { "账号或名单已变化" }
                        fetchPage(item, start, end, offset, span)
                    }).load(criteria)
                }, replySenderFactory, replyIntervalMillis)
                batch.run(result, template, rewardName, accountId,
                    canContinue = { sameContext() && state.value.replyEnabled }, retryRejected = retryRejected,
                    onRecord = { record -> if (sameContext()) mutableState.update { current ->
                        current.copy(result = current.result?.let { it.copy(replyRecords = it.replyRecords + (record.commentId to record)) })
                    } },
                    onProgress = { done, total -> if (sameContext()) mutableState.update { it.copy(replyProgress = "已处理 $done / $total 条源评论") } })
                if (sameContext()) {
                    val records = state.value.result?.replyRecords.orEmpty().values
                    val sent = records.count { it.status == GiftReplyStatus.SUCCESS }
                    val pending = records.count { it.status == GiftReplyStatus.UNCERTAIN || it.status == GiftReplyStatus.RESERVED }
                    mutableState.update { it.copy(replyRunning = false, replyNotice = "回复处理结束：成功 $sent 条，跳过 ${records.size - sent - pending} 条，待核对 $pending 条") }
                }
            } catch (exception: CancellationException) {
                if (sameContext()) mutableState.update { it.copy(replyRunning = false, replyEnabled = false, replyNotice = "已停止回复；不确定请求不会自动重发") }
                throw exception
            } catch (exception: Exception) {
                if (sameContext()) mutableState.update { it.copy(replyRunning = false, replyEnabled = false,
                    replyNotice = exception.message ?: "回复已停止，请核对状态后继续") }
            }
        }
    }

    /** 只记录本地成功导出的行；不代表已赠送或已扣费。 */
    fun recordCsvExport(snapshot: GiftLotteryResult, uids: Collection<String>) {
        if (validateSnapshot(snapshot)) mutableState.update {
            it.copy(result = snapshot.copy(exportedUids = snapshot.exportedUids + uids))
        }
    }
}
