package com.lemon.mcdevmanagermp

import com.lemon.mcdevmanagermp.data.db.entity.AccountEntity
import com.lemon.mcdevmanagermp.utils.WidgetSessionSnapshot
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsWidgetBridgeTest {
    private fun account(id: Long) = AccountEntity(id, "账户$id", "{\"S_INFO\":\"db%2Bvalue==\"}", 1,
        email = "private@example.com", password = "never-export-password", rememberPassword = true)

    @Test
    fun startupUsesSavedCookiesButBoundSessionUsesWireValuesWithoutPasswords() {
        val builder = WindowsWidgetSnapshotBuilder()
        val initial = builder.build(listOf(account(1)), WidgetSessionSnapshot(null, 0, emptyMap()))
        assertEquals("db%2Bvalue==", initial.accounts.single().cookies["S_INFO"])
        val bound = builder.build(listOf(account(1)), WidgetSessionSnapshot(1, 0, mapOf("S_INFO" to "live%2B==")))
        val json = Json.encodeToString(bound)
        assertTrue(json.contains("live%2B=="))
        assertFalse(json.contains("never-export-password"))
        assertFalse(json.contains("private@example.com"))
    }

    @Test
    fun revokedAndDeletedAccountsAreNotReexportedFromStaleDatabaseCookies() {
        val builder = WindowsWidgetSnapshotBuilder()
        builder.build(listOf(account(1), account(2)), WidgetSessionSnapshot(1, 0, mapOf("S_INFO" to "first")))
        val clear = builder.build(listOf(account(1), account(2)), WidgetSessionSnapshot(null, 1, emptyMap()))
        assertEquals(listOf("2"), clear.accounts.map { it.id })
        val switched = builder.build(listOf(account(1), account(2)), WidgetSessionSnapshot(2, 1, mapOf("S_INFO" to "second")))
        assertEquals(listOf("2"), switched.accounts.map { it.id })
        assertEquals(emptyList(), builder.build(emptyList(), WidgetSessionSnapshot(null, 2, emptyMap())).accounts)
    }
}
