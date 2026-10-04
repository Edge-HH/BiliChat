package com.bilichat.app.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bilichat.app.BuildConfig
import com.bilichat.app.data.AppSettings
import com.bilichat.app.data.formatInterval
import com.bilichat.app.domain.model.Account
import com.bilichat.app.ui.components.Avatar
import com.bilichat.app.ui.theme.supportsDynamicColor
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    account: Account,
    accounts: List<Account>,
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onAddAccount: () -> Unit,
) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    val loggingOut by viewModel.loggingOut.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var notificationsEnabled by remember { mutableStateOf(true) }
    var batteryUnrestricted by remember { mutableStateOf(true) }
    var showIntervalDialog by remember { mutableStateOf(false) }
    var logoutTarget by remember { mutableStateOf<Account?>(null) }

    // 从系统设置返回时刷新权限状态。
    LifecycleResumeEffect(Unit) {
        notificationsEnabled = context.notificationsAllowed()
        batteryUnrestricted = context.getSystemService<PowerManager>()
            ?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        onPauseOrDispose { }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.setAutoNotify(true)
        } else {
            scope.launch {
                val result = snackbar.showSnackbar("未授予通知权限，无法发送新消息通知", actionLabel = "去设置")
                if (result == SnackbarResult.ActionPerformed) context.openNotificationSettings()
            }
        }
    }

    fun onAutoNotifyChange(enabled: Boolean) {
        val needsPermission = enabled &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.setAutoNotify(enabled)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
        ) {
            item { SectionHeader("账号") }
            items(accounts, key = { it.mid }) { item ->
                val active = item.mid == account.mid
                ListItem(
                    modifier = Modifier.clickable(enabled = !active) { viewModel.switchTo(item.mid) },
                    leadingContent = { Avatar(url = item.avatarUrl, name = item.name, size = 48.dp) },
                    headlineContent = { Text(item.name.ifEmpty { "UID ${item.mid}" }) },
                    supportingContent = {
                        Text(
                            when {
                                item.mid in loggingOut -> "正在退出…"
                                active -> "当前账号 · UID ${item.mid}"
                                else -> "UID ${item.mid} · 点击切换"
                            },
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = { logoutTarget = item }, enabled = item.mid !in loggingOut) {
                            Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = "退出该账号")
                        }
                    },
                )
            }
            item {
                ClickItem(
                    icon = Icons.Outlined.PersonAdd,
                    title = "添加账号",
                    summary = "登录多个账号后，点击首页顶栏的账号名即可切换",
                    onClick = onAddAccount,
                )
            }

            item { SectionHeader("通知") }
            item {
                SwitchItem(
                    icon = Icons.Outlined.NotificationsActive,
                    title = "自动通知",
                    summary = "在后台按刷新间隔检查新私信并发送通知，无需常驻后台",
                    checked = settings.autoNotify,
                    onCheckedChange = ::onAutoNotifyChange,
                )
            }
            item {
                SwitchItem(
                    icon = Icons.Outlined.Bedtime,
                    title = "息屏时放慢",
                    summary = "息屏后一直没有新消息时，检查间隔逐步放慢到最多 5 分钟；收到消息、亮屏或打开必聊后恢复",
                    checked = settings.slowWhenIdle,
                    enabled = !settings.powerSaving,
                    onCheckedChange = viewModel::setSlowWhenIdle,
                )
            }
            item {
                SwitchItem(
                    icon = Icons.Outlined.Eco,
                    title = "省电模式",
                    summary = "后台检查交给系统统一安排，约 15 分钟或更久一次；最省电，但通知会明显延迟",
                    checked = settings.powerSaving,
                    onCheckedChange = viewModel::setPowerSaving,
                )
            }
            item {
                SwitchItem(
                    icon = Icons.Outlined.FilterAlt,
                    title = "过滤自动消息",
                    summary = "不通知自动回复、官方推送、稿件推送、系统提示等自动发送的消息",
                    checked = settings.filterAutoMessages,
                    onCheckedChange = viewModel::setFilterAutoMessages,
                )
            }
            item {
                ClickItem(
                    icon = Icons.Outlined.Notifications,
                    title = "系统通知设置",
                    summary = if (notificationsEnabled) "提示音、横幅与锁屏显示" else "通知已被关闭，点击前往开启",
                    onClick = { context.openNotificationSettings() },
                )
            }
            item {
                ClickItem(
                    icon = Icons.Outlined.BatterySaver,
                    title = "后台运行",
                    summary = if (batteryUnrestricted) {
                        "已允许在后台不受限制地运行"
                    } else {
                        "建议关闭电池优化，否则息屏后检查可能被系统推迟"
                    },
                    onClick = { context.openBatteryOptimization() },
                )
            }

            item { SectionHeader("刷新") }
            item {
                ClickItem(
                    icon = Icons.Outlined.Schedule,
                    title = "消息刷新间隔",
                    summary = "${formatInterval(settings.refreshIntervalSeconds)} · 用于会话列表、聊天与后台通知，间隔越短越及时也越耗电",
                    onClick = { showIntervalDialog = true },
                )
            }

            item { SectionHeader("外观") }
            item {
                SwitchItem(
                    icon = Icons.Outlined.Palette,
                    title = "动态取色",
                    summary = if (supportsDynamicColor) "从壁纸提取主题色" else "需要 Android 12 及以上版本",
                    checked = settings.dynamicColor && supportsDynamicColor,
                    enabled = supportsDynamicColor,
                    onCheckedChange = viewModel::setDynamicColor,
                )
            }

            item {
                Text(
                    text = "必聊 ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                )
            }
        }
    }

    if (showIntervalDialog) {
        IntervalDialog(
            current = settings.refreshIntervalSeconds,
            onSelect = {
                viewModel.setRefreshInterval(it)
                showIntervalDialog = false
            },
            onDismiss = { showIntervalDialog = false },
        )
    }

    logoutTarget?.let { target ->
        val name = target.name.ifEmpty { "UID ${target.mid}" }
        val last = accounts.size == 1
        AlertDialog(
            onDismissRequest = { logoutTarget = null },
            icon = { Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null) },
            title = { Text("退出 $name？") },
            text = {
                Text(
                    if (last) {
                        "将清除本机保存的登录信息，并停止后台接收私信。"
                    } else {
                        "将清除该账号在本机保存的登录信息，不再接收它的私信通知。"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    logoutTarget = null
                    viewModel.logout(target.mid)
                }) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { logoutTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun SwitchItem(
    icon: ImageVector,
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    ListItem(
        modifier = Modifier.selectable(
            selected = checked,
            enabled = enabled,
            role = Role.Switch,
            onClick = { onCheckedChange(!checked) },
        ),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

@Composable
private fun ClickItem(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
    )
}

@Composable
private fun IntervalDialog(current: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Schedule, contentDescription = null) },
        title = { Text("消息刷新间隔") },
        text = {
            Column(Modifier.selectableGroup()) {
                AppSettings.refreshIntervalOptions.forEach { seconds ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = seconds == current, role = Role.RadioButton) { onSelect(seconds) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = seconds == current, onClick = null)
                        Text(formatInterval(seconds), modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun Context.notificationsAllowed(): Boolean {
    val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    return granted && NotificationManagerCompat.from(this).areNotificationsEnabled()
}

private fun Context.openNotificationSettings() {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    runCatching { startActivity(intent) }
}

@SuppressLint("BatteryLife")
private fun Context.openBatteryOptimization() {
    val unrestricted = getSystemService<PowerManager>()?.isIgnoringBatteryOptimizations(packageName) == true
    val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri())
    val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    runCatching { startActivity(if (unrestricted) list else request) }
        .onFailure { runCatching { startActivity(list) } }
}
