package com.lemon.mcdevmanagermp

import androidx.lifecycle.ViewModelStore
import com.lemon.mcdevmanagermp.data.vo.netease.comment.CommentData
import com.lemon.mcdevmanagermp.data.vo.netease.comment.CommentListVO
import com.lemon.mcdevmanagermp.domain.lottery.GiftReplyOutcome
import com.lemon.mcdevmanagermp.domain.lottery.GiftReplySender
import com.lemon.mcdevmanagermp.domain.lottery.GiftReplyStatus
import com.lemon.mcdevmanagermp.ui.pages.community.lottery.GiftLotteryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class GiftCommentReplyViewModelTest {
    private fun rows() = (1..6).map { n -> CommentData(n.toString(), "模组评测", "1", "玩家$n",
        Instant.parse("2026-10-10T12:00:00Z").epochSeconds, "来源", "5", n.toString(), "好玩") }
    private fun report(uids: List<String>) = "玩家uid,模组id,状态\n" + uids.joinToString("\n") { "$it,2,成功" }
    private fun configure(vm: GiftLotteryViewModel) = vm.edit { it.copy(sourceItemId = "1", rewardItemId = "2",
        winnerCount = "2", startDate = "2026-10-01", endDate = "2026-10-21") }

    @Test fun completedReportsNeedOptInAndRepeatedReportsNeverRepeatReplies() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val versions = MutableStateFlow<Pair<Long?, Long>>(1L to 0L)
        val store = ViewModelStore()
        val posts = mutableListOf<String>()
        try {
            val vm = GiftLotteryViewModel({ _, _, _, _, _ -> CommentListVO(6, rows()) }, { versions.value }, versions,
                dispatcher, replyLedger = MemoryGiftReplyLedger(), replySenderFactory = { object : GiftReplySender {
                    override suspend fun send(commentId: String, content: String): GiftReplyOutcome {
                        posts.add(commentId); return GiftReplyOutcome(GiftReplyStatus.SUCCESS)
                    }
                } }, replyIntervalMillis = 0)
            store.put("lottery", vm)
            configure(vm); vm.draw(); advanceUntilIdle()
            val initial = assertNotNull(vm.exportSnapshot())
            vm.importGiftReport(report(initial.winnerUids), initial); advanceUntilIdle()
            assertTrue(posts.isEmpty())
            vm.setReplyTemplate("{昵称}，已赠送{赠送模组}")
            vm.setAutoReply(true); advanceUntilIdle()
            assertEquals(initial.winners().map { it.commentId }, posts)
            val replied = assertNotNull(vm.exportSnapshot())
            vm.importGiftReport(report(replied.winnerUids), replied); advanceUntilIdle()
            assertEquals(2, posts.size)
            vm.setReplyTemplate("修改文案不覆盖旧回复")
            assertFalse(vm.state.value.replyEnabled)
            vm.setAutoReply(true); advanceUntilIdle()
            assertEquals(2, posts.size)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun waitsUntilReplacementGiftIsAlsoConfirmedThenRepliesWholeCurrentRound() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val versions = MutableStateFlow<Pair<Long?, Long>>(1L to 0L)
        val store = ViewModelStore()
        val posts = mutableListOf<String>()
        try {
            val vm = GiftLotteryViewModel({ _, _, _, _, _ -> CommentListVO(6, rows()) }, { versions.value }, versions,
                dispatcher, replyLedger = MemoryGiftReplyLedger(), replySenderFactory = { object : GiftReplySender {
                    override suspend fun send(commentId: String, content: String): GiftReplyOutcome {
                        posts.add(commentId); return GiftReplyOutcome(GiftReplyStatus.SUCCESS)
                    }
                } }, replyIntervalMillis = 0)
            store.put("lottery", vm)
            configure(vm); vm.draw(); advanceUntilIdle()
            val initial = assertNotNull(vm.exportSnapshot())
            vm.setReplyTemplate("赠送完成")
            vm.setAutoReply(true)
            assertTrue(posts.isEmpty())
            vm.importGiftReport("玩家uid,模组id,状态\n${initial.winnerUids[0]},2,成功\n${initial.winnerUids[1]},2,失败", initial)
            advanceUntilIdle()
            assertTrue(posts.isEmpty())
            val replacement = assertNotNull(vm.exportSnapshot())
            assertFalse(replacement.allGiftsConfirmed())
            vm.importGiftReport(report(listOf(replacement.winnerUids[1])), replacement)
            advanceUntilIdle()
            assertEquals(replacement.winners().map { it.commentId }, posts)
            assertTrue(initial.winners()[1].commentId !in posts)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun accountSwitchCancelsReplyAndDoesNotPublishOldStateToNewAccount() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val versions = MutableStateFlow<Pair<Long?, Long>>(1L to 0L)
        val ledger = MemoryGiftReplyLedger()
        val store = ViewModelStore()
        var posts = 0
        try {
            val vm = GiftLotteryViewModel({ _, _, _, _, _ -> CommentListVO(6, rows()) }, { versions.value }, versions,
                dispatcher, replyLedger = ledger, replySenderFactory = { object : GiftReplySender {
                    override suspend fun send(commentId: String, content: String): GiftReplyOutcome { posts++; awaitCancellation() }
                } }, replyIntervalMillis = 0)
            store.put("lottery", vm)
            configure(vm); vm.draw(); advanceUntilIdle()
            val initial = assertNotNull(vm.exportSnapshot())
            vm.setReplyTemplate("赠送完成"); vm.setAutoReply(true)
            vm.importGiftReport(report(initial.winnerUids), initial)
            runCurrent()
            assertTrue(vm.state.value.replyRunning)
            assertEquals(1, posts)
            versions.value = 2L to 1L
            advanceUntilIdle()
            assertNull(vm.state.value.result)
            assertFalse(vm.state.value.replyRunning)
            assertEquals(1, posts)
            assertEquals(GiftReplyStatus.UNCERTAIN, ledger.records.values.single().status)
            assertEquals(1L, ledger.records.keys.single().first)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}
