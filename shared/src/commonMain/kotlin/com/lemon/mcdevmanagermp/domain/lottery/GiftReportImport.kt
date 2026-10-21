package com.lemon.mcdevmanagermp.domain.lottery

import kotlinx.serialization.Serializable

enum class GiftReportStatus { SUCCESS, FAILED, UNRESOLVED }

data class GiftReportRow(
    val uid: String,
    val itemId: String,
    val status: GiftReportStatus,
    val rawStatus: String,
    val failureReason: String,
)

@Serializable
data class GiftReportAudit(
    val totalRows: Int,
    val confirmedSuccessUids: Set<String>,
    val replacedFailureUids: Set<String>,
    val unresolvedUids: Set<String>,
    val protectedSuccessUids: Set<String>,
    val otherItemRows: Int,
    val outsideWinnerRows: Int,
    val duplicateRows: Int,
    val unresolvedStatuses: Set<String>,
)

data class GiftReportApplication(val result: GiftLotteryResult, val audit: GiftReportAudit)

object GiftReportImport {
    fun parse(text: String): List<GiftReportRow> {
        require(text.length <= 5 * 1024 * 1024) { "赠送报告不能超过 5 MB" }
        val rows = LotteryUidImport.parseCsv(text.removePrefix("\uFEFF"))
        require(rows.isNotEmpty()) { "赠送报告为空" }
        val header = rows.first().map { it.trim().lowercase() }
        require(header.distinct().size == header.size) { "赠送报告存在重复列名" }
        val uidColumn = header.indexOfFirst { it in setOf("玩家uid", "uid", "game_uid") }
        val itemColumn = header.indexOf("模组id")
        val statusColumn = header.indexOf("状态")
        val reasonColumn = header.indexOf("失败原因")
        require(uidColumn >= 0 && itemColumn >= 0 && statusColumn >= 0) {
            "请选择平台赠送报告：必须包含玩家uid、模组id、状态列；订单玩家ID(app_uid)不能替代UID"
        }
        val result = rows.drop(1).filterNot { row -> row.all { it.isBlank() } }.mapIndexed { index, row ->
            require(row.size == header.size) { "赠送报告第 ${index + 2} 行列数不正确" }
            val uid = row[uidColumn].trim()
            val itemId = row[itemColumn].trim()
            require(validLotteryId(uid) && validLotteryId(itemId)) { "赠送报告第 ${index + 2} 行 UID 或模组 ID 无效" }
            val rawStatus = row[statusColumn].trim()
            val status = when (rawStatus.lowercase()) {
                "成功", "赠送成功", "发放成功", "success", "succeeded" -> GiftReportStatus.SUCCESS
                "失败", "赠送失败", "发放失败", "failed", "failure" -> GiftReportStatus.FAILED
                else -> GiftReportStatus.UNRESOLVED
            }
            GiftReportRow(uid, itemId, status, rawStatus, if (reasonColumn >= 0) row[reasonColumn].trim() else "")
        }
        require(result.isNotEmpty()) { "赠送报告没有玩家记录" }
        return result
    }

    /** 纯函数：所有检查及补抽都成功后才返回新结果，不部分改动旧名单。 */
    fun apply(result: GiftLotteryResult, text: String): GiftReportApplication {
        val rows = parse(text)
        val itemRows = rows.filter { it.itemId == result.criteria.rewardItemId }
        val winners = result.winnerUids.toSet()
        val matching = itemRows.filter { it.uid in winners }
        val grouped = matching.groupBy { it.uid }
        val successes = linkedSetOf<String>()
        val failures = linkedSetOf<String>()
        val unresolved = linkedSetOf<String>()
        val protected = linkedSetOf<String>()
        grouped.forEach { (uid, records) ->
            when {
                records.any { it.status == GiftReportStatus.SUCCESS } -> successes.add(uid)
                uid in result.deliveredUids -> protected.add(uid)
                records.any { it.status == GiftReportStatus.UNRESOLVED } -> unresolved.add(uid)
                records.all { it.status == GiftReportStatus.FAILED } -> failures.add(uid)
            }
        }
        val audit = GiftReportAudit(
            totalRows = rows.size,
            confirmedSuccessUids = successes,
            replacedFailureUids = failures,
            unresolvedUids = unresolved,
            protectedSuccessUids = protected,
            otherItemRows = rows.size - itemRows.size,
            outsideWinnerRows = itemRows.size - matching.size,
            duplicateRows = matching.size - grouped.size,
            unresolvedStatuses = matching.filter { it.uid in unresolved && it.status == GiftReportStatus.UNRESOLVED }
                .map { it.rawStatus.ifBlank { "空状态" }.take(40) }.toSet(),
        )
        // 报告证明这些当前名额已提交平台，未知/处理中也不能重新导出重复提交。
        val recorded = result.copy(
            deliveredUids = result.deliveredUids + successes,
            exportedUids = result.exportedUids + grouped.keys,
        )
        val updated = if (failures.isEmpty()) recorded else recorded.replacePurchased(failures)
        return GiftReportApplication(updated.copy(reportImports = result.reportImports + audit), audit)
    }
}
