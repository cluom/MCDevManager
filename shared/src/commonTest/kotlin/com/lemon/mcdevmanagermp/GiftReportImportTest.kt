package com.lemon.mcdevmanagermp

import com.lemon.mcdevmanagermp.data.vo.netease.comment.CommentData
import com.lemon.mcdevmanagermp.domain.lottery.GiftLottery
import com.lemon.mcdevmanagermp.domain.lottery.GiftLotteryCriteria
import com.lemon.mcdevmanagermp.domain.lottery.GiftReportImport
import com.lemon.mcdevmanagermp.domain.lottery.GiftReportStatus
import com.lemon.mcdevmanagermp.domain.lottery.LotteryUidImport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class GiftReportImportTest {
    private val reward = "4690141251535232006"
    private fun draw(players: Int = 10) = GiftLottery.draw((1..players).map { number ->
        CommentData(number.toString(), "模组评测", "1", "玩家$number",
            Instant.parse("2026-10-10T12:00:00Z").epochSeconds, "模组", "5", number.toString(), "好玩")
    }, GiftLotteryCriteria("1", reward, "2026-10-01", "2026-10-21", winnerCount = 3), 7)

    private fun csv(vararg rows: String) = "\uFEFF玩家uid,模组id,状态,失败原因\r\n" + rows.joinToString("\r\n", postfix = "\r\n")

    @Test fun parsesPlatformSchemaAndQuotedMultilineReasonWithoutRoundingIds() {
        val text = "\uFEFF玩家uid,模组id,模组名称,单价(钻石),折扣,折后价(钻石),状态,失败原因\r\n" +
            "99999999999999999999,$reward,奖品,100,1,100,失败,\"已拥有,无法赠送\n请换人\"\r\n"
        val row = GiftReportImport.parse(text).single()
        assertEquals("99999999999999999999", row.uid)
        assertEquals(reward, row.itemId)
        assertEquals(GiftReportStatus.FAILED, row.status)
        assertEquals("已拥有,无法赠送\n请换人", row.failureReason)
    }

    @Test fun onlyFailedSlotsAreReplacedAndCsvOnlyContainsNewReplacements() {
        val original = draw()
        val (failed, success, untouched) = original.winnerUids
        val input = original.copy(exportedUids = original.winnerUids.toSet())
        val applied = GiftReportImport.apply(input, csv("$failed,$reward,失败,已拥有", "$success,$reward,成功,"))
        assertEquals(success, applied.result.winnerUids[1])
        assertEquals(untouched, applied.result.winnerUids[2])
        assertTrue(failed !in applied.result.winnerUids)
        assertEquals(setOf(failed), applied.audit.replacedFailureUids)
        assertEquals(setOf(success), applied.result.deliveredUids)
        assertEquals(setOf(applied.result.winnerUids[0]), LotteryUidImport.fromCsv(applied.result.giftCsv(true)))
        assertTrue(input.reportImports.isEmpty())
        assertEquals(1, applied.result.reportImports.size)
    }

    @Test fun allSuccessfulReportKeepsWinnersAndDoesNotQueueAnotherGift() {
        val original = draw()
        val applied = GiftReportImport.apply(original, csv(*original.winnerUids.map { "$it,$reward,成功," }.toTypedArray()))
        assertEquals(original.winnerUids, applied.result.winnerUids)
        assertEquals(original.winnerUids.toSet(), applied.result.deliveredUids)
        assertTrue(applied.result.pendingCsvUids().isEmpty())
        assertTrue(applied.audit.replacedFailureUids.isEmpty())
    }

    @Test fun duplicateFailuresReplaceOnceAndSuccessWinsConflictingRecords() {
        val original = draw()
        val (failed, success) = original.winnerUids
        val applied = GiftReportImport.apply(original, csv("$failed,$reward,失败,已购", "$failed,$reward,失败,已购",
            "$success,$reward,失败,错误记录", "$success,$reward,成功,"))
        assertEquals(setOf(failed), applied.audit.replacedFailureUids)
        assertEquals(2, applied.audit.duplicateRows)
        assertEquals(success, applied.result.winnerUids[1])
        assertEquals(setOf(success), applied.result.deliveredUids)
    }

    @Test fun unresolvedOrPendingStatusDoesNotBecomeFailureEvenWhenReasonSaysFailed() {
        val original = draw()
        val (pending, unknown) = original.winnerUids
        val applied = GiftReportImport.apply(original, csv("$pending,$reward,失败,一次失败",
            "$pending,$reward,处理中,", "$unknown,$reward,未知状态,赠送失败"))
        assertEquals(original.winnerUids, applied.result.winnerUids)
        assertEquals(setOf(pending, unknown), applied.audit.unresolvedUids)
        assertTrue(applied.audit.replacedFailureUids.isEmpty())
        assertEquals(listOf(original.winnerUids[2]), applied.result.pendingCsvUids())
    }

    @Test fun otherRewardAndNonCurrentUidCannotReplaceAnyone() {
        val original = draw()
        val applied = GiftReportImport.apply(original, csv("${original.winnerUids[0]},999,失败,",
            "999,$reward,失败,"))
        assertEquals(original.winnerUids, applied.result.winnerUids)
        assertEquals(1, applied.audit.otherItemRows)
        assertEquals(1, applied.audit.outsideWinnerRows)
        assertEquals(original.pendingCsvUids(), applied.result.pendingCsvUids())
    }

    @Test fun repeatedOldFailureReportNeverReplacesTheReplacementAgain() {
        val original = draw()
        val report = csv("${original.winnerUids.first()},$reward,失败,已拥有")
        val first = GiftReportImport.apply(original, report).result
        val second = GiftReportImport.apply(first, report)
        assertEquals(first.winnerUids, second.result.winnerUids)
        assertEquals(first.replacedUids, second.result.replacedUids)
        assertTrue(second.audit.replacedFailureUids.isEmpty())
        assertEquals(first.pendingCsvUids(), second.result.pendingCsvUids())
    }

    @Test fun confirmedSuccessIsProtectedFromLaterFailureAndManualReplacement() {
        val original = draw()
        val uid = original.winnerUids.first()
        val success = GiftReportImport.apply(original, csv("$uid,$reward,成功,")).result
        val later = GiftReportImport.apply(success, csv("$uid,$reward,失败,迟到记录"))
        assertEquals(original.winnerUids, later.result.winnerUids)
        assertEquals(setOf(uid), later.audit.protectedSuccessUids)
        assertTrue(later.audit.replacedFailureUids.isEmpty())
        assertFailsWith<IllegalArgumentException> { later.result.replacePurchased(setOf(uid)) }
    }

    @Test fun insufficientCandidatesDoNotPartiallyRecordSuccessOrReplaceAnyone() {
        val original = draw(players = 3)
        val (failed, success) = original.winnerUids
        assertFailsWith<IllegalArgumentException> { GiftReportImport.apply(original,
            csv("$failed,$reward,失败,", "$success,$reward,成功,")) }
        assertTrue(original.deliveredUids.isEmpty())
        assertTrue(original.exportedUids.isEmpty())
        assertTrue(original.reportImports.isEmpty())
        assertTrue(original.replacedUids.isEmpty())
    }

    @Test fun wrongIdentityMissingColumnsAndMalformedRowsAreRejected() {
        listOf("玩家ID,模组id,状态\n123,$reward,失败", "玩家uid,模组id\n123,$reward",
            "玩家uid,模组id,状态,状态\n123,$reward,失败,成功", csv("1E10,$reward,失败,"),
            csv("123,4.69014125153523E18,失败,"), csv("123,$reward,失败"),
            "玩家uid,模组id,状态\n\"123,$reward,失败").forEach { text ->
            assertFailsWith<IllegalArgumentException> { GiftReportImport.parse(text) }
        }
    }
}
