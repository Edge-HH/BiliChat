package com.bilichat.app.data.bili

import java.net.URLEncoder
import java.security.MessageDigest

/** B 站 Web 端 WBI 签名。 */
internal object Wbi {
    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
        61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
        36, 20, 34, 44, 52,
    )

    /** 从 `img_url`/`sub_url` 中截取文件名作为口令。 */
    fun keyFromUrl(url: String): String = url.substringAfterLast('/').substringBefore('.')

    fun mixinKey(imgKey: String, subKey: String): String {
        val raw = imgKey + subKey
        return buildString { MIXIN_KEY_ENC_TAB.forEach { append(raw[it]) } }.take(32)
    }

    fun sign(params: Map<String, String>, mixinKey: String, nowSeconds: Long): Map<String, String> {
        val signed = (params + ("wts" to nowSeconds.toString()))
            .mapValues { (_, value) -> value.filterNot { it in "!'()*" } }
            .toSortedMap()
        val query = signed.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        return signed + ("w_rid" to md5(query + mixinKey))
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20").replace("%7E", "~")

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
