package com.lemon.mcdevmanagermp.ui.pages.community.lottery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lemon.mcdevmanagermp.domain.lottery.GiftReplyText
import com.lemon.mcdevmanagermp.ui.theme.LocalAppColors

@Composable
internal fun GiftCommentReplySettings(state: GiftLotteryState, viewModel: GiftLotteryViewModel) {
    val result = state.result ?: return
    val colors = LocalAppColors.current
    val confirmed = result.winnerUids.count { it in result.deliveredUids }
    val first = result.winners().firstOrNull()
    val rewardName = state.mods.find { it.id == result.criteria.rewardItemId }?.name.orEmpty()
    val preview = first?.let { runCatching { GiftReplyText.render(state.replyTemplate, it, rewardName, result.criteria.rewardItemId) } }
    Card(colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("全部赠送确认后，自动回复源评论", fontWeight = FontWeight.Bold)
            Text("报告已确认 $confirmed / ${result.winnerUids.size} 个当前名额赠送成功。未全部确认前不会发送，导出 CSV 不算确认。",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            OutlinedTextField(state.replyTemplate, viewModel::setReplyTemplate, modifier = Modifier.fillMaxWidth(),
                enabled = !state.replyRunning, minLines = 2, maxLines = 5,
                label = { Text("自定义回复文案") },
                supportingText = { Text("可用 {昵称}、{赠送模组}、{模组ID}；展开后每条限 500 字符。修改文案后需重新启用。") })
            if (state.replyTemplate.isNotBlank()) {
                Text("回复预览", style = MaterialTheme.typography.labelLarge)
                Text(preview?.getOrElse { it.message ?: "文案不正确" }.orEmpty(), style = MaterialTheme.typography.bodyMedium,
                    color = if (preview?.isFailure == true) MaterialTheme.colorScheme.error else colors.textColor)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("启用自动公开回复")
                    Text(if (result.allGiftsConfirmed()) "开启后将立即处理本轮源评论" else "全部成功报告到齐时自动触发",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Switch(checked = state.replyEnabled, onCheckedChange = viewModel::setAutoReply,
                    enabled = !state.replyRunning && state.replyTemplate.isNotBlank())
            }
            Text("已有回复的源评论会跳过；成功和待核对记录按账号保存在本机，不会静默重复发送。不要同时在多个客户端运行批量回复。",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (state.replyRunning) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.replyProgress, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = viewModel::stopCommentReplies) { Text("停止回复") }
            } else if (result.replyRecords.isNotEmpty() || state.replyNotice != null) {
                OutlinedButton(onClick = viewModel::retryCommentReplies,
                    enabled = result.allGiftsConfirmed() && state.replyTemplate.isNotBlank()) {
                    Text("继续未发送项 / 重试明确失败项")
                }
            }
            state.replyNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
        }
    }
}
