package com.bilichat.app.data.bili

import com.bilichat.app.data.Credentials
import com.bilichat.app.domain.model.Account
import com.bilichat.app.domain.model.ChatMessage
import com.bilichat.app.domain.model.Emote
import com.bilichat.app.domain.model.EmoteItem
import com.bilichat.app.domain.model.EmotePackage
import com.bilichat.app.domain.model.UserCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class BiliApiException(val code: Int, message: String) : IOException(message)

data class NavInfo(val account: Account?, val imgKey: String, val subKey: String)

data class QrCode(val url: String, val key: String)

sealed interface QrStatus {
    data object Waiting : QrStatus
    data object Scanned : QrStatus
    data object Expired : QrStatus
    data class Success(val cookies: Map<String, String>) : QrStatus
}

data class RawSession(
    val talkerId: Long,
    val sessionType: Int,
    val topTs: Long,
    val isMuted: Boolean,
    val ackSeqno: Long,
    val sessionTs: Long,
    val unreadCount: Int,
    val maxSeqno: Long,
    val lastMessage: ChatMessage?,
    val systemMsgType: Int,
    /** 系统会话或粉丝团自带的名称与头像；普通用户会话需另行查询用户信息。 */
    val presetName: String?,
    val presetAvatar: String?,
)

data class SessionPage(val sessions: List<RawSession>, val hasMore: Boolean)

data class UploadedImage(val url: String, val width: Int, val height: Int, val sizeKb: Double)

data class MessagePage(
    /** 按时间正序排列。 */
    val messages: List<ChatMessage>,
    val hasMore: Boolean,
    val emotes: Map<String, Emote>,
)

/**
 * B 站 Web 端私信相关接口。每个账号一个实例，所有 bilibili.com 请求都会自动带上该账号的 Cookie。
 */
class BiliApi(
    private val credentials: Credentials,
    private val deviceId: String,
    private val onUnauthorized: () -> Unit,
) {
    // 各账号共用连接池与线程池，只是附加的 Cookie 不同。
    private val client: OkHttpClient = baseClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val builder = request.newBuilder().header("User-Agent", USER_AGENT)
            val host = request.url.host
            if (host == "bilibili.com" || host.endsWith(".bilibili.com")) {
                val cookie = credentials.cookieHeader()
                if (cookie.isNotEmpty()) builder.header("Cookie", cookie)
                if (request.header("Referer") == null) builder.header("Referer", "$MESSAGE_ORIGIN/")
                if (request.method == "POST" && request.header("Origin") == null) builder.header("Origin", MESSAGE_ORIGIN)
            }
            chain.proceed(builder.build())
        }
        .build()

    @Volatile
    private var wbiMixinKey: String? = null

    @Volatile
    private var wbiFetchedAt = 0L

    // region 账号与登录

    suspend fun nav(): NavInfo {
        val (root, _) = get("https://api.bilibili.com/x/web-interface/nav".toHttpUrl())
        val data = root.obj("data") ?: throw BiliApiException(root.int("code"), root.str("message"))
        val wbi = data.obj("wbi_img")
        val imgKey = Wbi.keyFromUrl(wbi?.str("img_url").orEmpty())
        val subKey = Wbi.keyFromUrl(wbi?.str("sub_url").orEmpty())
        if (imgKey.length + subKey.length >= 64) {
            wbiMixinKey = Wbi.mixinKey(imgKey, subKey)
            wbiFetchedAt = System.currentTimeMillis()
        }
        val account = if (root.int("code") == 0 && data.bool("isLogin")) {
            Account(data.long("mid"), data.str("uname"), normalizeUrl(data.raw("face")))
        } else {
            null
        }
        return NavInfo(account, imgKey, subKey)
    }

    suspend fun generateQrCode(): QrCode {
        val data = get("https://passport.bilibili.com/x/passport-login/web/qrcode/generate".toHttpUrl()).requireData()
        return QrCode(data.str("url"), data.str("qrcode_key"))
    }

    suspend fun pollQrCode(key: String): QrStatus {
        val url = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll".toHttpUrl().newBuilder()
            .addQueryParameter("qrcode_key", key)
            .build()
        val (root, headers) = get(url, referer = "https://www.bilibili.com/")
        val data = root.requireDataOf()
        return when (data.int("code")) {
            // 优先使用 Set-Cookie 中的值，跨域 url 中的参数仅作补充。
            0 -> QrStatus.Success(crossDomainCookies(data.str("url")) + cookiesFrom(headers))
            86090 -> QrStatus.Scanned
            86101 -> QrStatus.Waiting
            else -> QrStatus.Expired
        }
    }

    /** 获取设备指纹 Cookie（buvid3/buvid4），部分接口在缺少时会触发风控。 */
    suspend fun fetchBuvid(): Map<String, String> {
        val data = get("https://api.bilibili.com/x/frontend/finger/spi".toHttpUrl()).requireData()
        return mapOf("buvid3" to data.str("b_3"), "buvid4" to data.str("b_4")).filterValues { it.isNotEmpty() }
    }

    suspend fun logout() {
        post(
            "https://passport.bilibili.com/login/exit/v2".toHttpUrl(),
            mapOf("biliCSRF" to credentials.csrf),
        )
    }

    // endregion

    // region 会话

    suspend fun getSessions(endTs: Long? = null, size: Int = 30): SessionPage {
        val url = VC.newBuilder()
            .addPathSegments("session_svr/v1/session_svr/get_sessions")
            .addQueryParameter("session_type", "4")
            .addQueryParameter("group_fold", "0")
            .addQueryParameter("unfollow_fold", "0")
            .addQueryParameter("sort_rule", "2")
            .addQueryParameter("size", size.toString())
            .apply { if (endTs != null) addQueryParameter("end_ts", endTs.toString()) }
            .webClient()
            .build()
        val data = get(url).requireData()
        val sessions = data.arr("session_list")?.objects().orEmpty().map(::parseSession)
        return SessionPage(sessions, hasMore = data.bool("has_more"))
    }

    suspend fun userCards(uids: Collection<Long>): List<UserCard> {
        if (uids.isEmpty()) return emptyList()
        return uids.chunked(50).flatMap { chunk ->
            val ids = chunk.joinToString(",")
            runCatching {
                val url = "https://api.bilibili.com/x/polymer/pc-electron/v1/user/cards".toHttpUrl().newBuilder()
                    .addQueryParameter("uids", ids)
                    .build()
                val data = get(url).requireData()
                data.values.filterIsInstance<JsonObject>().map(::parseUserCard)
            }.recoverCatching {
                val url = "https://api.vc.bilibili.com/account/v1/user/cards".toHttpUrl().newBuilder()
                    .addQueryParameter("uids", ids)
                    .build()
                val (root, _) = get(url)
                root.requireDataOf()
                (root["data"] as? JsonArray)?.objects().orEmpty().map(::parseUserCard)
            }.getOrThrow()
        }
    }

    suspend fun updateAck(talkerId: Long, sessionType: Int, ackSeqno: Long) {
        post(
            VC.newBuilder().addPathSegments("session_svr/v1/session_svr/update_ack").build(),
            mapOf(
                "talker_id" to talkerId.toString(),
                "session_type" to sessionType.toString(),
                "ack_seqno" to ackSeqno.toString(),
                "build" to "0",
                "mobi_app" to "web",
                "csrf_token" to credentials.csrf,
                "csrf" to credentials.csrf,
            ),
        ).requireDataOf()
    }

    // endregion

    // region 消息

    suspend fun fetchMessages(
        talkerId: Long,
        sessionType: Int,
        size: Int = 30,
        beginSeqno: Long? = null,
        endSeqno: Long? = null,
    ): MessagePage {
        val url = VC.newBuilder()
            .addPathSegments("svr_sync/v1/svr_sync/fetch_session_msgs")
            .addQueryParameter("talker_id", talkerId.toString())
            .addQueryParameter("session_type", sessionType.toString())
            .addQueryParameter("size", size.toString())
            .apply {
                if (beginSeqno != null && beginSeqno > 0) addQueryParameter("begin_seqno", beginSeqno.toString())
                if (endSeqno != null && endSeqno > 0) addQueryParameter("end_seqno", endSeqno.toString())
            }
            .addQueryParameter("sender_device_id", "1")
            .webClient()
            .build()
        val data = get(url).requireData()
        val messages = data.arr("messages")?.objects().orEmpty()
            .map(MessageParser::parseMessage)
            .sortedBy { it.seqno }
        return MessagePage(messages, data.bool("has_more"), MessageParser.parseEmotes(data.arr("e_infos")))
    }

    /** 发送文字私信，返回服务器解析出的表情信息。 */
    suspend fun sendText(receiverId: Long, receiverType: Int, text: String): Map<String, Emote> {
        val content = buildJsonObject { put("content", text) }.toString()
        val data = sendMessage(receiverId, receiverType, ChatMessage.TYPE_TEXT, content)
        return MessageParser.parseEmotes(data?.arr("e_infos"))
    }

    suspend fun withdraw(receiverId: Long, receiverType: Int, msgKey: String) {
        sendMessage(receiverId, receiverType, ChatMessage.TYPE_WITHDRAW, msgKey)
    }

    suspend fun sendImage(receiverId: Long, receiverType: Int, image: UploadedImage, imageType: String) {
        val content = buildJsonObject {
            put("url", image.url)
            put("height", image.height)
            put("width", image.width)
            put("imageType", imageType)
            put("original", 1)
            put("size", image.sizeKb)
        }.toString()
        sendMessage(receiverId, receiverType, ChatMessage.TYPE_IMAGE, content)
    }

    /** 上传图片到 B 站图床，私信图片必须使用 B 站图床地址。 */
    suspend fun uploadImage(bytes: ByteArray, mimeType: String, fileName: String): UploadedImage {
        var lastError: Exception? = null
        for (biz in listOf("im", "new_dyn")) {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file_up", fileName, bytes.toRequestBody(mimeType.toMediaType()))
                .addFormDataPart("biz", biz)
                .addFormDataPart("category", "daily")
                .addFormDataPart("csrf", credentials.csrf)
                .build()
            try {
                val data = post("https://api.bilibili.com/x/dynamic/feed/draw/upload_bfs".toHttpUrl(), body).requireDataOf()
                val url = normalizeUrl(data.raw("image_url")) ?: throw BiliApiException(-1, "图片上传失败")
                return UploadedImage(
                    url = url,
                    width = data.int("image_width"),
                    height = data.int("image_height"),
                    sizeKb = data.raw("img_size")?.toDoubleOrNull() ?: (bytes.size / 1024.0),
                )
            } catch (e: BiliApiException) {
                if (e.code == -101) throw e
                lastError = e
            }
        }
        throw lastError ?: BiliApiException(-1, "图片上传失败")
    }

    /** 当前账号可用的表情包（含会员与已购买的）；获取不到时退回默认的小黄脸与颜文字。 */
    suspend fun emotePackages(): List<EmotePackage> {
        val panel = "https://api.bilibili.com/x/emote/user/panel/web".toHttpUrl().newBuilder()
            .addQueryParameter("business", "reply")
            .build()
        val packages = parseEmotePackages(get(panel).requireData())
        if (packages.isNotEmpty()) return packages
        val defaults = "https://api.bilibili.com/x/emote/package".toHttpUrl().newBuilder()
            .addQueryParameter("ids", "1,4")
            .addQueryParameter("business", "reply")
            .build()
        return parseEmotePackages(get(defaults).requireData())
    }

    private fun parseEmotePackages(data: JsonObject): List<EmotePackage> =
        data.arr("packages")?.objects().orEmpty().mapNotNull { pkg ->
            val isKaomoji = pkg.int("type") == 4
            val packageLarge = pkg.obj("meta")?.int("size") == 2
            val emotes = pkg.arr("emote")?.objects().orEmpty()
                .filterNot { it.obj("flags")?.bool("no_access") == true }
                .mapNotNull { emote ->
                    val text = emote.str("text").ifEmpty { return@mapNotNull null }
                    EmoteItem(
                        text = text,
                        url = if (isKaomoji) null else normalizeUrl(emote.raw("url")),
                        large = !isKaomoji && (emote.obj("meta")?.int("size")?.takeIf { it > 0 } ?: if (packageLarge) 2 else 1) == 2,
                    )
                }
            if (emotes.isEmpty()) {
                null
            } else {
                EmotePackage(pkg.long("id"), pkg.str("text"), normalizeUrl(pkg.raw("url")), isKaomoji, emotes)
            }
        }

    private suspend fun sendMessage(receiverId: Long, receiverType: Int, msgType: Int, content: String): JsonObject? {
        val sender = credentials.mid ?: throw BiliApiException(-101, "账号未登录")
        val query = mapOf(
            "w_sender_uid" to sender.toString(),
            "w_receiver_id" to receiverId.toString(),
            "w_dev_id" to deviceId,
        )
        val signed = wbiMixinKey()?.let { Wbi.sign(query, it, System.currentTimeMillis() / 1000) } ?: query
        val url = VC.newBuilder()
            .addPathSegments("web_im/v1/web_im/send_msg")
            .apply { signed.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        val form = mapOf(
            "msg[sender_uid]" to sender.toString(),
            "msg[receiver_id]" to receiverId.toString(),
            "msg[receiver_type]" to receiverType.toString(),
            "msg[msg_type]" to msgType.toString(),
            "msg[msg_status]" to "0",
            "msg[dev_id]" to deviceId,
            "msg[timestamp]" to (System.currentTimeMillis() / 1000).toString(),
            "msg[new_face_version]" to "1",
            "msg[content]" to content,
            "from_firework" to "0",
            "build" to "0",
            "mobi_app" to "web",
            "csrf_token" to credentials.csrf,
            "csrf" to credentials.csrf,
        )
        return post(url, form).requireDataOf()
    }

    private suspend fun wbiMixinKey(): String? {
        val fresh = System.currentTimeMillis() - wbiFetchedAt < WBI_TTL_MS
        if (wbiMixinKey == null || !fresh) runCatching { nav() }
        return wbiMixinKey
    }

    // endregion

    // region HTTP

    private suspend fun get(url: HttpUrl, referer: String? = null): Pair<JsonObject, Headers> {
        val request = Request.Builder().url(url).apply { if (referer != null) header("Referer", referer) }.build()
        return execute(request)
    }

    private suspend fun post(url: HttpUrl, form: Map<String, String>): JsonObject =
        post(url, FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build())

    private suspend fun post(url: HttpUrl, body: RequestBody): JsonObject =
        execute(Request.Builder().url(url).post(body).build()).first

    private suspend fun execute(request: Request): Pair<JsonObject, Headers> = try {
        runInterruptible(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) throw BiliApiException(response.code, "网络错误（HTTP ${response.code}）")
                val json = parseJsonOrNull(text) as? JsonObject ?: throw BiliApiException(-1, "服务器返回了无法解析的数据")
                json to response.headers
            }
        }
    } catch (e: BiliApiException) {
        throw e
    } catch (_: SocketTimeoutException) {
        throw BiliApiException(-1, "网络连接超时，请稍后重试")
    } catch (_: IOException) {
        throw BiliApiException(-1, "网络连接失败，请检查网络")
    }

    /** 校验返回码并取出 data 对象（可能为空）。 */
    private fun JsonObject.requireDataOf(): JsonObject {
        val code = int("code")
        if (code != 0) {
            if (code == -101) onUnauthorized()
            val message = str("message").ifEmpty { str("msg") }.takeIf { it.isNotEmpty() && it != "0" }
            throw BiliApiException(code, message ?: "请求失败（$code）")
        }
        return obj("data") ?: JsonObject(emptyMap())
    }

    private fun Pair<JsonObject, Headers>.requireData(): JsonObject = first.requireDataOf()

    private fun HttpUrl.Builder.webClient() = addQueryParameter("build", "0").addQueryParameter("mobi_app", "web")

    // endregion

    private fun parseSession(json: JsonObject): RawSession {
        val sessionType = json.int("session_type")
        val accountInfo = json.obj("account_info")
        val groupName = json.str("group_name")
        return RawSession(
            talkerId = json.long("talker_id"),
            sessionType = sessionType,
            topTs = json.long("top_ts"),
            isMuted = json.bool("is_dnd"),
            ackSeqno = json.long("ack_seqno"),
            sessionTs = json.long("session_ts"),
            unreadCount = json.int("unread_count"),
            maxSeqno = json.long("max_seqno"),
            lastMessage = json.obj("last_msg")?.let(MessageParser::parseMessage),
            systemMsgType = json.int("system_msg_type"),
            presetName = accountInfo?.str("name")?.ifEmpty { null } ?: groupName.ifEmpty { null },
            presetAvatar = normalizeUrl(accountInfo?.raw("pic_url") ?: json.raw("group_cover")),
        )
    }

    private fun parseUserCard(json: JsonObject) = UserCard(
        mid = json.long("mid"),
        name = json.str("name"),
        avatarUrl = normalizeUrl(json.raw("face")),
    )

    private fun cookiesFrom(headers: Headers): Map<String, String> = headers.values("Set-Cookie")
        .mapNotNull { header ->
            val pair = header.substringBefore(';')
            val index = pair.indexOf('=')
            if (index <= 0) null else pair.substring(0, index).trim() to pair.substring(index + 1).trim()
        }
        .filter { it.second.isNotEmpty() }
        .toMap()

    /** 登录成功后返回的跨域 url 中携带了 Cookie；保留原始编码以与浏览器写入的值一致。 */
    private fun crossDomainCookies(url: String): Map<String, String> {
        val query = url.substringAfter('?', "").substringBefore('#')
        return query.split('&')
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) null else part.substring(0, index) to part.substring(index + 1)
            }
            .filter { it.first in LOGIN_COOKIE_NAMES && it.second.isNotEmpty() }
            .toMap()
    }

    companion object {
        private val baseClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
        private const val MESSAGE_ORIGIN = "https://message.bilibili.com"
        private val VC = "https://api.vc.bilibili.com/".toHttpUrl()
        private const val WBI_TTL_MS = 6 * 60 * 60 * 1000L
        private val LOGIN_COOKIE_NAMES = setOf("DedeUserID", "DedeUserID__ckMd5", "SESSDATA", "bili_jct")
    }
}
