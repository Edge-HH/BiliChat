package com.bilichat.app.data

import android.content.Context
import androidx.core.content.edit
import com.bilichat.app.domain.model.Account
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

data class AccountsState(
    val accounts: List<Account> = emptyList(),
    val activeMid: Long? = null,
) {
    val active: Account? get() = accounts.firstOrNull { it.mid == activeMid }

    fun contains(mid: Long): Boolean = accounts.any { it.mid == mid }
}

/**
 * 保存所有已登录账号及其 Cookie。Cookie 只存放在应用私有目录，且已排除在系统备份之外。
 */
class AccountStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("accounts", Context.MODE_PRIVATE)
    private val cookies = ConcurrentHashMap<Long, Map<String, String>>()

    private val _state = MutableStateFlow(AccountsState())
    val state: StateFlow<AccountsState> = _state.asStateFlow()

    init {
        load()
        migrateSingleAccount()
    }

    fun credentials(mid: Long): Credentials = object : Credentials {
        override val mid: Long get() = mid
        override val csrf: String get() = cookies[mid]?.get(Cookies.CSRF).orEmpty()
        override fun cookieHeader(): String = Cookies.format(cookies[mid].orEmpty())
    }

    fun account(mid: Long): Account? = _state.value.accounts.firstOrNull { it.mid == mid }

    /** 新增或更新账号（重新登录时替换 Cookie）。 */
    @Synchronized
    fun upsert(account: Account, accountCookies: Map<String, String>, activate: Boolean) {
        cookies[account.mid] = accountCookies
        val current = _state.value
        val accounts = if (current.contains(account.mid)) {
            current.accounts.map { if (it.mid == account.mid) account else it }
        } else {
            current.accounts + account
        }
        _state.value = AccountsState(accounts, if (activate) account.mid else current.activeMid ?: account.mid)
        persist()
    }

    @Synchronized
    fun updateProfile(account: Account) {
        val current = _state.value
        if (!current.contains(account.mid)) return
        _state.value = current.copy(accounts = current.accounts.map { if (it.mid == account.mid) account else it })
        persist()
    }

    @Synchronized
    fun setActive(mid: Long) {
        val current = _state.value
        if (!current.contains(mid) || current.activeMid == mid) return
        _state.value = current.copy(activeMid = mid)
        persist()
    }

    /** 移除账号；若移除的是当前账号，切换到剩余的第一个账号。 */
    @Synchronized
    fun remove(mid: Long) {
        cookies.remove(mid)
        val current = _state.value
        val accounts = current.accounts.filterNot { it.mid == mid }
        val active = if (current.activeMid == mid) accounts.firstOrNull()?.mid else current.activeMid
        _state.value = AccountsState(accounts, active)
        persist()
    }

    private fun persist() {
        val state = _state.value
        val json = buildJsonObject {
            state.activeMid?.let { put("active", it) }
            put(
                "accounts",
                buildJsonArray {
                    state.accounts.forEach { account ->
                        add(
                            buildJsonObject {
                                put("mid", account.mid)
                                put("name", account.name)
                                account.avatarUrl?.let { put("face", it) }
                                put("cookies", Cookies.format(cookies[account.mid].orEmpty()))
                            },
                        )
                    }
                },
            )
        }
        prefs.edit { putString(KEY_DATA, json.toString()) }
    }

    private fun load() {
        val root = runCatching { Json.parseToJsonElement(prefs.getString(KEY_DATA, null) ?: return) }
            .getOrNull() as? JsonObject ?: return
        val accounts = (root["accounts"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val mid = obj["mid"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val accountCookies = Cookies.parse(obj.string("cookies").orEmpty())
            if (!Cookies.isComplete(accountCookies)) return@mapNotNull null
            cookies[mid] = accountCookies
            Account(mid, obj.string("name").orEmpty(), obj.string("face"))
        }
        val active = root["active"]?.jsonPrimitive?.longOrNull?.takeIf { mid -> accounts.any { it.mid == mid } }
        _state.value = AccountsState(accounts, active ?: accounts.firstOrNull()?.mid)
    }

    /** 从只支持单账号的旧版本迁移登录信息。 */
    private fun migrateSingleAccount() {
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val legacyCookies = Cookies.parse(legacy.getString("cookies", null).orEmpty())
        val mid = legacy.getLong("mid", 0L)
        if (mid != 0L && Cookies.isComplete(legacyCookies) && account(mid) == null) {
            upsert(
                Account(mid, legacy.getString("name", null).orEmpty(), legacy.getString("face", null)),
                legacyCookies,
                activate = _state.value.activeMid == null,
            )
        }
        if (legacy.all.isNotEmpty()) {
            context.deleteSharedPreferences(LEGACY_PREFS)
            context.deleteSharedPreferences("notify_state")
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val KEY_DATA = "data"
        const val LEGACY_PREFS = "auth"
    }
}
