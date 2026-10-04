package com.bilichat.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.bilichat.app.domain.model.EmoteItem
import com.bilichat.app.domain.model.EmotePackage

/** 输入框下方的表情面板：底部切换表情包，点选插入或发送。 */
@Composable
internal fun EmotePanel(
    state: EmotePanelState,
    onSelect: (EmoteItem) -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
    ) {
        when (state) {
            EmotePanelState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is EmotePanelState.Failed -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                TextButton(onClick = onRetry) { Text("重试") }
            }
            is EmotePanelState.Ready -> if (state.packages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无可用表情", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                PackagesPager(state.packages, onSelect, onDelete)
            }
        }
    }
}

@Composable
private fun PackagesPager(
    packages: List<EmotePackage>,
    onSelect: (EmoteItem) -> Unit,
    onDelete: () -> Unit,
) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val current = packages[selected.coerceIn(packages.indices)]
    val cellSize = when {
        current.isKaomoji -> 104.dp
        current.emotes.any { it.large } -> 76.dp
        else -> 46.dp
    }
    Column(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(cellSize),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(8.dp),
        ) {
            items(current.emotes) { emote ->
                EmoteCell(emote, kaomoji = current.isKaomoji, onClick = { onSelect(emote) })
            }
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            LazyRow(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                itemsIndexed(packages) { index, pkg ->
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (index == selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                            )
                            .clickable { selected = index },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (pkg.iconUrl != null && !pkg.isKaomoji) {
                            AsyncImage(model = pkg.iconUrl, contentDescription = pkg.name, modifier = Modifier.size(28.dp))
                        } else {
                            Text(
                                text = pkg.name.take(2),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.AutoMirrored.Outlined.Backspace, contentDescription = "删除")
            }
        }
    }
}

@Composable
private fun EmoteCell(emote: EmoteItem, kaomoji: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .then(if (kaomoji) Modifier.height(44.dp) else Modifier.aspectRatio(1f))
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (kaomoji || emote.url == null) {
            Text(
                text = emote.text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            AsyncImage(model = emote.url, contentDescription = emote.text, modifier = Modifier.fillMaxSize())
        }
    }
}
