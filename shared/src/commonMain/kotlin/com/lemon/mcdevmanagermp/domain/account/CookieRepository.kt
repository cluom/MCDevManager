package com.lemon.mcdevmanagermp.domain.account

interface CookieRepository {
    fun getAllCookiesMap(): Map<String, String>
    fun addCookie(key: String, value: String)
    fun clearCookies()
    fun bindAccount(accountId: Long, persistedCookies: Map<String, String>)
}
