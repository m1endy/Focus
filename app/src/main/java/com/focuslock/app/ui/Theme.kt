package com.focuslock.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

// ===== Organic Glassmorphism — палитра =====
// Названия Cyan/CyanDim/CardBlack сохранены из старой темы (чтобы не менять
// сотни мест использования по всему проекту), но теперь это нефритово-зелёная,
// а не голубая гамма — глубокий чёрно-зелёный фон, органическое стекло.

val DeepBlack = Color(0xFF050F0A)          // база фона
val CardBlack = Color(0xF00D1A14)          // почти непрозрачный тёмно-зелёный — для диалогов
val Cyan = Color(0xFF4CE0A0)               // акцент: нефритовый зелёный ("стекло")
val CyanDim = Color(0xFF1F6B4C)            // приглушённый акцент
val TextPrimary = Color(0xFFF2F7F3)        // тёплый белый с еле уловимой зеленью
val TextSecondary = Color(0xFF9BB0A2)      // шалфейный серо-зелёный
val DangerRed = Color(0xFFFF5D72)

val GlassFillTop = Color(0x26FFFFFF)       // блик стекла сверху
val GlassFillBottom = Color(0x14BFE8D2)    // мягкий зеленоватый снизу
val FernDeep = Color(0xFF0B2117)           // тень травы/папоротника
val FernLight = Color(0xFF25523A)          // светлый край травы
val DispersionViolet = Color(0xFFC7AEFF)   // блик дисперсии света в стекле
val DispersionAmber = Color(0xFFFFD9A3)    // тёплый блик дисперсии

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

val ScreenGradient = Brush.verticalGradient(
    listOf(Color(0xFF040907), Color(0xFF0A1712), Color(0xFF0D2018))
)

// ===== Стеклянная карточка =====
// Полупрозрачная заливка + мягкая "внутренняя тень" по краю (радиальный
// градиент поверх контента) + тонкая градиентная кайма, ловящая свет сверху.
fun Modifier.glassCard(radius: Int = 24): Modifier = this
    .clip(RoundedCornerShape(radius.dp))
    .background(Brush.verticalGradient(listOf(GlassFillTop, GlassFillBottom)))
    .drawWithContent {
        drawContent()
        drawRoundRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.22f)),
                radius = size.maxDimension.coerceAtLeast(1f) * 0.85f
            ),
            cornerRadius = CornerRadius(radius.dp.toPx(), radius.dp.toPx())
        )
    }
    .border(
        width = 1.dp,
        brush = Brush.linearGradient(
            listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0.04f), Cyan.copy(alpha = 0.14f))
        ),
        shape = RoundedCornerShape(radius.dp)
    )

// Плавное затухание (fade) верхнего и нижнего края прокручиваемого списка —
// создаёт ощущение, что контент продолжается за пределами видимой области,
// вместо резкого обрыва. Каждый край показывается, только если в ту сторону
// действительно есть что прокручивать (canScrollBackward/Forward), иначе
// когда список весь помещается на экране, никакого "обрубленного" фейда не
// будет видно зря.
fun Modifier.fadingEdges(state: LazyListState, edgeHeight: androidx.compose.ui.unit.Dp = 24.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val edgePx = edgeHeight.toPx()
        if (state.canScrollBackward) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black),
                    startY = 0f,
                    endY = edgePx
                ),
                blendMode = BlendMode.DstIn
            )
        }
        if (state.canScrollForward) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Black, Color.Transparent),
                    startY = size.height - edgePx,
                    endY = size.height
                ),
                blendMode = BlendMode.DstIn
            )
        }
    }

// ===== Зернистость (film grain) =====
// Один раз генерируем маленькую шумовую текстуру и тайлим её поверх фона —
// даёт лёгкую "матовую" фактуру вместо плоской заливки.
private fun generateGrainBitmap(sizePx: Int = 96): android.graphics.Bitmap {
    val bmp = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
    val rnd = java.util.Random(7L)
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            val v = rnd.nextInt(256)
            val a = rnd.nextInt(20)
            bmp.setPixel(x, y, android.graphics.Color.argb(a, v, v, v))
        }
    }
    return bmp
}

@Composable
fun GrainOverlay(modifier: Modifier = Modifier) {
    val grain = remember { generateGrainBitmap() }
    Canvas(modifier = modifier.fillMaxSize()) {
        drawIntoCanvas { canvas ->
            val shader = android.graphics.BitmapShader(
                grain, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT
            )
            val paint = android.graphics.Paint().apply { this.shader = shader }
            canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
        }
    }
}

// ===== Силуэт травы и папоротника =====
// Процедурно генерируем набор мягких изогнутых "стеблей" фиксированным
// сидом (стабильно между перекомпозициями) и сильно размываем — имитация
// боке из брифа без использования растровых изображений.
private data class Blade(
    val baseX: Float, val height: Float, val width: Float, val curve: Float, val alpha: Float, val mix: Float
)

private fun generateBlades(count: Int, seed: Long): List<Blade> {
    val rnd = java.util.Random(seed)
    return List(count) {
        Blade(
            baseX = rnd.nextFloat(),
            height = 0.32f + rnd.nextFloat() * 0.55f,
            width = 0.018f + rnd.nextFloat() * 0.03f,
            curve = (rnd.nextFloat() - 0.5f) * 0.7f,
            alpha = 0.30f + rnd.nextFloat() * 0.32f,
            mix = rnd.nextFloat()
        )
    }
}

@Composable
fun GrassFernSilhouette(modifier: Modifier = Modifier, heightFraction: Float = 0.4f) {
    val blades = remember { generateBlades(24, seed = 11L) }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(heightFraction)
            .blur(28.dp)
    ) {
        val w = size.width
        val h = size.height
        blades.forEach { b ->
            val baseX = b.baseX * w
            val bladeH = b.height * h
            val bladeW = (b.width * w).coerceAtLeast(2f)
            val sway = b.curve * bladeW * 7f
            val tipX = baseX + sway
            val color = lerp(FernDeep, FernLight, b.mix).copy(alpha = b.alpha)
            val path = Path().apply {
                moveTo(baseX - bladeW / 2f, h)
                quadraticBezierTo(baseX + sway * 0.6f, h - bladeH * 0.55f, tipX, h - bladeH)
                quadraticBezierTo(baseX + sway * 0.6f + bladeW, h - bladeH * 0.55f, baseX + bladeW / 2f, h)
                close()
            }
            drawPath(path, color = color)
        }
    }
}

// ===== Полный органический фон =====
// Градиент + зерно + силуэт травы снизу, за один вызов.
@Composable
fun OrganicBackground(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier.fillMaxSize().background(ScreenGradient), contentAlignment = contentAlignment) {
        GrainOverlay()
        GrassFernSilhouette(modifier = Modifier.align(Alignment.BottomCenter))
        content()
    }
}

// ===== "Стеклянная" иконка-бейдж =====
// Круглый значок под оптическое стекло: мягкое свечение позади, вертикальный
// градиент (блик сверху → акцент → тень снизу), тонкая светлая кайма и
// маленький цветной блик у края — намёк на дисперсию света в стекле.
@Composable
fun GlassIconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    sizeDp: Int = 46,
    accent: Color = Cyan
) {
    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(listOf(accent.copy(alpha = 0.30f), Color.Transparent)),
                    radius = size.maxDimension * 0.9f
                )
            }
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.22f), accent.copy(alpha = 0.30f), Color.Black.copy(alpha = 0.32f))
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.55f), Color.Transparent)),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = (sizeDp * 0.14f).dp, start = (sizeDp * 0.16f).dp)
                .size((sizeDp * 0.15f).dp)
                .clip(CircleShape)
                .background(DispersionViolet.copy(alpha = 0.55f))
        )
        Icon(icon, null, tint = TextPrimary, modifier = Modifier.size((sizeDp * 0.48f).dp))
    }
}
