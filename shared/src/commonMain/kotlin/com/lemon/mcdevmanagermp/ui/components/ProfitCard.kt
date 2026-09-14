package com.lemon.mcdevmanagermp.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lemon.mcdevmanagermp.domain.main.ProfitPeriod
import com.lemon.mcdevmanagermp.domain.profitsharing.ProfitAllocationSummary
import com.lemon.mcdevmanagermp.domain.profitsharing.scaleToNetTotal
import com.lemon.mcdevmanagermp.ui.theme.LocalAppColors
import com.lemon.mcdevmanagermp.utils.ProfitData
import com.lemon.mcdevmanagermp.utils.calculationLabel
import com.lemon.mcdevmanagermp.utils.summaryRows
import com.lemon.mcdevmanagermp.utils.allocationNotice
import com.lemon.mcdevmanagermp.utils.extension.formatDecimal
import com.lemon.mcdevmanagermp.utils.getTaxMoney
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import mcdevmanagermpr.shared.generated.resources.Res
import mcdevmanagermpr.shared.generated.resources.ic_money
import org.jetbrains.compose.resources.painterResource

@Composable
fun ProfitCard(
    title: String,
    profitData: ProfitData,
    profitPeriod: ProfitPeriod? = null,
    allocationSummary: ProfitAllocationSummary? = null,
    isLoading: Boolean = true,
    expanded: Boolean = false,
    onToggleExpand: () -> Unit = {},
    onNavigateToDetail: (() -> Unit)? = null,
    onManageSharing: (() -> Unit)? = null
) {
    val colors = LocalAppColors.current
    val estimatedSettlement = if (profitData.hasIncome && profitData.settlement == null) {
        profitPeriod?.estimateSettlement(profitData.totalProfit)
    } else null
    val estimatedAllocation = estimatedSettlement?.let { estimate ->
        allocationSummary?.scaleToNetTotal(estimate - getTaxMoney(estimate))
    }

    if (!isLoading) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggleExpand
                )
                .animateContentSize(),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh),
            shape = RoundedCornerShape(12.dp),

        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(Res.drawable.ic_money),
                        contentDescription = "money",
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.textColor
                        )
                        profitPeriod?.let { period ->
                            Text(
                                text = period.toDisplayText(),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                        Text(
                            text = profitData.calculationLabel(),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant
                        )
                        allocationSummary?.takeIf { profitData.hasIncome }?.let { summary ->
                            Text(
                                text = if (summary.payouts.isEmpty()) {
                                    "尚未配置人员分账 · 点击展开管理"
                                } else {
                                    "已配置 ${summary.payouts.size} 人 · 点击展开查看分账"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.primary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    if (onNavigateToDetail != null) {
                        IconButton(
                            onClick = onNavigateToDetail,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "查看详情",
                                tint = colors.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = if (profitData.hasIncome) profitData.totalProfit.formatDecimal(2) else "—",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.primary
                        )
                        Text(
                            text = if (profitData.hasIncome) "税后净收益 ${profitData.netIncome.formatDecimal(2)}" else "等待账单",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                        estimatedSettlement?.let { estimate ->
                            Text(
                                text = "预计结算 ${estimate.formatDecimal(2)}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = colors.primary
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .padding(bottom = 12.dp)
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DetailSection(
                            color = colors.surfaceContainerHighest,
                            rows = profitData.summaryRows()
                        )

                        if (profitData.settlement == null && profitData.subsidyProfit.isNotEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(colors.surfaceContainerHighest, RoundedCornerShape(8.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "模组激励",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                profitData.subsidyProfit.entries.forEach { (itemId, value) ->
                                    DetailRow(
                                        label = profitData.moduleNames[itemId] ?: itemId,
                                        value = "${value.toInt()}",
                                        labelColor = colors.onSurfaceVariant,
                                        valueColor = colors.textColor
                                    )
                                }
                            }
                        }

                        if (profitData.hasIncome && profitData.settlement == null) Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DetailSection(
                                modifier = Modifier.weight(1f),
                                color = colors.surfaceContainerHighest,
                                rows = listOf(
                                    "流水激励" to "${profitData.profitSubsidy.toInt()}"
                                )
                            )
                            DetailSection(
                                modifier = Modifier.weight(1f),
                                color = colors.surfaceContainerHighest,
                                rows = listOf(
                                    "分成返还" to "${(profitData.subsidyPercent * 100).toInt()}%"
                                )
                            )
                        }

                        profitData.allocationNotice()?.let { notice ->
                            Text(notice, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        allocationSummary?.takeIf { profitData.hasIncome }?.let { summary ->
                            AllocationSummarySection(
                                summary = summary,
                                estimatedSummary = estimatedAllocation,
                                onManageSharing = onManageSharing
                            )
                        }
                    }
                }
            }
        }
    } else {
        ShimmerProfitCard()
    }
}

@Composable
private fun AllocationSummarySection(
    summary: ProfitAllocationSummary,
    estimatedSummary: ProfitAllocationSummary?,
    onManageSharing: (() -> Unit)?
) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainerHighest, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = if (estimatedSummary == null) {
                "人员分账（税后）"
            } else {
                "人员分账（当前 / 预计完整周期，税后）"
            },
            style = MaterialTheme.typography.labelMedium,
            color = colors.primary,
            fontWeight = FontWeight.SemiBold
        )
        if (summary.payouts.isEmpty()) {
            Text(
                text = "尚未添加分账人员",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        } else {
            val estimatedByPerson = estimatedSummary?.payouts?.associateBy { it.personId }.orEmpty()
            summary.payouts.forEach { payout ->
                DetailRow(
                    label = payout.personName,
                    value = estimatedByPerson[payout.personId]?.let { estimate ->
                        "${payout.amount.formatDecimal(2)} / ${estimate.amount.formatDecimal(2)}"
                    } ?: payout.amount.formatDecimal(2),
                    labelColor = colors.onSurfaceVariant,
                    valueColor = colors.textColor
                )
            }
        }
        if (summary.unassignedAmount > 0.005) {
            DetailRow(
                label = "未分配",
                value = estimatedSummary?.let {
                    "${summary.unassignedAmount.formatDecimal(2)} / ${it.unassignedAmount.formatDecimal(2)}"
                } ?: summary.unassignedAmount.formatDecimal(2),
                labelColor = colors.error,
                valueColor = colors.error
            )
        }
        DetailRow(
            label = if (estimatedSummary == null) "税后合计" else "税后合计 / 预计",
            value = estimatedSummary?.let {
                "${summary.netTotal.formatDecimal(2)} / ${it.netTotal.formatDecimal(2)}"
            } ?: summary.netTotal.formatDecimal(2),
            labelColor = colors.onSurfaceVariant,
            valueColor = colors.primary
        )
        onManageSharing?.let { onClick ->
            TextButton(
                onClick = onClick,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("管理人员与模组")
            }
        }
    }
}

private fun ProfitPeriod.toDisplayText(): String {
    val progress = if (elapsedDays > 0) "已统计 $elapsedDays/$totalDays 天" else "等待日数据"
    return "统计 ${startDate.toShortDate()}–${endDate.toShortDate()} · $progress"
}

private fun LocalDate.toShortDate(): String =
    "${month.number.toString().padStart(2, '0')}/${day.toString().padStart(2, '0')}"

@Composable
private fun ShimmerProfitCard() {
    val colors = LocalAppColors.current
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -300f,
        targetValue = 300f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerOffset"
    )
    val shimmerColors = listOf(
        colors.shimmer,
        colors.shimmer.copy(alpha = 0.3f),
        colors.shimmer
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh),
        shape = RoundedCornerShape(12.dp),

        ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Brush.linearGradient(shimmerColors, start = androidx.compose.ui.geometry.Offset(shimmerOffset, 0f), end = androidx.compose.ui.geometry.Offset(shimmerOffset + 300f, 0f)))
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Brush.linearGradient(shimmerColors, start = androidx.compose.ui.geometry.Offset(shimmerOffset, 0f), end = androidx.compose.ui.geometry.Offset(shimmerOffset + 300f, 0f)))
            )
            Spacer(modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Box(
                    modifier = Modifier
                        .width(72.dp)
                        .height(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Brush.linearGradient(shimmerColors, start = androidx.compose.ui.geometry.Offset(shimmerOffset, 0f), end = androidx.compose.ui.geometry.Offset(shimmerOffset + 300f, 0f)))
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .width(96.dp)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Brush.linearGradient(shimmerColors, start = androidx.compose.ui.geometry.Offset(shimmerOffset, 0f), end = androidx.compose.ui.geometry.Offset(shimmerOffset + 300f, 0f)))
                )
            }
        }
    }
}

@Composable
private fun DetailSection(
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color,
    rows: List<Pair<String, String>>
) {
    val colors = LocalAppColors.current
    Column(
        modifier = modifier
            .background(color, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        rows.forEach { (label, value) ->
            DetailRow(
                label = label,
                value = value,
                labelColor = colors.onSurfaceVariant,
                valueColor = colors.textColor
            )
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    labelColor: androidx.compose.ui.graphics.Color,
    valueColor: androidx.compose.ui.graphics.Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = labelColor
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor
        )
    }
}
