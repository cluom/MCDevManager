package com.lemon.mcdevmanagermp.ui.pages.profitsharing

import androidx.lifecycle.viewModelScope
import com.lemon.mcdevmanagermp.data.common.AppContext
import com.lemon.mcdevmanagermp.data.common.NetworkState
import com.lemon.mcdevmanagermp.data.repository.AccountRepositoryImpl
import com.lemon.mcdevmanagermp.data.repository.ProfitSharingRepositoryImpl
import com.lemon.mcdevmanagermp.data.repository.ResourceRepositoryImpl
import com.lemon.mcdevmanagermp.domain.profitsharing.ModuleOwnership
import com.lemon.mcdevmanagermp.domain.resource.GetResourceListUseCase
import com.lemon.mcdevmanagermp.ui.base.BaseViewModel
import com.lemon.mcdevmanagermp.utils.Logger
import kotlinx.coroutines.launch

class ProfitSharingViewModel : BaseViewModel<
        ProfitSharingState,
        ProfitSharingAction,
        ProfitSharingEffect
        >(ProfitSharingState()) {

    private val repository = ProfitSharingRepositoryImpl.INSTANCE
    private val accountRepository = AccountRepositoryImpl.INSTANCE
    private val getResourceList = GetResourceListUseCase(ResourceRepositoryImpl.INSTANCE)
    private var accountKey: String = ""

    override fun dispatch(action: ProfitSharingAction) {
        when (action) {
            ProfitSharingAction.Load -> load()
            is ProfitSharingAction.ChangePersonName -> setState {
                copy(newPersonName = action.value)
            }

            ProfitSharingAction.AddPerson -> addPerson()
            is ProfitSharingAction.DeletePerson -> deletePerson(action.personId)
            is ProfitSharingAction.ToggleOwner -> toggleOwner(action.itemId, action.personId)
            is ProfitSharingAction.ChangeWeight -> changeWeight(
                action.itemId,
                action.personId,
                action.value
            )

            is ProfitSharingAction.SaveModule -> saveModule(action.itemId)
        }
    }

    private fun load() {
        viewModelScope.launch {
            setState { copy(isLoading = true) }
            accountKey = accountRepository.getLastUsedAccount()?.nickname
                ?: AppContext.userInfo?.nickname
                ?: ""
            if (accountKey.isBlank()) {
                setState { copy(isLoading = false) }
                sendEffect(ProfitSharingEffect.ShowToast("未找到当前账号"))
                return@launch
            }

            val people = repository.getPeople(accountKey)
            val ownerships = repository.getOwnerships(accountKey)
            when (val result = getResourceList("pe")) {
                is NetworkState.Success -> setState {
                    copy(
                        isLoading = false,
                        people = people,
                        resources = result.data.orEmpty(),
                        ownershipDrafts = ownerships.toDrafts()
                    )
                }

                is NetworkState.Error -> {
                    Logger.e("加载分账模组失败: ${result.msg}", result.e)
                    setState {
                        copy(
                            isLoading = false,
                            people = people,
                            ownershipDrafts = ownerships.toDrafts()
                        )
                    }
                    sendEffect(ProfitSharingEffect.ShowToast("模组列表加载失败"))
                }
            }
        }
    }

    private fun addPerson() {
        val name = state.value.newPersonName.trim()
        if (name.isBlank()) {
            sendEffect(ProfitSharingEffect.ShowToast("请输入人员名称"))
            return
        }
        if (state.value.people.any { it.name == name }) {
            sendEffect(ProfitSharingEffect.ShowToast("人员名称已存在"))
            return
        }
        viewModelScope.launch {
            runCatching { repository.addPerson(accountKey, name) }
                .onSuccess {
                    reloadLocalData()
                    setState { copy(newPersonName = "") }
                    sendEffect(ProfitSharingEffect.ShowToast("已添加 $name"))
                }
                .onFailure {
                    Logger.e("添加分账人员失败", it)
                    sendEffect(ProfitSharingEffect.ShowToast("添加失败"))
                }
        }
    }

    private fun deletePerson(personId: Long) {
        viewModelScope.launch {
            runCatching { repository.deletePerson(personId) }
                .onSuccess {
                    reloadLocalData()
                    sendEffect(ProfitSharingEffect.ShowToast("人员已删除"))
                }
                .onFailure {
                    Logger.e("删除分账人员失败", it)
                    sendEffect(ProfitSharingEffect.ShowToast("删除失败"))
                }
        }
    }

    private fun toggleOwner(itemId: String, personId: Long) {
        setState {
            val moduleDraft = ownershipDrafts[itemId].orEmpty().toMutableMap()
            if (personId in moduleDraft) {
                moduleDraft.remove(personId)
            } else {
                moduleDraft[personId] = "1"
            }
            copy(ownershipDrafts = ownershipDrafts + (itemId to moduleDraft))
        }
    }

    private fun changeWeight(itemId: String, personId: Long, value: String) {
        if (value.any { !it.isDigit() && it != '.' }) return
        setState {
            val moduleDraft = ownershipDrafts[itemId].orEmpty().toMutableMap()
            if (personId in moduleDraft) moduleDraft[personId] = value
            copy(ownershipDrafts = ownershipDrafts + (itemId to moduleDraft))
        }
    }

    private fun saveModule(itemId: String) {
        val draft = state.value.ownershipDrafts[itemId].orEmpty()
        val ownerships = if (draft.size <= 1) {
            draft.keys.map { ModuleOwnership(itemId, it, 1.0) }
        } else {
            draft.mapNotNull { (personId, weightText) ->
                val weight = weightText.toDoubleOrNull()
                if (weight != null && weight > 0.0) {
                    ModuleOwnership(itemId, personId, weight)
                } else {
                    null
                }
            }
        }
        if (draft.size > 1 && ownerships.size != draft.size) {
            sendEffect(ProfitSharingEffect.ShowToast("多人归属时，每个人都要填写大于0的权重"))
            return
        }

        viewModelScope.launch {
            setState { copy(savingItemIds = savingItemIds + itemId) }
            runCatching { repository.replaceOwnerships(itemId, ownerships) }
                .onSuccess {
                    reloadLocalData()
                    sendEffect(ProfitSharingEffect.ShowToast("模组归属已保存"))
                }
                .onFailure {
                    Logger.e("保存模组归属失败", it)
                    sendEffect(ProfitSharingEffect.ShowToast("保存失败"))
                }
            setState { copy(savingItemIds = savingItemIds - itemId) }
        }
    }

    private suspend fun reloadLocalData() {
        val people = repository.getPeople(accountKey)
        val ownerships = repository.getOwnerships(accountKey)
        setState {
            copy(
                people = people,
                ownershipDrafts = ownerships.toDrafts()
            )
        }
    }

    private fun List<ModuleOwnership>.toDrafts(): Map<String, Map<Long, String>> =
        groupBy { it.itemId }.mapValues { (_, owners) ->
            owners.associate { it.personId to it.weight.toDisplayWeight() }
        }

    private fun Double.toDisplayWeight(): String =
        if (this % 1.0 == 0.0) toInt().toString() else toString()
}
