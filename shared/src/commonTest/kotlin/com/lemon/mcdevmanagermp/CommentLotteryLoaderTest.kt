package com.lemon.mcdevmanagermp

import com.lemon.mcdevmanagermp.data.vo.netease.comment.CommentData
import com.lemon.mcdevmanagermp.data.vo.netease.comment.CommentListVO
import com.lemon.mcdevmanagermp.domain.lottery.CommentLotteryLoader
import com.lemon.mcdevmanagermp.domain.lottery.GiftLotteryCriteria
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CommentLotteryLoaderTest {
    private val criteria = GiftLotteryCriteria("1", "2", "2026-10-01", "2026-10-02", winnerCount = 1)
    private fun row(id: String) = CommentData(id, "默认类型", "1", "玩家", 1, "模组", "5", id, "好玩")

    @Test fun fetchesEveryPageIncludingShortIntermediatePage() = runTest {
        val offsets = mutableListOf<Int>()
        val loader = CommentLotteryLoader({ _, _, _, offset, _ ->
            offsets.add(offset)
            CommentListVO(3, listOf(row((offset + 1).toString())))
        }, pageSize = 2)
        assertEquals(3, loader.load(criteria).size)
        assertEquals(listOf(0, 1, 2), offsets)
    }

    @Test fun cappedCountSplitsIntoDisjointDateWindows() = runTest {
        val windows = mutableListOf<Pair<String, String>>()
        val loader = CommentLotteryLoader({ _, start, end, _, _ ->
            windows.add(start to end)
            if (start == "20261001" && end == "20261003") CommentListVO(4, listOf(row("ignore")))
            else CommentListVO(1, listOf(row(start)))
        }, pageSize = 2, countCap = 4)
        assertEquals(setOf("20261001", "20261002"), loader.load(criteria).map { it.id }.toSet())
        assertEquals(3, windows.size)
        assertEquals(listOf("20261001" to "20261003", "20261001" to "20261002", "20261002" to "20261003"), windows)
    }

    @Test fun cappedSingleDayIsNotAcceptedAsComplete() = runTest {
        val loader = CommentLotteryLoader({ _, _, _, _, _ -> CommentListVO(4, listOf(row("1"))) }, countCap = 4)
        assertFailsWith<IllegalArgumentException> { loader.load(criteria.copy(endDate = criteria.startDate)) }
    }

    @Test fun missingPageAndChangingCountAreRejected() = runTest {
        val missing = CommentLotteryLoader({ _, _, _, offset, _ ->
            CommentListVO(2, if (offset == 0) listOf(row("1")) else emptyList())
        })
        assertFailsWith<IllegalArgumentException> { missing.load(criteria) }
        val changing = CommentLotteryLoader({ _, _, _, offset, _ ->
            CommentListVO(if (offset == 0) 2 else 3, listOf(row((offset + 1).toString())))
        })
        assertFailsWith<IllegalArgumentException> { changing.load(criteria) }
    }

    @Test fun duplicatePagesAreRejectedAndEmptyRangeIsValid() = runTest {
        val repeated = CommentLotteryLoader({ _, _, _, _, _ -> CommentListVO(2, listOf(row("1"))) })
        assertFailsWith<IllegalArgumentException> { repeated.load(criteria) }
        val empty = CommentLotteryLoader({ _, _, _, _, _ -> CommentListVO(0, emptyList()) })
        assertTrue(empty.load(criteria).isEmpty())
    }
}
