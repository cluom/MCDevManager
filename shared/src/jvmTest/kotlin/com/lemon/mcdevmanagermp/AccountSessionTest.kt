package com.lemon.mcdevmanagermp

import com.lemon.mcdevmanagermp.data.common.JSONConverter
import com.lemon.mcdevmanagermp.data.common.NetworkState
import com.lemon.mcdevmanagermp.data.vo.netease.user.UserInfoVO
import com.lemon.mcdevmanagermp.domain.account.*
import com.lemon.mcdevmanagermp.domain.user.UserRepository
import com.lemon.mcdevmanagermp.utils.PasswordCrypto
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountSessionTest {
    private class Accounts : AccountRepository {
        var saved = Account(42, "test", "{\"NTES_SESS\":\"old\"}", 1)
        override suspend fun getAllAccounts() = listOf(saved)
        override suspend fun getLastUsedAccount() = saved
        override suspend fun getAccountByNickname(nickname: String) = saved.takeIf { it.nickname == nickname }
        override suspend fun upsertAccount(account: Account) { saved = account }
        override suspend fun deleteAccount(id: Long) = Unit
        override suspend fun updateNicknameById(id: Long, nickname: String) = Unit
    }

    private class Cookies : CookieRepository {
        var values = mapOf("NTES_SESS" to "old")
        var boundId: Long? = null
        override fun getAllCookiesMap() = values
        override fun addCookie(key: String, value: String) { values = values + (key to value) }
        override fun clearCookies() { values = emptyMap(); boundId = null }
        override fun bindAccount(accountId: Long, persistedCookies: Map<String, String>) { boundId = accountId }
    }

    private fun user(cookies: Cookies) = object : UserRepository {
        override suspend fun getUserInfo(): NetworkState<UserInfoVO> {
            cookies.addCookie("NTES_SESS", "refreshed==")
            return NetworkState.Success(UserInfoVO(0, 1, nickname = "test", income = "0",
                onSaleItemCount = 0, curMonthIncentiveFund = 0.0, unExtractIncome = "0", prerequisiteSwitch = false))
        }
        override suspend fun getOverview() = error("unused")
        override suspend fun getLevelInfo() = error("unused")
    }

    private fun assertRefreshed(accounts: Accounts, cookies: Cookies) {
        assertEquals("refreshed==", JSONConverter.decodeFromString<Map<String, String>>(accounts.saved.cookiesJson)["NTES_SESS"])
        assertEquals(42L, cookies.boundId)
    }

    @Test
    fun manualLoginSavesSnapshotAfterUserValidation() = runBlocking {
        val accounts = Accounts()
        val cookies = Cookies()
        SaveAccountUseCase(accounts, user(cookies), cookies)()
        assertRefreshed(accounts, cookies)
    }

    @Test
    fun autoLoginSavesRefreshedCookies() = runBlocking {
        val accounts = Accounts()
        val cookies = Cookies()
        cookies.addCookie("stale-account-cookie", "must-be-cleared")
        assertEquals(true, AutoLoginUseCase(accounts, user(cookies), cookies)())
        assertRefreshed(accounts, cookies)
        assertEquals(setOf("NTES_SESS"), cookies.values.keys)
    }

    @Test
    fun switchingAccountSavesRefreshedCookies() = runBlocking {
        val accounts = Accounts()
        val cookies = Cookies()
        AccountManageUseCase(accounts, user(cookies), cookies).switchAccount(accounts.saved)
        assertRefreshed(accounts, cookies)
    }

    @Test
    fun rememberedPasswordEncryptionKeepsRefreshedCookiesAndAccountBinding() = runBlocking {
        val accounts = Accounts()
        val cookies = Cookies()
        SaveAccountUseCase(accounts, user(cookies), cookies)(
            email = "test@example.com", password = "test-password", rememberPassword = true,
        )
        assertTrue(PasswordCrypto.isEncrypted(accounts.saved.password))
        assertEquals("test-password", PasswordCrypto.decrypt(accounts.saved.password))
        assertEquals(true, accounts.saved.rememberPassword)
        assertRefreshed(accounts, cookies)
    }

    @Test
    fun cookieLoginEncryptsLegacyPasswordWithoutLosingSavedEmail() = runBlocking {
        val accounts = Accounts().apply {
            saved = saved.copy(email = "test@example.com", password = "legacy-password", rememberPassword = true)
        }
        val cookies = Cookies()
        SaveAccountUseCase(accounts, user(cookies), cookies)()
        assertEquals("test@example.com", accounts.saved.email)
        assertTrue(PasswordCrypto.isEncrypted(accounts.saved.password))
        assertEquals("legacy-password", PasswordCrypto.decrypt(accounts.saved.password))
        assertRefreshed(accounts, cookies)
    }

    @Test
    fun cookieLoginPreservesExistingCiphertextWithoutEncryptingItAgain() = runBlocking {
        val ciphertext = PasswordCrypto.encrypt("test-password")
        val accounts = Accounts().apply {
            saved = saved.copy(email = "test@example.com", password = ciphertext, rememberPassword = true)
        }
        val cookies = Cookies()
        SaveAccountUseCase(accounts, user(cookies), cookies)()
        assertEquals(ciphertext, accounts.saved.password)
        assertEquals("test-password", PasswordCrypto.decrypt(accounts.saved.password))
        assertRefreshed(accounts, cookies)
    }

    @Test
    fun disablingRememberPasswordClearsCiphertextWithoutLosingSession() = runBlocking {
        val accounts = Accounts().apply {
            saved = saved.copy(email = "test@example.com", password = PasswordCrypto.encrypt("old"),
                rememberPassword = true)
        }
        val cookies = Cookies()
        SaveAccountUseCase(accounts, user(cookies), cookies)(
            email = "test@example.com", password = "new", rememberPassword = false,
        )
        assertEquals("", accounts.saved.password)
        assertEquals(false, accounts.saved.rememberPassword)
        assertRefreshed(accounts, cookies)
    }
}
