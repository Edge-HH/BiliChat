package com.bilichat.app.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bilichat.app.data.ChatRepository
import com.bilichat.app.data.SettingsRepository
import com.bilichat.app.domain.model.Conversation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ConversationsUiState(
    val conversations: List<Conversation> = emptyList(),
    val loaded: Boolean = false,
    val hasMore: Boolean = false,
    val refreshing: Boolean = false,
    /** 首次加载失败时的错误，显示为整页错误状态。 */
    val loadError: String? = null,
)

class ConversationsViewModel(
    private val chat: ChatRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val refreshing = MutableStateFlow(false)
    private val loadError = MutableStateFlow<String?>(null)

    /** 下拉刷新、加载更多等用户操作的错误，以 Snackbar 提示。 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    val uiState: StateFlow<ConversationsUiState> =
        combine(chat.sessions, refreshing, loadError) { sessions, isRefreshing, error ->
            ConversationsUiState(
                conversations = sessions.conversations,
                loaded = sessions.loaded,
                hasMore = sessions.hasMore,
                refreshing = isRefreshing,
                loadError = error.takeIf { !sessions.loaded },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationsUiState())

    private var loadingMore = false

    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            try {
                chat.refreshSessions()
                loadError.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (chat.sessions.value.loaded) _message.value = e.message else loadError.value = e.message
            } finally {
                refreshing.value = false
            }
        }
    }

    /** 界面可见期间按刷新间隔静默刷新；与后台服务共享结果，避免重复请求。 */
    suspend fun pollWhileVisible() {
        settings.settings.map { it.refreshIntervalMillis }.distinctUntilChanged().collectLatest { interval ->
            while (true) {
                try {
                    chat.refreshSessions(maxAgeMillis = interval / 2)
                    loadError.value = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!chat.sessions.value.loaded) loadError.value = e.message ?: "加载失败"
                }
                delay(interval)
            }
        }
    }

    fun loadMore() {
        if (loadingMore) return
        loadingMore = true
        viewModelScope.launch {
            try {
                chat.loadMoreSessions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = e.message
            } finally {
                loadingMore = false
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
