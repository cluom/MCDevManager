package com.lemon.mcdevmanagermp.domain.lottery

import com.lemon.mcdevmanagermp.data.vo.netease.comment.CommentData
import com.lemon.mcdevmanagermp.platform.GiftReplyPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class GiftReplyStatus { RESERVED, SUCCESS, SKIPPED_EXISTING, SKIPPED_MISSING, REJECTED, UNCERTAIN }

@Serializable
data class GiftReplyRecord(val commentId: String, val uid: String, val sourceItemId: String,
                           val content: String, val status: GiftReplyStatus, val note: String = "")

interface GiftReplyLedger {
    suspend fun get(accountId: Long, commentId: String): GiftReplyRecord?
    suspend fun put(accountId: Long, record: GiftReplyRecord)
}

class PreferencesGiftReplyLedger : GiftReplyLedger {
    private val preferences by lazy { GiftReplyPreferences() }
    private val json = Json { ignoreUnknownKeys = true }
    private fun key(account: Long, comment: String): String {
        require(validLotteryId(comment)) { "原评论 ID 无效" }
        return "a${account}_c$comment"
    }
    override suspend fun get(accountId: Long, commentId: String): GiftReplyRecord? = withContext(Dispatchers.Default) {
        preferences.get(key(accountId, commentId))?.let { json.decodeFromString<GiftReplyRecord>(it) }
    }
    override suspend fun put(accountId: Long, record: GiftReplyRecord) = withContext(Dispatchers.Default) {
        preferences.put(key(accountId, record.commentId), json.encodeToString(record))
    }
}

data class GiftReplyOutcome(val status: GiftReplyStatus, val note: String = "")

interface GiftReplySender {
    suspend fun send(commentId: String, content: String): GiftReplyOutcome
    fun close() {}
}

object GiftReplyText {
    fun render(template: String, entry: GiftLotteryEntry, rewardName: String, rewardId: String): String {
        require(template.isNotBlank()) { "请先填写自动回复文案" }
        val content = Regex("\\{(昵称|赠送模组|模组ID)\\}").replace(template) { match ->
            when (match.groupValues[1]) {
                "昵称" -> entry.nickname.ifBlank { "玩家" }
                "赠送模组" -> rewardName.ifBlank { rewardId }
                else -> rewardId
            }
        }.trim()
        require(content.length in 1..500) { "展开后的每条回复须为 1～500 个字符（本地限制）" }
        return content
    }
}

/** 同一进程一次只执行一个批次；持久记录防止重启后静默重发。 */
private val giftReplyBatchMutex = Mutex()

class GiftCommentReplyBatch(
    private val ledger: GiftReplyLedger,
    private val fetchComments: suspend (GiftLotteryCriteria) -> List<CommentData>,
    private val senderFactory: () -> GiftReplySender,
    private val intervalMillis: Long = 1500,
) {
    suspend fun run(
        result: GiftLotteryResult, template: String, rewardName: String, accountId: Long,
        canContinue: () -> Boolean, retryRejected: Boolean = false,
        onRecord: (GiftReplyRecord) -> Unit, onProgress: (Int, Int) -> Unit,
    ) {
        require(result.allGiftsConfirmed()) { "须等当前全部中奖名额的报告确认成功后才能回复" }
        val entries = result.winners()
        require(entries.all { validLotteryId(it.commentId) && it.itemId == result.criteria.sourceItemId }) { "源评论身份无效" }
        require(entries.map { it.commentId }.distinct().size == entries.size) { "源评论 ID 重复，不能批量回复" }
        val messages = entries.associate { it.commentId to GiftReplyText.render(template, it, rewardName, result.criteria.rewardItemId) }
        check(giftReplyBatchMutex.tryLock()) { "已有批量回复在运行，请稍后再试" }
        var sender: GiftReplySender? = null
        fun checkCurrent() { if (!canContinue()) throw CancellationException("回复已停止或账号已变化") }
        try {
            checkCurrent()
            val comments = fetchComments(result.criteria).associateBy { it.id }
            checkCurrent()
            val activeSender = senderFactory().also { sender = it }
            checkCurrent()
            entries.forEachIndexed { index, entry ->
                checkCurrent()
                val previous = ledger.get(accountId, entry.commentId)
                checkCurrent()
                check(previous == null || (previous.uid == entry.uid && previous.sourceItemId == entry.itemId)) { "本地回复记录与源评论身份不一致，请人工核对" }
                val comment = comments[entry.commentId]
                val base = GiftReplyRecord(entry.commentId, entry.uid, entry.itemId, messages.getValue(entry.commentId), GiftReplyStatus.RESERVED)
                val skip = when {
                    previous?.status == GiftReplyStatus.SUCCESS || previous?.status == GiftReplyStatus.SKIPPED_EXISTING -> previous
                    comment == null || comment.uid != entry.uid || comment.iid != entry.itemId -> base.copy(status = GiftReplyStatus.SKIPPED_MISSING, note = "原评论不存在或身份不匹配")
                    !comment.replyId.isNullOrBlank() && comment.replyId != "0" -> base.copy(status = GiftReplyStatus.SKIPPED_EXISTING, note = "源评论已有回复，未覆盖")
                    previous?.status == GiftReplyStatus.RESERVED || previous?.status == GiftReplyStatus.UNCERTAIN -> previous.copy(status = GiftReplyStatus.UNCERTAIN, note = "上次结果待核对，未重复发送")
                    previous?.status == GiftReplyStatus.REJECTED && !retryRejected -> previous
                    else -> null
                }
                if (skip != null) {
                    if (skip.status == GiftReplyStatus.SKIPPED_EXISTING) ledger.put(accountId, skip)
                    onRecord(skip); onProgress(index + 1, entries.size)
                    return@forEachIndexed
                }
                ledger.put(accountId, base)
                onRecord(base)
                checkCurrent()
                val outcome = try {
                    activeSender.send(entry.commentId, base.content)
                } catch (exception: CancellationException) {
                    val uncertain = base.copy(status = GiftReplyStatus.UNCERTAIN, note = "请求已发出但被停止，请核对源评论")
                    withContext(NonCancellable) { ledger.put(accountId, uncertain) }
                    onRecord(uncertain)
                    throw exception
                } catch (_: Exception) {
                    GiftReplyOutcome(GiftReplyStatus.UNCERTAIN, "网络响应不确定，请核对源评论，未自动重发")
                }
                check(outcome.status in setOf(GiftReplyStatus.SUCCESS, GiftReplyStatus.REJECTED, GiftReplyStatus.UNCERTAIN))
                val record = base.copy(status = outcome.status, note = outcome.note.take(200))
                withContext(NonCancellable) { ledger.put(accountId, record) }
                onRecord(record); onProgress(index + 1, entries.size)
                checkCurrent()
                check(outcome.status == GiftReplyStatus.SUCCESS) { outcome.note.ifBlank { "回复未确认成功，批次已停止" } }
                if (index < entries.lastIndex) delay(intervalMillis)
            }
        } finally {
            sender?.close()
            giftReplyBatchMutex.unlock()
        }
    }
}
