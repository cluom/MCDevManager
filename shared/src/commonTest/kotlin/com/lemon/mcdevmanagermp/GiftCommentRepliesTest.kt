package com.lemon.mcdevmanagermp

import com.lemon.mcdevmanagermp.data.vo.netease.comment.CommentData
import com.lemon.mcdevmanagermp.domain.lottery.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Instant

internal class MemoryGiftReplyLedger : GiftReplyLedger {
    val records = mutableMapOf<Pair<Long, String>, GiftReplyRecord>()
    override suspend fun get(accountId: Long, commentId: String) = records[accountId to commentId]
    override suspend fun put(accountId: Long, record: GiftReplyRecord) { records[accountId to record.commentId] = record }
}

@OptIn(ExperimentalCoroutinesApi::class)
class GiftCommentRepliesTest {
    private fun comments() = (1..5).map { n -> CommentData(n.toString(), "模组评测", "1", "玩家$n",
        Instant.parse("2026-10-10T12:00:00Z").epochSeconds, "来源", "5", n.toString(), "好玩") }
    private fun result() = GiftLottery.draw(comments(), GiftLotteryCriteria("1", "2", "2026-10-01", "2026-10-21", winnerCount = 2), 7)
        .let { it.copy(deliveredUids = it.winnerUids.toSet()) }
    private fun sender(send: suspend (String, String) -> GiftReplyOutcome) = object : GiftReplySender {
        override suspend fun send(commentId: String, content: String) = send.invoke(commentId, content)
    }

    @Test fun partialGiftConfirmationCannotEvenStartPreflightOrSend() = runTest {
        var requests = 0
        val result = result().copy(deliveredUids = emptySet())
        val batch = GiftCommentReplyBatch(MemoryGiftReplyLedger(), { requests++; comments() },
            { sender { _, _ -> requests++; GiftReplyOutcome(GiftReplyStatus.SUCCESS) } }, 0)
        assertFailsWith<IllegalArgumentException> { batch.run(result, "已赠送", "奖品", 1, { true }, onRecord = {}, onProgress = { _, _ -> }) }
        assertEquals(0, requests)
    }

    @Test fun templatesExpandOnceAndRejectBlankOrOversizedContent() {
        val entry = result().winners().first().copy(nickname = "{模组ID}")
        assertEquals("你好{模组ID}，奖品2", GiftReplyText.render("你好{昵称}，{赠送模组}{模组ID}", entry, "奖品", "2"))
        assertFailsWith<IllegalArgumentException> { GiftReplyText.render(" ", entry, "奖品", "2") }
        assertFailsWith<IllegalArgumentException> { GiftReplyText.render("a".repeat(501), entry, "奖品", "2") }
    }

    @Test fun persistsReservationBeforeSendingAndNeverRepeatsSuccessAcrossBatches() = runTest {
        val result = result()
        val ledger = MemoryGiftReplyLedger()
        val sent = mutableListOf<String>()
        fun batch() = GiftCommentReplyBatch(ledger, { comments() }, { sender { id, _ ->
            assertEquals(GiftReplyStatus.RESERVED, ledger.records[1L to id]?.status)
            sent.add(id); GiftReplyOutcome(GiftReplyStatus.SUCCESS)
        } }, 0)
        batch().run(result, "{昵称}，{赠送模组}已赠送", "奖品", 1, { true }, onRecord = {}, onProgress = { _, _ -> })
        assertEquals(result.winners().map { it.commentId }, sent)
        batch().run(result, "新的文案不覆盖旧回复", "奖品", 1, { true }, onRecord = {}, onProgress = { _, _ -> })
        assertEquals(2, sent.size)
    }

    @Test fun skipsExistingRepliesAndMismatchedSourceIdentity() = runTest {
        val result = result()
        val first = result.winners()[0]
        val second = result.winners()[1]
        val fresh = comments().map { when (it.id) {
            first.commentId -> it.copy(replyId = "12345678")
            second.commentId -> it.copy(uid = "999")
            else -> it
        } }
        var sent = 0
        val records = mutableListOf<GiftReplyRecord>()
        GiftCommentReplyBatch(MemoryGiftReplyLedger(), { fresh }, { sender { _, _ -> sent++; GiftReplyOutcome(GiftReplyStatus.SUCCESS) } }, 0)
            .run(result, "已赠送", "奖品", 1, { true }, onRecord = records::add, onProgress = { _, _ -> })
        assertEquals(0, sent)
        assertEquals(listOf(GiftReplyStatus.SKIPPED_EXISTING, GiftReplyStatus.SKIPPED_MISSING), records.map { it.status })
    }

    @Test fun uncertainResponseStopsAndIsNotAutomaticallyRetried() = runTest {
        val ledger = MemoryGiftReplyLedger()
        val result = result()
        val sent = mutableListOf<String>()
        var fail = true
        fun batch() = GiftCommentReplyBatch(ledger, { comments() }, { sender { id, _ ->
            sent.add(id)
            if (fail) throw IllegalStateException("simulated timeout")
            GiftReplyOutcome(GiftReplyStatus.SUCCESS)
        } }, 0)
        assertFailsWith<IllegalStateException> { batch().run(result, "已赠送", "奖品", 1, { true }, onRecord = {}, onProgress = { _, _ -> }) }
        assertEquals(GiftReplyStatus.UNCERTAIN, ledger.records[1L to sent.first()]?.status)
        fail = false
        batch().run(result, "已赠送", "奖品", 1, { true }, retryRejected = true, onRecord = {}, onProgress = { _, _ -> })
        assertEquals(1, sent.count { it == result.winners().first().commentId })
        assertEquals(2, sent.size)
    }

    @Test fun explicitRejectionRequiresUserRequestedRetry() = runTest {
        val ledger = MemoryGiftReplyLedger()
        val result = result()
        var attempts = 0
        fun batch(reject: Boolean) = GiftCommentReplyBatch(ledger, { comments() }, { sender { _, _ ->
            attempts++; GiftReplyOutcome(if (reject) GiftReplyStatus.REJECTED else GiftReplyStatus.SUCCESS, "平台明确拒绝")
        } }, 0)
        assertFailsWith<IllegalStateException> { batch(true).run(result, "已赠送", "奖品", 1, { true }, onRecord = {}, onProgress = { _, _ -> }) }
        batch(false).run(result, "已赠送", "奖品", 1, { true }, onRecord = {}, onProgress = { _, _ -> })
        assertEquals(2, attempts) // 拒绝项未重试，只处理另一个未发送名额。
        batch(false).run(result, "已赠送", "奖品", 1, { true }, retryRejected = true, onRecord = {}, onProgress = { _, _ -> })
        assertEquals(3, attempts)
    }

    @Test fun stoppingInFlightPersistsUncertaintyAndNeverSendsNextReply() = runTest {
        val ledger = MemoryGiftReplyLedger()
        val result = result()
        var sent = 0
        val job = launch {
            GiftCommentReplyBatch(ledger, { comments() }, { sender { _, _ -> sent++; awaitCancellation() } }, 0)
                .run(result, "已赠送", "奖品", 1, { true }, onRecord = {}, onProgress = { _, _ -> })
        }
        runCurrent()
        job.cancelAndJoin()
        assertEquals(1, sent)
        assertEquals(GiftReplyStatus.UNCERTAIN, ledger.records[1L to result.winners().first().commentId]?.status)
    }

    @Test fun sessionChangeAfterFirstResponseStopsBeforeSecondRequest() = runTest {
        var current = true
        var sent = 0
        val ledger = MemoryGiftReplyLedger()
        val result = result()
        assertFailsWith<CancellationException> {
            GiftCommentReplyBatch(ledger, { comments() }, { sender { _, _ ->
                sent++; current = false; GiftReplyOutcome(GiftReplyStatus.SUCCESS)
            } }, 0).run(result, "已赠送", "奖品", 1, { current }, onRecord = {}, onProgress = { _, _ -> })
        }
        assertEquals(1, sent)
        assertEquals(GiftReplyStatus.SUCCESS, ledger.records[1L to result.winners().first().commentId]?.status)
    }

    @Test fun persistenceFailurePreventsAnyPublicWrite() = runTest {
        var sent = 0
        val ledger = object : GiftReplyLedger {
            override suspend fun get(accountId: Long, commentId: String): GiftReplyRecord? = null
            override suspend fun put(accountId: Long, record: GiftReplyRecord) { error("disk error") }
        }
        assertFailsWith<IllegalStateException> {
            GiftCommentReplyBatch(ledger, { comments() }, { sender { _, _ -> sent++; GiftReplyOutcome(GiftReplyStatus.SUCCESS) } }, 0)
                .run(result(), "已赠送", "奖品", 1, { true }, onRecord = {}, onProgress = { _, _ -> })
        }
        assertEquals(0, sent)
    }
}
