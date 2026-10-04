package com.bilichat.app.data

/** 某个账号（或未登录访客）发起请求所用的凭据。 */
interface Credentials {
    val mid: Long?
    val csrf: String
    fun cookieHeader(): String
}

/** 固定的一组 Cookie，用于未登录请求与登录过程中的校验。 */
class StaticCredentials(private val cookies: Map<String, String>) : Credentials {
    override val mid: Long? get() = cookies[Cookies.UID]?.toLongOrNull()
    override val csrf: String get() = cookies[Cookies.CSRF].orEmpty()
    override fun cookieHeader(): String = Cookies.format(cookies)
}

object Cookies {
    const val SESSION = "SESSDATA"
    const val CSRF = "bili_jct"
    const val UID = "DedeUserID"

    fun parse(header: String): Map<String, String> = header
        .split(';')
        .mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) return@mapNotNull null
            part.substring(0, index).trim() to part.substring(index + 1).trim()
        }
        .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
        .toMap()

    fun format(cookies: Map<String, String>): String =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    fun isComplete(cookies: Map<String, String>): Boolean =
        listOf(SESSION, CSRF, UID).all { !cookies[it].isNullOrEmpty() }
}
