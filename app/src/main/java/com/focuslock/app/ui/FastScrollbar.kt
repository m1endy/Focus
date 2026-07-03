package com.focuslock.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Компактный "ползунок" быстрой прокрутки для длинных списков — тянуть
 * пальцем по узкой полосе справа от списка удобнее, чем листать десятки
 * приложений по одному. При перетаскивании показывает всплывающую букву,
 * как в классическом списке контактов.
 *
 * Список должен быть отсортирован так же, как [labelForIndex] отдаёт
 * подписи (в FocusLock приложения отсортированы по алфавиту).
 */
@Composable
fun FastScrollbar(
    listState: LazyListState,
    itemCount: Int,
    modifier: Modifier = Modifier,
    labelForIndex: (Int) -> String? = { null }
) {
    if (itemCount < 2) return

    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    var isDragging by remember { mutableStateOf(false) }
    var trackHeightPx by remember { mutableStateOf(0f) }
    var dragProgress by remember { mutableStateOf(0f) }

    // Прогресс на основе текущей позиции списка (пока пользователь не тащит
    // сам ползунок) — учитывает частичное смещение внутри элемента, чтобы
    // полоска двигалась вместе со списком плавно, а не скачками по пунктам.
    val scrollProgress by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            if (total <= 1) {
                0f
            } else {
                val firstVisible = info.visibleItemsInfo.firstOrNull()
                val partial = if (firstVisible != null && firstVisible.size > 0) {
                    listState.firstVisibleItemScrollOffset.toFloat() / firstVisible.size.toFloat()
                } else 0f
                ((listState.firstVisibleItemIndex + partial) / (total - 1).toFloat()).coerceIn(0f, 1f)
            }
        }
    }

    val progress = if (isDragging) dragProgress else scrollProgress
    val thumbHeight = 46.dp
    val thumbHeightPx = with(density) { thumbHeight.toPx() }

    fun jumpTo(yPx: Float) {
        if (trackHeightPx <= 0f) return
        val usable = (trackHeightPx - thumbHeightPx).coerceAtLeast(1f)
        val p = ((yPx - thumbHeightPx / 2f) / usable).coerceIn(0f, 1f)
        dragProgress = p
        val targetIndex = (p * (itemCount - 1)).toInt().coerceIn(0, itemCount - 1)
        coroutineScope.launch { listState.scrollToItem(targetIndex) }
    }

    Box(
        modifier = modifier
            .width(32.dp)
            .onSizeChanged { trackHeightPx = it.height.toFloat() }
            .pointerInput(itemCount) {
                detectDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        jumpTo(offset.y)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        jumpTo(change.position.y)
                    },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false }
                )
            }
    ) {
        // Дорожка
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(4.dp)
                .fillMaxHeight(0.96f)
                .clip(RoundedCornerShape(2.dp))
                .background(TextSecondary.copy(alpha = 0.15f))
        )

        val offsetDp = with(density) {
            val usable = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
            (progress * usable).toDp()
        }

        // Сам ползунок
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = offsetDp)
                .width(if (isDragging) 8.dp else 5.dp)
                .height(thumbHeight)
                .clip(RoundedCornerShape(4.dp))
                .background(Cyan)
        )

        // Всплывающая буква текущей позиции — только во время перетаскивания
        if (isDragging) {
            val idx = (progress * (itemCount - 1)).toInt().coerceIn(0, itemCount - 1)
            val label = labelForIndex(idx)
            if (!label.isNullOrEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-44).dp, y = (offsetDp - 8.dp).coerceAtLeast(0.dp))
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Cyan),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label.uppercase(), color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}
