package com.bilichat.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.bilichat.app.domain.model.Account
import kotlinx.coroutines.launch

/** 点击顶栏账号名弹出的账号切换面板。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSwitcherSheet(
    accounts: List<Account>,
    activeMid: Long,
    onSwitch: (Long) -> Unit,
    onAddAccount: () -> Unit,
    onManageAccounts: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // 先播放收起动画，再执行操作。
    fun dismissThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                text = "切换账号",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            accounts.forEach { account ->
                val active = account.mid == activeMid
                ListItem(
                    modifier = Modifier.clickable { dismissThen { if (!active) onSwitch(account.mid) } },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { Avatar(url = account.avatarUrl, name = account.name, size = 44.dp) },
                    headlineContent = { Text(account.name.ifEmpty { "UID ${account.mid}" }) },
                    supportingContent = { Text("UID ${account.mid}") },
                    trailingContent = {
                        if (active) Icon(Icons.Outlined.Check, contentDescription = "当前账号", tint = MaterialTheme.colorScheme.primary)
                    },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ActionItem(Icons.Outlined.PersonAdd, "添加账号") { dismissThen(onAddAccount) }
            ActionItem(Icons.Outlined.ManageAccounts, "管理账号") { dismissThen(onManageAccounts) }
        }
    }
}

@Composable
private fun ActionItem(icon: ImageVector, title: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        },
        headlineContent = { Text(title) },
    )
}
