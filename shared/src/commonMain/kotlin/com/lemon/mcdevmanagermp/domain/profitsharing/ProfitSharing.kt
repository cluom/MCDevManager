package com.lemon.mcdevmanagermp.domain.profitsharing

import com.lemon.mcdevmanagermp.utils.ProfitData
import com.lemon.mcdevmanagermp.utils.getTaxMoney
import com.lemon.mcdevmanagermp.utils.toModuleIncomeDetails
import kotlinx.coroutines.flow.Flow

data class ProfitPerson(
    val id: Long,
    val accountKey: String,
    val name: String,
    val createdAt: Long
)

data class ModuleOwnership(
    val itemId: String,
    val personId: Long,
    val weight: Double
)

data class PersonPayout(
    val personId: Long,
    val personName: String,
    val amount: Double
)

data class ProfitAllocationSummary(
    val payouts: List<PersonPayout> = emptyList(),
    val unassignedAmount: Double = 0.0,
    val netTotal: Double = 0.0
)

fun ProfitAllocationSummary.scaleToNetTotal(targetNetTotal: Double): ProfitAllocationSummary? {
    if (netTotal <= 0.0 || targetNetTotal <= 0.0) return null
    val factor = targetNetTotal / netTotal
    return copy(
        payouts = payouts.map { it.copy(amount = it.amount * factor) },
        unassignedAmount = unassignedAmount * factor,
        netTotal = targetNetTotal
    )
}

interface ProfitSharingRepository {
    val changes: Flow<Unit>

    suspend fun getPeople(accountKey: String): List<ProfitPerson>
    suspend fun getOwnerships(accountKey: String): List<ModuleOwnership>
    suspend fun addPerson(accountKey: String, name: String): Long
    suspend fun deletePerson(personId: Long)
    suspend fun replaceOwnerships(itemId: String, ownerships: List<ModuleOwnership>)
}

fun calculateProfitAllocation(
    profitData: ProfitData,
    people: List<ProfitPerson>,
    ownerships: List<ModuleOwnership>
): ProfitAllocationSummary {
    val netTotal = profitData.totalProfit - getTaxMoney(profitData.totalProfit)
    if (netTotal <= 0.0) {
        return ProfitAllocationSummary(
            payouts = people.map { PersonPayout(it.id, it.name, 0.0) },
            netTotal = netTotal.coerceAtLeast(0.0)
        )
    }

    val moduleDetails = profitData.toModuleIncomeDetails()
    val moduleBaseTotal = moduleDetails.sumOf { it.totalIncome }
    val netFactor = netTotal / profitData.totalProfit
    val peopleById = people.associateBy { it.id }
    val ownershipsByItem = ownerships.groupBy { it.itemId }
    val payoutByPerson = people.associate { it.id to 0.0 }.toMutableMap()

    moduleDetails.forEach { module ->
        val globalSubsidyShare = if (moduleBaseTotal > 0.0) {
            profitData.profitSubsidy * module.totalIncome / moduleBaseTotal
        } else {
            0.0
        }
        val moduleNetIncome = (module.totalIncome + globalSubsidyShare) * netFactor
        val owners = ownershipsByItem[module.moduleId]
            .orEmpty()
            .filter { it.personId in peopleById }

        when (owners.size) {
            0 -> Unit
            1 -> {
                val owner = owners.first()
                payoutByPerson[owner.personId] = payoutByPerson.getValue(owner.personId) + moduleNetIncome
            }

            else -> {
                val validOwners = owners.filter { it.weight > 0.0 }
                val totalWeight = validOwners.sumOf { it.weight }
                if (totalWeight > 0.0) {
                    validOwners.forEach { owner ->
                        val amount = moduleNetIncome * owner.weight / totalWeight
                        payoutByPerson[owner.personId] = payoutByPerson.getValue(owner.personId) + amount
                    }
                }
            }
        }
    }

    val payouts = people.map { person ->
        PersonPayout(
            personId = person.id,
            personName = person.name,
            amount = payoutByPerson[person.id] ?: 0.0
        )
    }.sortedByDescending { it.amount }
    val allocated = payouts.sumOf { it.amount }

    return ProfitAllocationSummary(
        payouts = payouts,
        unassignedAmount = (netTotal - allocated).coerceAtLeast(0.0),
        netTotal = netTotal
    )
}
