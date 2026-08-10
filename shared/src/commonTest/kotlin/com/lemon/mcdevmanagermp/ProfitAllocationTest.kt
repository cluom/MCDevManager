package com.lemon.mcdevmanagermp

import com.lemon.mcdevmanagermp.domain.profitsharing.ModuleOwnership
import com.lemon.mcdevmanagermp.domain.profitsharing.ProfitPerson
import com.lemon.mcdevmanagermp.domain.profitsharing.calculateProfitAllocation
import com.lemon.mcdevmanagermp.utils.calculateProfit
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfitAllocationTest {

    private val alice = ProfitPerson(1, "account", "甲", 1)
    private val bob = ProfitPerson(2, "account", "乙", 2)

    @Test
    fun multiple_owners_split_module_net_income_by_weight() {
        val profit = calculateProfit(
            itemProfitMap = mapOf("mod-a" to 200_000.0),
            moduleNames = mapOf("mod-a" to "模组A")
        )

        val result = calculateProfitAllocation(
            profitData = profit,
            people = listOf(alice, bob),
            ownerships = listOf(
                ModuleOwnership("mod-a", alice.id, 1.0),
                ModuleOwnership("mod-a", bob.id, 3.0)
            )
        )

        val amounts = result.payouts.associate { it.personId to it.amount }
        assertEquals(result.netTotal * 0.25, amounts.getValue(alice.id), 0.001)
        assertEquals(result.netTotal * 0.75, amounts.getValue(bob.id), 0.001)
        assertEquals(0.0, result.unassignedAmount, 0.001)
    }

    @Test
    fun single_owner_receives_full_module_income_without_weight_requirement() {
        val profit = calculateProfit(mapOf("mod-a" to 200_000.0))

        val result = calculateProfitAllocation(
            profitData = profit,
            people = listOf(alice),
            ownerships = listOf(ModuleOwnership("mod-a", alice.id, 0.0))
        )

        assertEquals(result.netTotal, result.payouts.single().amount, 0.001)
        assertEquals(0.0, result.unassignedAmount, 0.001)
    }

    @Test
    fun unowned_modules_are_reported_as_unassigned() {
        val profit = calculateProfit(mapOf("mod-a" to 200_000.0))

        val result = calculateProfitAllocation(profit, listOf(alice), emptyList())

        assertEquals(0.0, result.payouts.single().amount, 0.001)
        assertEquals(result.netTotal, result.unassignedAmount, 0.001)
    }
}
