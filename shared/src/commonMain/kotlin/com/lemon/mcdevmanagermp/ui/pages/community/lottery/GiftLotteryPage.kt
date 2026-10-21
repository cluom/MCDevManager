package com.lemon.mcdevmanagermp.ui.pages.community.lottery

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lemon.mcdevmanagermp.domain.lottery.GiftLotteryResult
import com.lemon.mcdevmanagermp.domain.lottery.LotteryUidImport
import com.lemon.mcdevmanagermp.domain.lottery.parseLotteryDate
import com.lemon.mcdevmanagermp.ui.components.AppDatePickerDialog
import com.lemon.mcdevmanagermp.ui.theme.LocalAppColors
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFileSaverLauncher
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.writeString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock

@Composable
fun GiftLotteryPage(onBack: () -> Unit) {
    val viewModel: GiftLotteryViewModel = viewModel { GiftLotteryViewModel() }
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val colors = LocalAppColors.current
    var replacementText by remember { mutableStateOf("") }
    var pendingSnapshot by remember { mutableStateOf<GiftLotteryResult?>(null) }
    var pendingReportSnapshot by remember { mutableStateOf<GiftLotteryResult?>(null) }
    var dateTarget by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(viewModel) { viewModel.loadMods() }
    // 0=未导出的新中奖者，1=完整当前名单，2=审计。
    var pendingExportMode by remember { mutableStateOf(0) }
    val json = remember { Json { prettyPrint = true } }

    val picker = rememberFilePickerLauncher(type = FileKitType.File()) { file ->
        if (file != null) scope.launch {
            try {
                val bytes = file.readBytes()
                require(bytes.size <= 5 * 1024 * 1024) { "名单文件不能超过 5 MB" }
                viewModel.importExcludedCsv(bytes.decodeToString(throwOnInvalidSequence = true))
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) { viewModel.error(exception.message ?: "CSV 读取失败，请使用 UTF-8 编码") }
        }
    }
    val reportPicker = rememberFilePickerLauncher(
        type = FileKitType.File(),
        onError = {
            pendingReportSnapshot = null
            viewModel.error("无法打开报告选择窗口，请重试")
        },
    ) { file ->
        val snapshot = pendingReportSnapshot
        pendingReportSnapshot = null
        if (file != null && snapshot != null) scope.launch {
            try {
                val bytes = file.readBytes()
                require(bytes.size <= 5 * 1024 * 1024) { "赠送报告不能超过 5 MB" }
                viewModel.importGiftReport(bytes.decodeToString(throwOnInvalidSequence = true), snapshot)
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) { viewModel.error(exception.message ?: "报告读取失败，请使用 UTF-8 CSV") }
        }
    }
    val saver = rememberFileSaverLauncher(
        dialogSettings = FileKitDialogSettings.createDefault(),
        onError = {
            pendingSnapshot = null
            viewModel.error("无法打开保存对话框，请重试或选择可写目录")
        },
    ) { file ->
        val snapshot = pendingSnapshot
        val mode = pendingExportMode
        pendingSnapshot = null
        if (file != null && snapshot != null && viewModel.validateSnapshot(snapshot)) scope.launch {
            try {
                if (!viewModel.validateSnapshot(snapshot)) return@launch
                file.writeString(if (mode == 2) json.encodeToString(snapshot) else snapshot.giftCsv(onlyUnexported = mode == 0))
                if (mode != 2) viewModel.recordCsvExport(snapshot, if (mode == 0) snapshot.pendingCsvUids() else snapshot.winnerUids)
                viewModel.message(if (mode == 2) "抽奖审计已保存（含条件、种子、候选与补抽记录）" else "CSV 已保存（不代表已赠送）。请直接导入平台，勿用 Excel 改写大整数 ID")
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) { viewModel.error(exception.message ?: "文件保存失败") }
        }
    }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.align(Alignment.TopCenter).widthIn(max = 1050.dp).fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                Text("条件抽奖", style = MaterialTheme.typography.titleLarge, color = colors.textColor)
            }
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("按真实 UID 去重，一人一次机会。这里只生成名单，不会赠送或发表评论。",
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("参与条件", fontWeight = FontWeight.Bold)
                            val canSelectMods = !state.busy && state.modsLoaded && !state.modsLoading
                            LotteryModSelector("来源模组（读取谁的评论）", state.form.sourceItemId, state.mods,
                                canSelectMods, state.modsLoading) { viewModel.selectMod(it, source = true) }
                            LotteryModSelector("赠送模组（CSV 中的奖品）", state.form.rewardItemId, state.mods,
                                canSelectMods, state.modsLoading) { viewModel.selectMod(it, source = false) }
                            state.modsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (!state.modsLoading && state.modsLoaded && state.mods.isEmpty()) {
                                Text("当前账号没有可选的 PE 作品，请检查登录账号。", color = colors.onSurfaceVariant)
                            }
                            TextButton(onClick = viewModel::loadMods, enabled = !state.modsLoading && !state.busy) {
                                Text(if (state.modsError != null) "重试读取作品列表" else "刷新作品列表")
                            }
                            Text("从当前账号的作品中选择，支持搜索名称或 ID。日期按北京时间，包含起止两天。",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                LotteryDateField("开始日期", state.form.startDate, !state.busy, Modifier.weight(1f)) { dateTarget = 0 }
                                LotteryDateField("结束日期", state.form.endDate, !state.busy, Modifier.weight(1f)) { dateTarget = 1 }
                            }
                            Text("评分（多选）", style = MaterialTheme.typography.labelLarge)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                (0..5).forEach { star ->
                                    FilterChip(selected = star in state.form.stars, enabled = !state.busy,
                                        onClick = { viewModel.edit { it.copy(stars = if (star in it.stars) it.stars - star else it.stars + star) } },
                                        label = { Text(if (star == 0) "未评分" else "$star 星") })
                                }
                            }
                            Text("同一 UID 在所选日期内只看最新评价，再判断是否符合评分和内容条件。",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            LotteryField("评论类型（留空不限）", state.form.tag, !state.busy) { value -> viewModel.edit { it.copy(tag = value) } }
                            LotteryField("评论必须包含（留空不限）", state.form.includeText, !state.busy) { value -> viewModel.edit { it.copy(includeText = value) } }
                            LotteryField("评论不得包含（留空不限）", state.form.excludeText, !state.busy) { value -> viewModel.edit { it.copy(excludeText = value) } }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                LotteryField("抽取人数", state.form.winnerCount, !state.busy, Modifier.weight(1f)) { value -> viewModel.edit { it.copy(winnerCount = value) } }
                                LotteryField("随机种子（留空自动）", state.form.seed, !state.busy, Modifier.weight(1f)) { value -> viewModel.edit { it.copy(seed = value) } }
                            }
                            OutlinedTextField(state.form.excludedText, { value -> viewModel.edit { it.copy(excludedText = value) } },
                                label = { Text("可选：排除 UID，逗号或换行分隔") }, minLines = 2, maxLines = 4,
                                enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                            OutlinedButton(onClick = { picker.launch() }, enabled = !state.busy) { Text("导入 UID 排除名单 CSV") }
                            Text("只接受玩家uid / uid / game_uid 列。订单表的玩家ID是 app_uid，不能直接比对。未自动校验是否已购，请在平台判定已购后补抽。",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            if (state.busy) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(state.progress, style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = viewModel::cancel) { Text("取消读取") }
                            } else Button(onClick = viewModel::draw, modifier = Modifier.fillMaxWidth(),
                                enabled = state.modsLoaded && !state.modsLoading && state.form.sourceItemId.isNotEmpty() && state.form.rewardItemId.isNotEmpty()) {
                                Text(if (state.result == null) "读取完整评论并抽奖" else "重新读取并整组重抽（清空本轮记录）")
                            }
                        }
                    }
                }
                state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                state.message?.let { message -> item { Text(message, color = colors.primary) } }
                val result = state.result
                if (result != null) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("中奖 ${result.winnerUids.size} 人", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("读取 ${result.loadedComments} 条 · 去重 ${result.uniquePlayers} 人 · 合格 ${result.candidates.size} 人 · 已替换 ${result.replacedUids.size} 人",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            Text("种子：${result.seed}　赠送组件：${result.criteria.rewardItemId}", style = MaterialTheme.typography.bodySmall)
                            Text("未校验是否已购。标记后只替换对应名额，历史抽中 UID 不再进入候选池。",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                Button(enabled = pendingSnapshot == null && result.pendingCsvUids().isNotEmpty(), onClick = {
                                    viewModel.exportSnapshot()?.let {
                                        pendingSnapshot = it; pendingExportMode = 0
                                        saver.launch(suggestedName = "batch_gift_${Clock.System.now().toEpochMilliseconds()}", defaultExtension = "csv")
                                    }
                                }) { Text("导出新名单 CSV（${result.pendingCsvUids().size}人）") }
                                OutlinedButton(enabled = pendingSnapshot == null, onClick = {
                                    viewModel.exportSnapshot()?.let {
                                        pendingSnapshot = it; pendingExportMode = 1
                                        saver.launch(suggestedName = "batch_gift_full_${Clock.System.now().toEpochMilliseconds()}", defaultExtension = "csv")
                                    }
                                }) { Text("重新导出完整名单") }
                                OutlinedButton(enabled = pendingSnapshot == null, onClick = {
                                    viewModel.exportSnapshot()?.let {
                                        pendingSnapshot = it; pendingExportMode = 2
                                        saver.launch(suggestedName = "lottery_audit_${Clock.System.now().toEpochMilliseconds()}", defaultExtension = "json")
                                    }
                                }) { Text("保存抽奖审计") }
                            }
                            Text("默认只导出尚未导出的新中奖者，补抽后不会再次输出旧批次。完整名单仅用于恢复/核对；导出状态不等于发放状态。",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            OutlinedButton(enabled = pendingReportSnapshot == null && pendingSnapshot == null, onClick = {
                                viewModel.exportSnapshot()?.let { pendingReportSnapshot = it; reportPicker.launch() }
                            }) { Text("导入赠送报告 CSV 并自动补抽") }
                            Text("报告只在本地读取。按当前奖品和中奖 UID 匹配，只补抽明确失败者；成功名额保留，未知/处理中状态、其他模组和非当前中奖者不处理。",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            state.reportSummary?.let { summary ->
                                Card(colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh)) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("上次报告：${summary.totalRows} 条", fontWeight = FontWeight.Medium)
                                        Text("确认成功 ${summary.confirmedSuccessUids.size} 人 · 自动补抽 ${summary.replacedFailureUids.size} 人 · 状态待确认 ${summary.unresolvedUids.size} 人",
                                            style = MaterialTheme.typography.bodySmall)
                                        Text("忽略：其他模组 ${summary.otherItemRows} 条、非当前中奖者 ${summary.outsideWinnerRows} 条；合并重复 ${summary.duplicateRows} 条；保护此前成功 ${summary.protectedSuccessUids.size} 人。",
                                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                        if (summary.unresolvedStatuses.isNotEmpty()) Text(
                                            "未自动处理的状态：${summary.unresolvedStatuses.take(6).joinToString("、")}",
                                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                    }
                                }
                            }
                            OutlinedTextField(replacementText, { replacementText = it }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
                                label = { Text("批量补抽：粘贴当前中奖名单中已购的 UID") })
                            OutlinedButton(enabled = replacementText.isNotBlank(), onClick = {
                                runCatching { LotteryUidImport.fromText(replacementText) }
                                    .onSuccess { viewModel.replacePurchased(it); if (viewModel.state.value.error == null) replacementText = "" }
                                    .onFailure { viewModel.error(it.message ?: "UID 格式不正确") }
                            }) { Text("只补抽这些名额") }
                            HorizontalDivider()
                        }
                    }
                    itemsIndexed(result.winners(), key = { _, winner -> winner.uid }) { index, winner ->
                        Card(colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh)) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("${index + 1}. ${winner.nickname}", fontWeight = FontWeight.Medium)
                                    Text("UID ${winner.uid} · ${winner.stars} 星", style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(enabled = winner.uid !in result.deliveredUids,
                                    onClick = { viewModel.replacePurchased(setOf(winner.uid)) }) {
                                    Text(if (winner.uid in result.deliveredUids) "赠送成功" else "已购，换人")
                                }
                            }
                        }
                    }
                }
                item { Text("CSV 表头固定为：玩家uid,模组id。修改参与条件会清空旧结果；补抽后请重新导出。",
                    modifier = Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
            }
        }
    }
    dateTarget?.let { target ->
        val start = parseLotteryDate(state.form.startDate)
        val end = parseLotteryDate(state.form.endDate)
        val today = Clock.System.now().toLocalDateTime(TimeZone.of("Asia/Shanghai")).date
        AppDatePickerDialog(
            initialDate = if (target == 0) start else end,
            minDate = if (target == 1) start else null,
            maxDate = if (target == 0) minOf(end, today) else today,
            onConfirm = { viewModel.selectDate(it, start = target == 0) },
            onDismiss = { dateTarget = null },
        )
    }
}

@Composable
private fun LotteryField(label: String, value: String, enabled: Boolean, modifier: Modifier = Modifier,
                         onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, modifier.fillMaxWidth(), enabled = enabled, label = { Text(label) }, singleLine = true)
}
