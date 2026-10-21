package com.lemon.mcdevmanagermp

import androidx.lifecycle.ViewModelStore
import com.lemon.mcdevmanagermp.data.vo.netease.comment.CommentData
import com.lemon.mcdevmanagermp.data.vo.netease.comment.CommentListVO
import com.lemon.mcdevmanagermp.ui.pages.community.lottery.GiftLotteryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class GiftLotteryViewModelTest {
    private fun rows() = (1..6).map { number -> CommentData(number.toString(), "模组评测", "1", "玩家$number",
        Instant.parse("2026-10-10T12:00:00Z").epochSeconds, "模组", "5", number.toString(), "好玩") }

    @Test fun editingParametersInvalidatesDrawAndPreventsOldExport() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val versions = MutableStateFlow<Pair<Long?, Long>>(1L to 0L)
        val store = ViewModelStore()
        try {
            val vm = GiftLotteryViewModel({ _, _, _, _, _ -> CommentListVO(6, rows()) }, { versions.value }, versions, dispatcher)
            store.put("lottery", vm)
            vm.edit { it.copy(sourceItemId = "1", rewardItemId = "4690141251535232006", winnerCount = "2",
                startDate = "2026-10-01", endDate = "2026-10-21", seed = "7") }
            vm.draw()
            advanceUntilIdle()
            val old = assertNotNull(vm.exportSnapshot())
            vm.edit { it.copy(rewardItemId = "3") }
            assertNull(vm.state.value.result)
            assertFalse(vm.validateSnapshot(old))
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun sessionSwitchClearsResultsAndExportQueue() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val versions = MutableStateFlow<Pair<Long?, Long>>(1L to 0L)
        val store = ViewModelStore()
        try {
            val vm = GiftLotteryViewModel({ _, _, _, _, _ -> CommentListVO(6, rows()) }, { versions.value }, versions, dispatcher)
            store.put("lottery", vm)
            vm.edit { it.copy(sourceItemId = "1", winnerCount = "2", startDate = "2026-10-01", endDate = "2026-10-21") }
            vm.draw()
            advanceUntilIdle()
            val old = assertNotNull(vm.exportSnapshot())
            versions.value = 2L to 1L
            advanceUntilIdle()
            assertNull(vm.state.value.result)
            assertEquals("", vm.state.value.form.excludedText)
            assertFalse(vm.validateSnapshot(old))
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun exportRecordsDoNotBecomeGiftSuccessAndReplacementOnlyQueuesNewUid() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val versions = MutableStateFlow<Pair<Long?, Long>>(1L to 0L)
        val store = ViewModelStore()
        try {
            val vm = GiftLotteryViewModel({ _, _, _, _, _ -> CommentListVO(6, rows()) }, { versions.value }, versions, dispatcher)
            store.put("lottery", vm)
            vm.edit { it.copy(sourceItemId = "1", winnerCount = "2", startDate = "2026-10-01", endDate = "2026-10-21") }
            vm.draw(); advanceUntilIdle()
            val initial = assertNotNull(vm.exportSnapshot())
            vm.recordCsvExport(initial, initial.winnerUids)
            val exported = assertNotNull(vm.exportSnapshot())
            assertTrue(exported.pendingCsvUids().isEmpty())
            assertEquals("not_checked_manual_replacement", exported.purchaseCheck)
            vm.replacePurchased(setOf(exported.winnerUids.first()))
            val replaced = assertNotNull(vm.exportSnapshot())
            assertEquals(1, replaced.pendingCsvUids().size)
            assertEquals(initial.winnerUids[1], replaced.winnerUids[1])
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun cancelledReadCannotLeavePartialResult() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val versions = MutableStateFlow<Pair<Long?, Long>>(1L to 0L)
        val store = ViewModelStore()
        try {
            val vm = GiftLotteryViewModel({ _, _, _, _, _ -> awaitCancellation() }, { versions.value }, versions, dispatcher)
            store.put("lottery", vm)
            vm.edit { it.copy(sourceItemId = "1", winnerCount = "2", startDate = "2026-10-01", endDate = "2026-10-21") }
            vm.draw(); advanceUntilIdle()
            assertTrue(vm.state.value.busy)
            vm.cancel(); advanceUntilIdle()
            assertFalse(vm.state.value.busy)
            assertNull(vm.state.value.result)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}
