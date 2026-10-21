package com.lemon.mcdevmanagermp

import com.lemon.mcdevmanagermp.data.vo.netease.comment.CommentData
import com.lemon.mcdevmanagermp.domain.lottery.GiftLottery
import com.lemon.mcdevmanagermp.domain.lottery.GiftLotteryCriteria
import com.lemon.mcdevmanagermp.domain.lottery.LotteryUidImport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class GiftLotteryTest {
    private val criteria = GiftLotteryCriteria("4688538944207380371", "4690141251535232006", "2026-10-01", "2026-10-21", winnerCount = 2)
    private val timestamp = Instant.parse("2026-10-10T12:00:00Z").epochSeconds

    private fun comment(uid: String, id: String = uid, stars: String = "5", time: Long = timestamp,
                        item: String = criteria.sourceItemId, text: String = "喜欢修仙") = CommentData(
        id, "模组评测", item, "玩家$uid", time, "免费修仙", stars, uid, text,
    )

    @Test fun duplicatesHaveOnlyOneChanceAndLatestRatingWins() {
        val rows = listOf(comment("1"), comment("1", "new", "1", timestamp + 1), comment("2"), comment("3"))
        val draw = GiftLottery.draw(rows, criteria, 7)
        assertEquals(setOf("2", "3"), draw.winnerUids.toSet())
        assertEquals(3, draw.uniquePlayers)
    }

    @Test fun sameTimestampUsesNumericCommentIdRatherThanLexicalOrder() {
        val rows = listOf(comment("1", "9", "5"), comment("1", "10", "1"), comment("2"), comment("3"))
        assertEquals(setOf("2", "3"), GiftLottery.draw(rows, criteria, 7).winnerUids.toSet())
    }

    @Test fun sourceDatesKeywordsAndUidExclusionsApplyTogether() {
        val rows = listOf(comment("1"), comment("2", text = "崩溃"), comment("3"), comment("4"),
            comment("5", item = "9"), comment("6", time = 1))
        val draw = GiftLottery.draw(rows, criteria.copy(includeText = "修仙", excludeText = "崩溃", excludedUids = setOf("1")), 8)
        assertEquals(setOf("3", "4"), draw.winnerUids.toSet())
        assertEquals(1, draw.excludedPlayers)
    }

    @Test fun stableSeedIgnoresInputOrderAndNeverRepeatsPlayers() {
        val rows = (1..100).map { comment(it.toString()) }
        val first = GiftLottery.draw(rows, criteria.copy(winnerCount = 60), Long.MIN_VALUE)
        val second = GiftLottery.draw(rows.reversed() + rows, criteria.copy(winnerCount = 60), Long.MIN_VALUE)
        assertEquals(first.winnerUids, second.winnerUids)
        assertEquals(60, first.winnerUids.distinct().size)
        assertEquals(100, first.drawOrder.distinct().size)
    }

    @Test fun insufficientPlayersFailsRatherThanReducingCount() {
        assertFailsWith<IllegalArgumentException> { GiftLottery.draw(listOf(comment("1")), criteria, 1) }
    }

    @Test fun csvRetainsExactNineteenDigitRewardId() {
        val result = GiftLottery.draw(listOf(comment("2147483649"), comment("99999999999999999999")), criteria, 5)
        val csv = result.giftCsv()
        assertTrue(csv.startsWith("\uFEFF玩家uid,模组id\r\n"))
        assertEquals(2, csv.lineSequence().count { it.endsWith(",4690141251535232006") })
        assertEquals(result.winnerUids.toSet(), LotteryUidImport.fromCsv(csv))
        assertTrue(csv.contains("99999999999999999999"))
        assertFalse(csv.contains("E+"))
    }

    @Test fun replacementKeepsOtherWinnersAndNeverReentersPreviousPlayers() {
        var result = GiftLottery.draw((1..8).map { comment(it.toString()) }, criteria, 7)
        val unchanged = result.winnerUids[1]
        val original = result.winnerUids.first()
        result = result.replacePurchased(setOf(original))
        assertEquals(unchanged, result.winnerUids[1])
        assertFalse(original in result.winnerUids)
        val second = result.winnerUids.first()
        result = result.replacePurchased(setOf(second))
        assertEquals(setOf(original, second), result.replacedUids.toSet())
        assertTrue(result.winnerUids.none { it in result.replacedUids })
        assertEquals(2, result.winnerUids.distinct().size)
    }

    @Test fun exhaustedOrUnknownReplacementDoesNotAlterOriginal() {
        val result = GiftLottery.draw(listOf(comment("1"), comment("2")), criteria, 1)
        assertFailsWith<IllegalArgumentException> { result.replacePurchased(setOf(result.winnerUids.first())) }
        assertFailsWith<IllegalArgumentException> { result.replacePurchased(setOf("99")) }
        assertEquals(2, result.winnerUids.size)
        assertTrue(result.replacedUids.isEmpty())
    }

    @Test fun replacementCsvOnlyContainsNewPlayersAfterOriginalExport() {
        var result = GiftLottery.draw((1..6).map { comment(it.toString()) }, criteria, 9)
        val original = result.winnerUids.toSet()
        result = result.copy(exportedUids = original)
        assertTrue(result.pendingCsvUids().isEmpty())
        assertFailsWith<IllegalArgumentException> { result.giftCsv(onlyUnexported = true) }
        result = result.replacePurchased(setOf(result.winnerUids.first()))
        val newUids = LotteryUidImport.fromCsv(result.giftCsv(onlyUnexported = true))
        assertEquals(1, newUids.size)
        assertTrue(newUids.none { it in original })
        assertEquals(2, LotteryUidImport.fromCsv(result.giftCsv()).size)
    }

    @Test fun explicitUidCsvSupportsQuotesAndMultilineNotes() {
        assertEquals(setOf("123", "456"), LotteryUidImport.fromCsv("\uFEFF玩家uid,备注\r\n\"123\",\"一行\n两行\"\r\n456,ok\r\n"))
        assertEquals(setOf("123", "456"), LotteryUidImport.fromText("123,456\n123"))
    }

    @Test fun orderAppUidAndNicknameOnlyCsvAreRejected() {
        assertFailsWith<IllegalArgumentException> { LotteryUidImport.fromCsv("订单ID,玩家ID\n1,123456789\n") }
        assertFailsWith<IllegalArgumentException> { LotteryUidImport.fromCsv("玩家昵称,星级\n同名玩家,5\n") }
    }

    @Test fun malformedCsvAndNonNumericIdsAreRejected() {
        assertFailsWith<IllegalArgumentException> { LotteryUidImport.fromCsv("uid\n\"123\n") }
        assertFailsWith<IllegalArgumentException> { LotteryUidImport.fromCsv("uid\n=123\n") }
        assertFailsWith<IllegalArgumentException> { LotteryUidImport.fromCsv("uid\n123,extra\n") }
        assertFailsWith<IllegalArgumentException> { criteria.copy(winnerCount = 0).validate() }
        assertFailsWith<IllegalArgumentException> { criteria.copy(startDate = "2026-10-22").validate() }
        assertFailsWith<IllegalArgumentException> { criteria.copy(stars = emptySet()).validate() }
    }
}
