package com.lemon.mcdevmanagermp.domain.lottery

import com.lemon.mcdevmanagermp.data.vo.netease.comment.CommentData
import com.lemon.mcdevmanagermp.data.vo.netease.comment.CommentListVO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.datetime.LocalDate

/** 分页函数只负责读取评论；不持有 Cookie，不调用赠送或回复接口。 */
class CommentLotteryLoader(
    private val fetchPage: suspend (item: String, start: String, end: String, offset: Int, span: Int) -> CommentListVO,
    private val pageSize: Int = 500,
    private val countCap: Int = 9999,
) {
    suspend fun load(criteria: GiftLotteryCriteria, onProgress: (String) -> Unit = {}): List<CommentData> {
        criteria.validate()
        require(pageSize > 0 && countCap > 0)
        val collected = linkedMapOf<String, CommentData>()
        var requests = 0

        suspend fun request(start: LocalDate, end: LocalDate, offset: Int): CommentListVO {
            currentCoroutineContext().ensureActive()
            require(++requests <= 2000) { "评论请求过多，已停止；请缩小日期范围" }
            onProgress("正在读取 $start ～ $end，已确认 ${collected.size} 条评论")
            // 实测接口为左闭右开；界面是包含结束日，故请求下一日零点。
            val exclusiveEnd = LocalDate.fromEpochDays(end.toEpochDays() + 1)
            val response = fetchPage(criteria.sourceItemId, start.toString().replace("-", ""),
                exclusiveEnd.toString().replace("-", ""), offset, pageSize)
            currentCoroutineContext().ensureActive()
            require(response.count >= 0 && response.data.size <= pageSize) { "评论接口返回数量异常" }
            return response
        }

        suspend fun loadRange(start: LocalDate, end: LocalDate) {
            var page = request(start, end, 0)
            if (page.count >= countCap) {
                require(start < end) { "$start 单日评论达到接口上限，无法保证全量；本次不抽奖" }
                val middle = LocalDate.fromEpochDays(start.toEpochDays() + (end.toEpochDays() - start.toEpochDays()) / 2)
                loadRange(start, middle)
                loadRange(LocalDate.fromEpochDays(middle.toEpochDays() + 1), end)
                return
            }
            val expected = page.count
            var offset = 0
            val rangeIds = hashSetOf<String>()
            while (true) {
                require(page.count == expected) { "抓取期间评论总数变化，请重新读取后再抽奖" }
                require(page.data.isNotEmpty() || offset == expected) { "评论存在缺页，本次不抽奖" }
                for (row in page.data) {
                    require(row.id.isNotBlank() && rangeIds.add(row.id) && !collected.containsKey(row.id)) {
                        "评论分页重复或日期窗口重叠，请重新读取"
                    }
                    require(row.iid == criteria.sourceItemId) { "评论接口返回了其他组件的数据，本次不抽奖" }
                    collected[row.id] = row
                }
                offset += page.data.size
                require(offset <= expected) { "评论数量超过接口报告总数，请重新读取" }
                if (offset == expected) break
                page = request(start, end, offset)
            }
        }

        loadRange(parseLotteryDate(criteria.startDate), parseLotteryDate(criteria.endDate))
        return collected.values.toList()
    }
}
