package com.lemon.mcdevmanagermp.ui.pages.profitsharing

import com.lemon.mcdevmanagermp.data.vo.netease.resource.ResourceData
import com.lemon.mcdevmanagermp.domain.profitsharing.ProfitPerson
import com.lemon.mcdevmanagermp.utils.extension.IUiAction
import com.lemon.mcdevmanagermp.utils.extension.IUiEffect
import com.lemon.mcdevmanagermp.utils.extension.IUiState

data class ProfitSharingState(
    val isLoading: Boolean = true,
    val newPersonName: String = "",
    val people: List<ProfitPerson> = emptyList(),
    val resources: List<ResourceData> = emptyList(),
    val ownershipDrafts: Map<String, Map<Long, String>> = emptyMap(),
    val savingItemIds: Set<String> = emptySet()
) : IUiState

sealed interface ProfitSharingAction : IUiAction {
    data object Load : ProfitSharingAction
    data class ChangePersonName(val value: String) : ProfitSharingAction
    data object AddPerson : ProfitSharingAction
    data class DeletePerson(val personId: Long) : ProfitSharingAction
    data class ToggleOwner(val itemId: String, val personId: Long) : ProfitSharingAction
    data class ChangeWeight(
        val itemId: String,
        val personId: Long,
        val value: String
    ) : ProfitSharingAction
    data class SaveModule(val itemId: String) : ProfitSharingAction
}

sealed interface ProfitSharingEffect : IUiEffect {
    data class ShowToast(val message: String) : ProfitSharingEffect
}
