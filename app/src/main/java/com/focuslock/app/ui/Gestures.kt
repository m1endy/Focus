package com.focuslock.app.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Свайп влево/вправо для небольших переключаемых секций (тип расписания,
 * периоды статистики). Внешние вкладки уже используют полноценный
 * HorizontalPager — вкладывать ещё один такой же пейджер внутрь его страницы
 * рискованно (конфликт жестов по одной и той же оси), поэтому здесь —
 * простой, изолированный жест, который не трогает вертикальную прокрутку и
 * не мешает внешнему пейджеру: он ловит только явный горизонтальный свайп в
 * своей области и сам решает, когда сработать.
 *
 * Это ДОПОЛНЕНИЕ к обычным кнопкам/переключателю, а не замена — они
 * продолжают работать как раньше.
 */
fun Modifier.swipeToChange(
    threshold: Float = 90f,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit
): Modifier = this.pointerInput(Unit) {
    var totalDrag = 0f
    detectHorizontalDragGestures(
        onDragStart = { totalDrag = 0f },
        onDragEnd = {
            when {
                totalDrag <= -threshold -> onSwipeLeft()
                totalDrag >= threshold -> onSwipeRight()
            }
        },
        onDragCancel = { totalDrag = 0f },
        onHorizontalDrag = { change, dragAmount ->
            change.consume()
            totalDrag += dragAmount
        }
    )
}
