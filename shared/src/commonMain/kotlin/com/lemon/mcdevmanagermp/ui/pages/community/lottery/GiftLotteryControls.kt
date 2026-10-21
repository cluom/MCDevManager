package com.lemon.mcdevmanagermp.ui.pages.community.lottery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.lemon.mcdevmanagermp.ui.components.BelowAnchorPositionProvider
import com.lemon.mcdevmanagermp.ui.components.ModSelectOption
import com.lemon.mcdevmanagermp.ui.theme.LocalAppColors

internal fun filterLotteryMods(options: List<ModSelectOption>, query: String): List<ModSelectOption> {
    val keyword = query.trim()
    return options.filter { keyword.isEmpty() || it.name.contains(keyword, ignoreCase = true) || it.id.contains(keyword) }
}

/** 选择值只来自作品列表；文本输入仅用于下拉菜单内部的本地搜索。 */
@Composable
internal fun LotteryModSelector(
    label: String,
    selectedId: String,
    options: List<ModSelectOption>,
    enabled: Boolean,
    loading: Boolean,
    onSelect: (String) -> Unit,
) {
    val colors = LocalAppColors.current
    val density = LocalDensity.current
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var anchorWidth by remember { mutableIntStateOf(0) }
    val selected = options.find { it.id == selectedId }
    val filtered = remember(options, query) { filterLotteryMods(options, query) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box(Modifier.fillMaxWidth().onSizeChanged { anchorWidth = it.width }) {
            OutlinedButton(
                onClick = { query = ""; expanded = !expanded },
                enabled = enabled,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            ) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    Text(selected?.name?.ifBlank { selectedId } ?: if (loading) "正在读取作品列表…" else "请选择模组",
                        style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (selected != null) Text(selected.id, style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant, textAlign = TextAlign.Start)
                }
                Icon(Icons.Default.KeyboardArrowDown, if (expanded) "收起模组列表" else "展开模组列表")
            }
            if (expanded && enabled) {
                Popup(
                    popupPositionProvider = BelowAnchorPositionProvider,
                    onDismissRequest = { expanded = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    Surface(Modifier.width(with(density) { anchorWidth.toDp() }),
                        shape = RoundedCornerShape(12.dp), color = colors.scheme.surface,
                        tonalElevation = 3.dp, shadowElevation = 6.dp) {
                        Column {
                            val focusRequester = remember { FocusRequester() }
                            LaunchedEffect(Unit) { focusRequester.requestFocus() }
                            OutlinedTextField(query, { query = it },
                                modifier = Modifier.fillMaxWidth().padding(10.dp).focusRequester(focusRequester),
                                singleLine = true, placeholder = { Text("搜索模组名称或 ID") },
                                leadingIcon = { Icon(Icons.Default.Search, null) })
                            if (filtered.isEmpty()) Text("没有匹配的模组", Modifier.padding(16.dp),
                                color = colors.onSurfaceVariant)
                            else LazyColumn(Modifier.heightIn(max = 260.dp)) {
                                items(filtered, key = { it.id }) { option ->
                                    DropdownMenuItem(
                                        text = { Column {
                                            Text(option.name.ifBlank { option.id }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(option.id, style = MaterialTheme.typography.labelSmall,
                                                color = colors.onSurfaceVariant)
                                        } },
                                        trailingIcon = { if (option.id == selectedId) Icon(Icons.Default.Check, "已选择") },
                                        onClick = { onSelect(option.id); expanded = false },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LotteryDateField(label: String, value: String, enabled: Boolean,
                              modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = enabled,
            shape = RoundedCornerShape(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(value, style = MaterialTheme.typography.bodyLarge)
                Icon(Icons.Default.CalendarMonth, "选择$label")
            }
        }
    }
}
