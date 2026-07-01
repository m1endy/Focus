package com.focuslock.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val DeepBlack = Color(0xFF0A0A0F)
val CardBlack = Color(0xCC121218)
val Cyan = Color(0xFF00D2FF)
val CyanDim = Color(0xFF0088AA)
val TextPrimary = Color(0xFFF2F5F7)
val TextSecondary = Color(0xFF8A8F98)
val DangerRed = Color(0xFFFF4D6D)

private val FocusLockColors = darkColorScheme(
    primary = Cyan,
    onPrimary = Color.Black,
    background = DeepBlack,
    onBackground = TextPrimary,
    surface = CardBlack,
    onSurface = TextPrimary,
    secondary = CyanDim,
    error = DangerRed
)

@Composable
fun FocusLockTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = FocusLockColors, content = content)
}

fun Modifier.glassCard(radius: Int = 20): Modifier = this
    .clip(RoundedCornerShape(radius.dp))
    .background(CardBlack)
    .border(
        width = 1.dp,
        brush = Brush.linearGradient(listOf(Cyan.copy(alpha = 0.5f), Color.Transparent, CyanDim.copy(alpha = 0.3f))),
        shape = RoundedCornerShape(radius.dp)
    )

val ScreenGradient = Brush.verticalGradient(listOf(DeepBlack, Color(0xFF0D0D14)))
