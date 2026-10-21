package com.lemon.mcdevmanagermp.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.lemon.mcdevmanagermp.ui.theme.LocalAppColors
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

// Material3 用 UTC 午夜表示日历日期，不是业务时区中的真实时间点。
// 若传入北京时间午夜，组件会归一化到前一天 UTC 午夜，造成日期回显偏移。
internal fun datePickerUtcMillis(date: LocalDate): Long =
    date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

internal fun dateFromPickerUtcMillis(millis: Long): LocalDate =
    Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date

/**
 * 通用日期选择弹窗：基于 Material3 DatePicker，适配应用主题色。
 *
 * 与分析页 `RealtimeProfitDatePicker` 的区别：本组件**允许选择未来日期**（折扣为未来时段），
 * 并通过 [minDate]/[maxDate] 可选地约束可选范围。
 *
 * @param initialDate 初始选中日期（为 null 时默认不选）
 * @param minDate 可选最早日期
 * @param maxDate 可选最晚日期
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDatePickerDialog(
    initialDate: LocalDate?,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    minDate: LocalDate? = null,
    maxDate: LocalDate? = null
) {
    val colors = LocalAppColors.current

    val initialMillis = remember(initialDate) {
        initialDate?.let(::datePickerUtcMillis)
    }
    val minMillis = remember(minDate) {
        minDate?.let(::datePickerUtcMillis)
    }
    val maxMillis = remember(maxDate) {
        maxDate?.let(::datePickerUtcMillis)
    }

    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialMillis,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val minOk = minMillis == null || utcTimeMillis >= minMillis
                val maxOk = maxMillis == null || utcTimeMillis <= maxMillis
                return minOk && maxOk
            }
        }
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        confirmButton = {
            Button(
                onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        onConfirm(dateFromPickerUtcMillis(millis))
                    }
                    onDismiss()
                },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                enabled = datePickerState.selectedDateMillis != null
            ) {
                Text(
                    text = "确定",
                    color = colors.onPrimary,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = "取消",
                    color = colors.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        colors = DatePickerDefaults.colors(
            containerColor = colors.surfaceContainerHigh,
            titleContentColor = colors.textColor,
            headlineContentColor = colors.textColor,
            weekdayContentColor = colors.primary,
            subheadContentColor = colors.onSurfaceVariant,
            yearContentColor = colors.onSurfaceVariant,
            currentYearContentColor = colors.primary,
            selectedYearContentColor = colors.onPrimary,
            selectedYearContainerColor = colors.primary,
            selectedDayContentColor = colors.onPrimary
        )
    ) {
        DatePicker(state = datePickerState)
    }
}
