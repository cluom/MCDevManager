package com.lemon.mcdevmanagermp.domain.lottery

import com.lemon.mcdevmanagermp.data.vo.netease.comment.CommentData
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Instant

private val numericId = Regex("[0-9]{1,20}")

fun validLotteryId(value: String): Boolean = numericId.matches(value) && value.any { it != '0' }

@Serializable
data class GiftLotteryCriteria(
    val sourceItemId: String,
    val rewardItemId: String,
    val startDate: String,
    val endDate: String,
    val stars: Set<Int> = setOf(5),
    val tag: String = "",
    val includeText: String = "",
    val excludeText: String = "",
    val winnerCount: Int = 200,
    val excludedUids: Set<String> = emptySet(),
) {
    fun validate() {
        require(validLotteryId(sourceItemId)) { "请填写正确的来源组件 ID" }
        require(validLotteryId(rewardItemId)) { "请填写正确的赠送组件 ID" }
        val start = parseLotteryDate(startDate)
        val end = parseLotteryDate(endDate)
        require(start <= end) { "开始日期不能晚于结束日期" }
        require(end.toEpochDays() - start.toEpochDays() <= 3660) { "日期范围不能超过十年" }
        require(stars.isNotEmpty() && stars.all { it in 0..5 }) { "请选择至少一个评分（0 表示未评分）" }
        require(winnerCount in 1..10000) { "抽取人数须为 1～10000" }
        require(excludedUids.all(::validLotteryId)) { "排除名单中有无效 UID" }
    }
}

fun parseLotteryDate(value: String): LocalDate = try {
    LocalDate.parse(value)
} catch (_: IllegalArgumentException) {
    throw IllegalArgumentException("日期格式须为 YYYY-MM-DD")
}

@Serializable
data class GiftLotteryEntry(
    val uid: String,
    val commentId: String,
    val itemId: String,
    val nickname: String,
    val stars: Int,
    val publishTime: Long,
)

@Serializable
data class GiftLotteryResult(
    val criteria: GiftLotteryCriteria,
    val seed: Long,
    val loadedComments: Int,
    val uniquePlayers: Int,
    val excludedPlayers: Int,
    val candidates: List<GiftLotteryEntry>,
    val drawOrder: List<String>,
    val winnerUids: List<String>,
    val replacedUids: List<String> = emptyList(),
    val exportedUids: Set<String> = emptySet(),
    val algorithm: String = "splitmix64-fisher-yates-v1",
    val purchaseCheck: String = "not_checked_manual_replacement",
) {
    fun winners(): List<GiftLotteryEntry> {
        val entries = candidates.associateBy { it.uid }
        return winnerUids.map { entries.getValue(it) }
    }

    /** 只替换明确标记的当前中奖者；保留其他名额，历史抽中者不再入池。 */
    fun replacePurchased(uids: Set<String>): GiftLotteryResult {
        require(uids.isNotEmpty()) { "请先选择需要补抽的中奖玩家" }
        require(winnerUids.containsAll(uids)) { "只能标记当前中奖名单中的 UID" }
        val used = (winnerUids + replacedUids).toHashSet()
        val replacements = drawOrder.filterNot { it in used }.take(uids.size)
        require(replacements.size == uids.size) { "剩余合格玩家不足，无法补齐这些名额" }
        val iterator = replacements.iterator()
        return copy(
            winnerUids = winnerUids.map { if (it in uids) iterator.next() else it },
            replacedUids = replacedUids + winnerUids.filter { it in uids },
        )
    }

    fun pendingCsvUids(): List<String> = winnerUids.filterNot { it in exportedUids }

    fun giftCsv(onlyUnexported: Boolean = false): String {
        require(winnerUids.size == criteria.winnerCount && winnerUids.distinct().size == winnerUids.size)
        require(winnerUids.all(::validLotteryId) && validLotteryId(criteria.rewardItemId))
        val ids = if (onlyUnexported) pendingCsvUids() else winnerUids
        require(ids.isNotEmpty()) { "没有尚未导出的新中奖者；需要恢复文件时可重新导出完整名单" }
        return "\uFEFF玩家uid,模组id\r\n" + ids.joinToString("\r\n", postfix = "\r\n") {
            "$it,${criteria.rewardItemId}"
        }
    }
}

object GiftLottery {
    fun draw(comments: List<CommentData>, criteria: GiftLotteryCriteria, seed: Long): GiftLotteryResult {
        criteria.validate()
        val start = parseLotteryDate(criteria.startDate)
        val end = parseLotteryDate(criteria.endDate)
        val latest = comments.asSequence()
            .filter { it.iid == criteria.sourceItemId && validLotteryId(it.uid) }
            .filter { Instant.fromEpochSeconds(it.publishTime).toLocalDateTime(TimeZone.of("Asia/Shanghai")).date in start..end }
            .groupBy { it.uid }
            .mapValues { (_, rows) -> rows.maxWith(compareBy<CommentData> { it.publishTime }
                .thenBy { it.id.toLongOrNull() ?: 0L }.thenBy { it.id }) }
            .values
        val matches = latest.filter { row ->
            val stars = row.stars.toIntOrNull() ?: return@filter false
            stars in criteria.stars &&
                (criteria.tag.isBlank() || row.commentTag == criteria.tag) &&
                (criteria.includeText.isBlank() || row.userComment.contains(criteria.includeText, ignoreCase = true)) &&
                (criteria.excludeText.isBlank() || !row.userComment.contains(criteria.excludeText, ignoreCase = true))
        }
        val excluded = matches.count { it.uid in criteria.excludedUids }
        val eligible = matches.filterNot { it.uid in criteria.excludedUids }
            .sortedBy { it.uid }
            .map { GiftLotteryEntry(it.uid, it.id, it.iid, it.nickname, it.stars.toInt(), it.publishTime) }
        require(eligible.size >= criteria.winnerCount) {
            "合格玩家只有 ${eligible.size} 人，不足 ${criteria.winnerCount} 人；请调整条件后重试"
        }
        val order = eligible.map { it.uid }.toMutableList()
        val random = StableLotteryRandom(seed)
        for (index in order.lastIndex downTo 1) {
            val other = random.nextInt(index + 1)
            val value = order[index]
            order[index] = order[other]
            order[other] = value
        }
        return GiftLotteryResult(
            criteria, seed, comments.size, latest.size, excluded,
            eligible, order, order.take(criteria.winnerCount),
        )
    }
}

/** 固定算法和拒绝采样，避免跨平台随机实现差异与直接取模偏差。 */
private class StableLotteryRandom(private var state: Long) {
    private fun nextLong(): Long {
        state += -7046029254386353131L
        var value = state
        value = (value xor (value ushr 30)) * -4658895280553007687L
        value = (value xor (value ushr 27)) * -7723592293110705685L
        return value xor (value ushr 31)
    }

    fun nextInt(bound: Int): Int {
        val limit = Long.MAX_VALUE - Long.MAX_VALUE % bound
        var value: Long
        do { value = nextLong().ushr(1) } while (value >= limit)
        return (value % bound).toInt()
    }
}

object LotteryUidImport {
    fun fromText(text: String): Set<String> {
        val values = text.trim().split(Regex("[\\s,;，；]+")).filter { it.isNotEmpty() }
        require(values.all(::validLotteryId)) { "UID 名单只能包含数字，以换行或逗号分隔" }
        return values.toSet()
    }

    fun fromCsv(text: String): Set<String> {
        require(text.length <= 5 * 1024 * 1024) { "名单文件不能超过 5 MB" }
        val rows = parseCsv(text.removePrefix("\uFEFF"))
        require(rows.isNotEmpty()) { "CSV 为空" }
        val header = rows.first().map { it.trim().lowercase() }
        val index = header.indexOfFirst { it in setOf("玩家uid", "uid", "game_uid") }
        require(index >= 0) {
            "未找到玩家uid/uid/game_uid列。订单的玩家ID(app_uid)不能直接当作评论UID，请勿用昵称或猜测换算。"
        }
        val ids = rows.drop(1).filterNot { row -> row.all { it.isBlank() } }.mapIndexed { i, row ->
            require(row.size == header.size) { "CSV 第 ${i + 2} 行列数不正确" }
            row[index].trim().also { require(validLotteryId(it)) { "CSV 第 ${i + 2} 行 UID 无效" } }
        }
        require(ids.isNotEmpty()) { "CSV 没有玩家 UID" }
        return ids.toSet()
    }

    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var closedQuote = false
        var index = 0
        fun endField() {
            row.add(field.toString())
            field.clear()
            closedQuote = false
        }
        while (index < text.length) {
            val char = text[index]
            when {
                quoted && char == '"' -> {
                    if (index + 1 < text.length && text[index + 1] == '"') {
                        field.append('"')
                        index++
                    } else { quoted = false; closedQuote = true }
                }
                quoted -> field.append(char)
                char == '"' -> {
                    require(field.isEmpty() && !closedQuote) { "CSV 引号位置不正确" }
                    quoted = true
                }
                char == ',' -> endField()
                char == '\r' || char == '\n' -> {
                    endField()
                    rows.add(row.toList())
                    row.clear()
                    if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                }
                else -> {
                    require(!closedQuote) { "CSV 引号后存在非法字符" }
                    field.append(char)
                }
            }
            index++
        }
        require(!quoted) { "CSV 引号未闭合" }
        if (field.isNotEmpty() || row.isNotEmpty() || closedQuote) {
            endField()
            rows.add(row.toList())
        }
        return rows
    }
}
