package com.lemon.mcdevmanagermp

import androidx.lifecycle.ViewModelStore
import com.lemon.mcdevmanagermp.data.vo.netease.comment.CommentData
import com.lemon.mcdevmanagermp.data.vo.netease.comment.CommentListVO
import com.lemon.mcdevmanagermp.ui.pages.community.lottery.GiftLotteryViewModel
import com.lemon.mcdevmanagermp.ui.components.ModSelectOption
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

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

    @Test fun loadsOwnedModsAndRejectsUnknownSelection() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val versions = MutableStateFlow<Pair<Long?, Long>>(1L to 0L)
        val store = ViewModelStore()
        try {
            val vm = GiftLotteryViewModel({ _, _, _, _, _ -> CommentListVO(0, emptyList()) },
                { versions.value }, versions, dispatcher, fetchMods = {
                    listOf(ModSelectOption("4688538944207380371", "凡人问道"),
                        ModSelectOption("4690141251535232006", "灵气复苏"),
                        ModSelectOption("4690141251535232006", "重复记录"), ModSelectOption("bad-id", "无效记录"))
                })
            store.put("lottery", vm)
            vm.loadMods(); advanceUntilIdle()
            assertTrue(vm.state.value.modsLoaded)
            assertFalse(vm.state.value.modsLoading)
            assertEquals(2, vm.state.value.mods.size)
            assertEquals("4688538944207380371", vm.state.value.form.sourceItemId)
            vm.selectMod("999", source = true)
            assertEquals("4688538944207380371", vm.state.value.form.sourceItemId)
            assertNotNull(vm.state.value.error)
            vm.selectMod("4690141251535232006", source = true)
            assertEquals("4690141251535232006", vm.state.value.form.sourceItemId)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun catalogFailureCanBeRetriedAndCannotDrawWhileMissing() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val versions = MutableStateFlow<Pair<Long?, Long>>(1L to 0L)
        val store = ViewModelStore()
        var attempts = 0
        var commentRequests = 0
        try {
            val vm = GiftLotteryViewModel({ _, _, _, _, _ -> commentRequests++; CommentListVO(0, emptyList()) },
                { versions.value }, versions, dispatcher, fetchMods = {
                    if (++attempts == 1) error("模拟网络失败")
                    listOf(ModSelectOption("1", "当前作品"))
                })
            store.put("lottery", vm)
            vm.loadMods(); advanceUntilIdle()
            assertNotNull(vm.state.value.modsError)
            vm.draw(); advanceUntilIdle()
            assertEquals(0, commentRequests)
            vm.loadMods(); advanceUntilIdle()
            assertTrue(vm.state.value.modsLoaded)
            assertNull(vm.state.value.modsError)
            assertEquals("", vm.state.value.form.sourceItemId)
            assertEquals("", vm.state.value.form.rewardItemId)
            vm.selectMod("1", source = true)
            vm.selectMod("1", source = false)
            assertEquals("1", vm.state.value.form.rewardItemId)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun lateOldAccountCatalogCannotOverwriteNewAccountOptions() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val versions = MutableStateFlow<Pair<Long?, Long>>(1L to 0L)
        val store = ViewModelStore()
        val oldResponse = CompletableDeferred<List<ModSelectOption>>()
        var requests = 0
        try {
            val vm = GiftLotteryViewModel({ _, _, _, _, _ -> CommentListVO(0, emptyList()) },
                { versions.value }, versions, dispatcher, fetchMods = {
                    if (++requests == 1) withContext(NonCancellable) { oldResponse.await() }
                    else listOf(ModSelectOption("2", "新账号作品"))
                })
            store.put("lottery", vm)
            vm.loadMods(); runCurrent()
            versions.value = 2L to 1L
            runCurrent()
            assertEquals(listOf("2"), vm.state.value.mods.map { it.id })
            assertEquals("", vm.state.value.form.sourceItemId)
            assertEquals("", vm.state.value.form.rewardItemId)
            oldResponse.complete(listOf(ModSelectOption("1", "旧账号作品")))
            advanceUntilIdle()
            assertEquals(listOf("2"), vm.state.value.mods.map { it.id })
        } finally { oldResponse.complete(emptyList()); store.clear(); Dispatchers.resetMain() }
    }

    @Test fun calendarSelectionRejectsReversedAndFutureDates() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val versions = MutableStateFlow<Pair<Long?, Long>>(1L to 0L)
        val store = ViewModelStore()
        try {
            val vm = GiftLotteryViewModel({ _, _, _, _, _ -> CommentListVO(0, emptyList()) },
                { versions.value }, versions, dispatcher)
            store.put("lottery", vm)
            val today = Clock.System.now().toLocalDateTime(TimeZone.of("Asia/Shanghai")).date
            val originalStart = today.minus(10, DateTimeUnit.DAY)
            vm.edit { it.copy(startDate = originalStart.toString(), endDate = today.toString()) }
            vm.selectDate(today.plus(1, DateTimeUnit.DAY), start = true)
            assertEquals(originalStart.toString(), vm.state.value.form.startDate)
            vm.selectDate(today.plus(1, DateTimeUnit.DAY), start = false)
            assertEquals(today.toString(), vm.state.value.form.endDate)
            vm.selectDate(today.minus(5, DateTimeUnit.DAY), start = true)
            assertEquals(today.minus(5, DateTimeUnit.DAY).toString(), vm.state.value.form.startDate)
            assertNull(vm.state.value.error)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}
