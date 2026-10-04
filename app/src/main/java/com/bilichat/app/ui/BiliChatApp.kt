package com.bilichat.app.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bilichat.app.AppContainer
import com.bilichat.app.domain.model.Account
import com.bilichat.app.ui.chat.ChatScreen
import com.bilichat.app.ui.chat.ChatViewModel
import com.bilichat.app.ui.conversations.ConversationsScreen
import com.bilichat.app.ui.conversations.ConversationsViewModel
import com.bilichat.app.ui.login.LoginScreen
import com.bilichat.app.ui.login.LoginViewModel
import com.bilichat.app.ui.login.WebLoginScreen
import com.bilichat.app.ui.settings.SettingsScreen
import com.bilichat.app.ui.settings.SettingsViewModel
import kotlinx.coroutines.flow.MutableStateFlow

/** 要打开的会话；`mid` 为收到该会话的账号。 */
data class ChatTarget(val mid: Long, val talkerId: Long, val sessionType: Int)

@Composable
fun BiliChatApp(container: AppContainer, pendingChat: MutableStateFlow<ChatTarget?>) {
    val accounts by container.accounts.state.collectAsStateWithLifecycle()
    val pending by pendingChat.collectAsStateWithLifecycle()

    // 从通知打开其他账号的会话时，先切换到该账号。
    LaunchedEffect(pending, accounts) {
        val target = pending ?: return@LaunchedEffect
        when {
            !accounts.contains(target.mid) -> pendingChat.value = null
            target.mid != accounts.activeMid -> container.accounts.switchTo(target.mid)
        }
    }

    val active = accounts.active
    if (active == null) {
        LoginFlow(container)
    } else {
        // 切换账号时重建整个导航，回到新账号的会话列表。
        key(active.mid) { MainFlow(container, active, accounts.accounts, pendingChat) }
    }
}

@Composable
private fun LoginFlow(container: AppContainer) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = ROUTE_LOGIN,
        enterTransition = { forwardEnter() },
        exitTransition = { forwardExit() },
        popEnterTransition = { backEnter() },
        popExitTransition = { backExit() },
    ) {
        loginRoutes(navController, container, onBack = null, onLoggedIn = {})
    }
}

@Composable
private fun MainFlow(
    container: AppContainer,
    account: Account,
    accounts: List<Account>,
    pendingChat: MutableStateFlow<ChatTarget?>,
) {
    val navController = rememberNavController()
    val pending by pendingChat.collectAsStateWithLifecycle()
    val notice by container.accounts.notice.collectAsStateWithLifecycle()
    val session = remember(account.mid) { container.accounts.session(account.mid) }

    LaunchedEffect(pending) {
        val target = pending?.takeIf { it.mid == account.mid } ?: return@LaunchedEffect
        navController.navigate(chatRoute(target)) {
            popUpTo(ROUTE_CONVERSATIONS)
            launchSingleTop = true
        }
        pendingChat.value = null
    }

    NavHost(
        navController = navController,
        startDestination = ROUTE_CONVERSATIONS,
        enterTransition = { forwardEnter() },
        exitTransition = { forwardExit() },
        popEnterTransition = { backEnter() },
        popExitTransition = { backExit() },
    ) {
        composable(ROUTE_CONVERSATIONS) {
            val viewModel: ConversationsViewModel = viewModel { ConversationsViewModel(session.chat, container.settings) }
            ConversationsScreen(
                account = account,
                accounts = accounts,
                viewModel = viewModel,
                notice = notice,
                onNoticeShown = container.accounts::consumeNotice,
                onOpenChat = { navController.navigate(chatRoute(ChatTarget(account.mid, it.talkerId, it.sessionType))) },
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                onSwitchAccount = container.accounts::switchTo,
                onAddAccount = { navController.navigate(ROUTE_LOGIN) },
            )
        }
        composable(
            route = "chat/{sessionType}/{talkerId}",
            arguments = listOf(
                navArgument("sessionType") { type = NavType.IntType },
                navArgument("talkerId") { type = NavType.LongType },
            ),
        ) { entry ->
            val args = requireNotNull(entry.arguments)
            val talkerId = args.getLong("talkerId")
            val sessionType = args.getInt("sessionType")
            val viewModel: ChatViewModel = viewModel {
                ChatViewModel(session.chat, container.settings, container.notifier, container.images, account, talkerId, sessionType)
            }
            ChatScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable(ROUTE_SETTINGS) {
            val viewModel: SettingsViewModel = viewModel { SettingsViewModel(container.settings, container.accounts, container.appScope) }
            SettingsScreen(
                account = account,
                accounts = accounts,
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onAddAccount = { navController.navigate(ROUTE_LOGIN) },
            )
        }
        loginRoutes(
            navController = navController,
            container = container,
            onBack = { navController.popBackStack() },
            // 登录了新账号会自动切换并重建导航；重新登录当前账号时返回会话列表。
            onLoggedIn = { navController.popBackStack(ROUTE_CONVERSATIONS, inclusive = false) },
        )
    }
}

/** 登录页与网页登录页。[onBack] 为 null 时表示首次登录，不显示返回按钮。 */
private fun NavGraphBuilder.loginRoutes(
    navController: NavHostController,
    container: AppContainer,
    onBack: (() -> Unit)?,
    onLoggedIn: () -> Unit,
) {
    composable(ROUTE_LOGIN) {
        val loginViewModel: LoginViewModel = viewModel { LoginViewModel(container.accounts) }
        val loggedIn by loginViewModel.loggedIn.collectAsStateWithLifecycle()
        LaunchedEffect(loggedIn) { if (loggedIn) onLoggedIn() }
        LoginScreen(
            viewModel = loginViewModel,
            onWebLogin = { navController.navigate(ROUTE_WEB_LOGIN) },
            onBack = onBack,
        )
    }
    composable(ROUTE_WEB_LOGIN) { entry ->
        // 与登录页共用同一个 ViewModel，以便在登录页显示网页登录的错误。
        val parent = remember(entry) { navController.getBackStackEntry(ROUTE_LOGIN) }
        val loginViewModel: LoginViewModel = viewModel(viewModelStoreOwner = parent) { LoginViewModel(container.accounts) }
        val error by loginViewModel.error.collectAsStateWithLifecycle()
        val loggedIn by loginViewModel.loggedIn.collectAsStateWithLifecycle()
        LaunchedEffect(error) {
            if (error != null) navController.popBackStack(ROUTE_LOGIN, inclusive = false)
        }
        LaunchedEffect(loggedIn) { if (loggedIn) onLoggedIn() }
        WebLoginScreen(
            onBack = { navController.popBackStack() },
            onCookies = loginViewModel::loginWithCookies,
            loggingIn = loginViewModel.webLoggingIn,
        )
    }
}

private const val ROUTE_LOGIN = "login"
private const val ROUTE_WEB_LOGIN = "login/web"
private const val ROUTE_CONVERSATIONS = "conversations"
private const val ROUTE_SETTINGS = "settings"

private fun chatRoute(target: ChatTarget) = "chat/${target.sessionType}/${target.talkerId}"

// Material 共享轴（X 轴）过渡。
private const val DURATION = 300

private fun AnimatedContentTransitionScope<NavBackStackEntry>.forwardEnter(): EnterTransition =
    slideInHorizontally(tween(DURATION)) { it / 5 } + fadeIn(tween(DURATION))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.forwardExit(): ExitTransition =
    slideOutHorizontally(tween(DURATION)) { -it / 5 } + fadeOut(tween(DURATION / 2))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.backEnter(): EnterTransition =
    slideInHorizontally(tween(DURATION)) { -it / 5 } + fadeIn(tween(DURATION))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.backExit(): ExitTransition =
    slideOutHorizontally(tween(DURATION)) { it / 5 } + fadeOut(tween(DURATION / 2))
