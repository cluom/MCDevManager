package com.lemon.mcdevmanagermp.ui.pages.profitsharing

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lemon.mcdevmanagermp.data.vo.netease.resource.ResourceData
import com.lemon.mcdevmanagermp.domain.profitsharing.ProfitPerson
import com.lemon.mcdevmanagermp.ui.components.CollapsingTopBar
import com.lemon.mcdevmanagermp.ui.components.collectUiEffect
import com.lemon.mcdevmanagermp.ui.theme.LocalAppColors
import com.lemon.mcdevmanagermp.utils.extension.formatDecimal

@Composable
fun ProfitSharingPage(onBack: () -> Unit) {
    val viewModel = remember { ProfitSharingViewModel() }
    val state by viewModel.state.collectAsState()
    val colors = LocalAppColors.current
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var pendingDelete by remember { mutableStateOf<ProfitPerson?>(null) }

    viewModel.effect.collectUiEffect { effect ->
        when (effect) {
            is ProfitSharingEffect.ShowToast -> showToast(effect.message)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.dispatch(ProfitSharingAction.Load)
    }

    Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
        CollapsingTopBar(
            title = "人员与模组分账",
            collapseFraction = 0f,
            onBack = onBack
        )

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().widthIn(max = 1000.dp),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 12.dp,
                    end = 16.dp,
                    bottom = navBarBottom + 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = "先添加参与分账的人员，再为每个模组选择归属。单人自动100%；多人按权重比例分配。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant
                    )
                }

                item {
                    PeopleManagerCard(
                        people = state.people,
                        newPersonName = state.newPersonName,
                        onNameChange = {
                            viewModel.dispatch(ProfitSharingAction.ChangePersonName(it))
                        },
                        onAdd = { viewModel.dispatch(ProfitSharingAction.AddPerson) },
                        onDelete = { pendingDelete = it }
                    )
                }

                item {
                    Text(
                        text = "模组归属",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.textColor,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                if (!state.isLoading && state.resources.isEmpty()) {
                    item {
                        Text(
                            text = "暂无可配置的模组",
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp)
                        )
                    }
                }

                items(state.resources, key = { it.itemId }) { resource ->
                    ModuleOwnershipCard(
                        resource = resource,
                        people = state.people,
                        draft = state.ownershipDrafts[resource.itemId].orEmpty(),
                        isSaving = resource.itemId in state.savingItemIds,
                        onToggleOwner = { personId ->
                            viewModel.dispatch(
                                ProfitSharingAction.ToggleOwner(resource.itemId, personId)
                            )
                        },
                        onWeightChange = { personId, value ->
                            viewModel.dispatch(
                                ProfitSharingAction.ChangeWeight(
                                    resource.itemId,
                                    personId,
                                    value
                                )
                            )
                        },
                        onSave = {
                            viewModel.dispatch(ProfitSharingAction.SaveModule(resource.itemId))
                        }
                    )
                }
            }

            if (state.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(36.dp),
                    color = colors.primary
                )
            }
        }
    }

    pendingDelete?.let { person ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除人员") },
            text = { Text("删除“${person.name}”后，其所有模组关联也会一并移除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.dispatch(ProfitSharingAction.DeletePerson(person.id))
                        pendingDelete = null
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun PeopleManagerCard(
    people: List<ProfitPerson>,
    newPersonName: String,
    onNameChange: (String) -> Unit,
    onAdd: () -> Unit,
    onDelete: (ProfitPerson) -> Unit
) {
    val colors = LocalAppColors.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "分账人员",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.textColor
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newPersonName,
                    onValueChange = onNameChange,
                    label = { Text("人员名称") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = onAdd, enabled = newPersonName.isNotBlank()) {
                    Text("添加")
                }
            }

            if (people.isEmpty()) {
                Text(
                    text = "尚未添加人员",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(people, key = { it.id }) { person ->
                        Card(colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHighest)) {
                            Row(
                                modifier = Modifier.padding(start = 12.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(person.name, color = colors.textColor)
                                IconButton(onClick = { onDelete(person) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "删除${person.name}",
                                        tint = colors.error,
                                        modifier = Modifier.size(18.dp)
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
private fun ModuleOwnershipCard(
    resource: ResourceData,
    people: List<ProfitPerson>,
    draft: Map<Long, String>,
    isSaving: Boolean,
    onToggleOwner: (Long) -> Unit,
    onWeightChange: (Long, String) -> Unit,
    onSave: () -> Unit
) {
    val colors = LocalAppColors.current
    val selectedCount = draft.size
    val parsedWeights = draft.mapValues { it.value.toDoubleOrNull() ?: 0.0 }
    val totalWeight = parsedWeights.values.filter { it > 0.0 }.sum()

    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = resource.itemName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "ID ${resource.itemId}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant
            )
            HorizontalDivider(color = colors.outlineVariant)

            if (people.isEmpty()) {
                Text("请先添加分账人员", color = colors.onSurfaceVariant)
            } else {
                people.forEach { person ->
                    val selected = person.id in draft
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = { onToggleOwner(person.id) }
                        )
                        Text(
                            text = person.name,
                            color = colors.textColor,
                            modifier = Modifier.weight(1f)
                        )
                        if (selectedCount > 1 && selected) {
                            OutlinedTextField(
                                value = draft[person.id].orEmpty(),
                                onValueChange = { onWeightChange(person.id, it) },
                                label = { Text("权重") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                supportingText = {
                                    val ratio = if (totalWeight > 0.0) {
                                        (parsedWeights[person.id] ?: 0.0) / totalWeight * 100.0
                                    } else {
                                        0.0
                                    }
                                    Text("占比 ${ratio.formatDecimal(1)}%")
                                },
                                modifier = Modifier.width(132.dp)
                            )
                        } else if (selected) {
                            Text(
                                text = "100%",
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.primary
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when (selectedCount) {
                        0 -> "当前：未分配"
                        1 -> "当前：单人100%"
                        else -> "当前：$selectedCount 人按权重分配"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                Button(onClick = onSave, enabled = !isSaving && people.isNotEmpty()) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("保存")
                }
            }
        }
    }
}
