package com.bilichat.app.ui.login

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bilichat.app.data.AccountManager
import com.bilichat.app.data.bili.QrStatus
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface QrUiState {
    data object Loading : QrUiState
    data class Ready(val image: ImageBitmap, val scanned: Boolean) : QrUiState
    data object Expired : QrUiState
    data object LoggingIn : QrUiState
    data class Failed(val message: String) : QrUiState
}

class LoginViewModel(private val accounts: AccountManager) : ViewModel() {
    private val api = accounts.guestApi

    private val _qr = MutableStateFlow<QrUiState?>(null)

    /** 为 null 时不显示二维码面板。 */
    val qr: StateFlow<QrUiState?> = _qr.asStateFlow()

    private val _webLoggingIn = MutableStateFlow(false)
    val webLoggingIn: StateFlow<Boolean> = _webLoggingIn.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _loggedIn = MutableStateFlow(false)
    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

    /** 账号登录失效等提示。 */
    val notice: StateFlow<String?> = accounts.notice

    private var qrJob: Job? = null

    fun startQrLogin() {
        qrJob?.cancel()
        qrJob = viewModelScope.launch {
            _qr.value = QrUiState.Loading
            val code = try {
                api.generateQrCode()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _qr.value = QrUiState.Failed(e.message ?: "获取二维码失败")
                return@launch
            }
            val image = withContext(Dispatchers.Default) { encodeQr(code.url) }
            _qr.value = QrUiState.Ready(image, scanned = false)
            while (true) {
                delay(POLL_INTERVAL_MS)
                val status = try {
                    api.pollQrCode(code.key)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    continue
                }
                when (status) {
                    QrStatus.Waiting -> Unit
                    QrStatus.Scanned -> _qr.update { (it as? QrUiState.Ready)?.copy(scanned = true) ?: it }
                    QrStatus.Expired -> {
                        _qr.value = QrUiState.Expired
                        return@launch
                    }
                    is QrStatus.Success -> {
                        _qr.value = QrUiState.LoggingIn
                        try {
                            accounts.addAccount(status.cookies)
                            _loggedIn.value = true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            _qr.value = QrUiState.Failed(e.message ?: "登录失败")
                        }
                        return@launch
                    }
                }
            }
        }
    }

    fun closeQr() {
        qrJob?.cancel()
        _qr.value = null
    }

    /** 网页登录完成后，用 WebView 中的 Cookie 登录。 */
    fun loginWithCookies(cookies: Map<String, String>) {
        if (_webLoggingIn.value) return
        _webLoggingIn.value = true
        viewModelScope.launch {
            try {
                accounts.addAccount(cookies)
                _loggedIn.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "登录失败"
                _webLoggingIn.value = false
            }
        }
    }

    fun consumeError() {
        _error.value = null
    }

    fun consumeNotice() = accounts.consumeNotice()

    private fun encodeQr(text: String): ImageBitmap {
        val size = 512
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val pixels = IntArray(size * size) { index ->
            if (matrix[index % size, index / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
    }
}
