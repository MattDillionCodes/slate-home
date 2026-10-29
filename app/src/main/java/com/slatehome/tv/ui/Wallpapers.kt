package com.slatehome.tv.ui

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * The six built-in looks. Every one is drawn from proportions of the canvas, so the same code
 * paints the full 1080p wallpaper, the blurred copy inside each glass pane, and the thumbnails.
 */
enum class WallpaperStyle(val title: String) {
    Aurora("Aurora"),
    Dunes("Dunes"),
    Bloom("Bloom"),
    Ribbons("Ribbons"),
    Horizon("Horizon"),
    Pastel("Pastel");

    val id: String get() = name.lowercase()

    companion object {
        fun fromId(id: String?): WallpaperStyle? = entries.find { it.id == id }
    }
}

/** What's behind the home screen: one of the built-in styles, or a photo. */
sealed interface Backdrop {
    data class Style(val style: WallpaperStyle) : Backdrop
    class Photo(val image: ImageBitmap) : Backdrop
}

fun DrawScope.drawBackdrop(backdrop: Backdrop, canvas: Size) {
    // Glass panes can draw before the root reports its size; zero-size gradients would throw.
    if (canvas.width <= 0f || canvas.height <= 0f) return
    when (backdrop) {
        is Backdrop.Style -> drawStyle(backdrop.style, canvas)
        is Backdrop.Photo -> {
            drawCover(backdrop.image, canvas)
            // iOS dims busy photos slightly so white icon labels stay readable.
            drawRect(Color.Black.copy(alpha = 0.14f), size = canvas)
        }
    }
}

fun DrawScope.drawStyle(style: WallpaperStyle, canvas: Size) = when (style) {
    WallpaperStyle.Aurora -> drawAurora(canvas)
    WallpaperStyle.Dunes -> drawDunes(canvas)
    WallpaperStyle.Bloom -> drawBloom(canvas)
    WallpaperStyle.Ribbons -> drawRibbons(canvas)
    WallpaperStyle.Horizon -> drawHorizon(canvas)
    WallpaperStyle.Pastel -> drawPastel(canvas)
}

/** Like ContentScale.Crop: fill the canvas, keep the aspect ratio, trim the overflow evenly. */
fun DrawScope.drawCover(image: ImageBitmap, canvas: Size) {
    val scale = max(canvas.width / image.width, canvas.height / image.height)
    val srcW = (canvas.width / scale).roundToInt().coerceAtMost(image.width)
    val srcH = (canvas.height / scale).roundToInt().coerceAtMost(image.height)
    drawImage(
        image,
        srcOffset = IntOffset((image.width - srcW) / 2, (image.height - srcH) / 2),
        srcSize = IntSize(srcW, srcH),
        dstSize = IntSize(canvas.width.roundToInt(), canvas.height.roundToInt()),
        filterQuality = FilterQuality.Medium,
    )
}

private fun DrawScope.glow(canvas: Size, x: Float, y: Float, radius: Float, color: Color, alpha: Float) {
    val center = Offset(x * canvas.width, y * canvas.height)
    val r = radius * canvas.width
    drawCircle(
        Brush.radialGradient(
            0f to color.copy(alpha = alpha),
            0.5f to color.copy(alpha = alpha * 0.38f),
            1f to Color.Transparent,
            center = center,
            radius = r,
        ),
        radius = r,
        center = center,
    )
}

// ---------------------------------------------------------------------------------------------
// Rasterising — draw once, then every frame is just a texture
// ---------------------------------------------------------------------------------------------

/** The wallpaper drawn once into pixels: [sharp] fills the screen, [frosted] backs the glass. */
class WallpaperRaster(val sharp: ImageBitmap, val frosted: ImageBitmap)

/** Runs a DrawScope block into a fresh bitmap. Safe off the main thread. */
fun rasterize(width: Int, height: Int, block: DrawScope.() -> Unit): ImageBitmap {
    val image = ImageBitmap(width, height)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(width.toFloat(), height.toFloat()), block)
    return image
}

fun renderWallpaper(backdrop: Backdrop, width: Int, height: Int): WallpaperRaster {
    val sharp = rasterize(width, height) { drawBackdrop(backdrop, size) }
    val frosted = frost(sharp)
    // Wallpapers are fully opaque. Saying so lets the GPU skip blending them over what's below.
    sharp.asAndroidBitmap().setHasAlpha(false)
    frosted.asAndroidBitmap().setHasAlpha(false)
    return WallpaperRaster(sharp, frosted)
}

/**
 * A frosted copy that's cheap to show: shrink 8× (which already averages detail away), then a
 * 3-pass box blur (≈ Gaussian). Drawn back up with bilinear filtering it reads as a heavy blur,
 * for the cost of one small texture instead of a GPU blur on every frame.
 */
fun frost(image: ImageBitmap): ImageBitmap {
    val source = image.asAndroidBitmap().let {
        if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it
    }
    val small = Bitmap.createScaledBitmap(
        source,
        (source.width / FROST_SHRINK).coerceAtLeast(1),
        (source.height / FROST_SHRINK).coerceAtLeast(1),
        true,
    )
    return boxBlur(small, radius = 5, passes = 3).asImageBitmap()
}

private const val FROST_SHRINK = 8

private fun boxBlur(bitmap: Bitmap, radius: Int, passes: Int): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    var src = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
    var dst = IntArray(w * h)
    repeat(passes) {
        for (horizontal in listOf(true, false)) {
            blurPass(src, dst, w, h, radius, horizontal)
            val swap = src
            src = dst
            dst = swap
        }
    }
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(src, 0, w, 0, 0, w, h) }
}

/** Sliding-window average along every row (or column), clamping at the edges. */
private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val length = if (horizontal) w else h
    val step = if (horizontal) 1 else w
    val window = 2 * r + 1
    for (line in 0 until lines) {
        val base = if (horizontal) line * w else line
        fun at(i: Int) = src[base + i.coerceIn(0, length - 1) * step]
        var a = 0
        var red = 0
        var green = 0
        var blue = 0
        for (i in -r..r) {
            val p = at(i)
            a += p ushr 24; red += p shr 16 and 0xFF; green += p shr 8 and 0xFF; blue += p and 0xFF
        }
        for (i in 0 until length) {
            dst[base + i * step] = (a / window shl 24) or (red / window shl 16) or (green / window shl 8) or (blue / window)
            val leaving = at(i - r)
            val entering = at(i + r + 1)
            a += (entering ushr 24) - (leaving ushr 24)
            red += (entering shr 16 and 0xFF) - (leaving shr 16 and 0xFF)
            green += (entering shr 8 and 0xFF) - (leaving shr 8 and 0xFF)
            blue += (entering and 0xFF) - (leaving and 0xFF)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 1. Aurora — iPadOS 26's soft colour glow
// ---------------------------------------------------------------------------------------------

private fun DrawScope.drawAurora(canvas: Size) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF070B1E), Color(0xFF160A28)), endY = canvas.height), size = canvas)
    glow(canvas, 0.10f, 0.15f, 0.55f, Color(0xFF2F5BFF), 0.80f)
    glow(canvas, 0.52f, 0.00f, 0.42f, Color(0xFF8B3DFF), 0.80f)
    glow(canvas, 0.95f, 0.40f, 0.40f, Color(0xFFFF3D8B), 0.80f)
    glow(canvas, 0.74f, 1.08f, 0.50f, Color(0xFFFF8A1F), 0.80f)
    glow(canvas, 0.06f, 1.02f, 0.40f, Color(0xFF14C8E0), 0.80f)
}

// ---------------------------------------------------------------------------------------------
// 2. Dunes — layered hills at dusk, in the spirit of macOS Big Sur
// ---------------------------------------------------------------------------------------------

private class Hill(val base: Float, val amp: Float, val freq: Float, val phase: Float, val top: Color, val bottom: Color)

private val hills = listOf(
    Hill(0.60f, 0.040f, 0.8f, 0.10f, Color(0xFFE07A8F), Color(0xFFB0476F)),
    Hill(0.69f, 0.050f, 1.1f, 0.55f, Color(0xFFA9488F), Color(0xFF6F2C7E)),
    Hill(0.79f, 0.045f, 0.7f, 0.30f, Color(0xFF5E2F86), Color(0xFF3A1F66)),
    Hill(0.89f, 0.040f, 1.3f, 0.80f, Color(0xFF2A1E5C), Color(0xFF120E33)),
)

private fun DrawScope.drawDunes(canvas: Size) {
    drawRect(
        Brush.verticalGradient(
            0f to Color(0xFF1B1446),
            0.38f to Color(0xFF6B2C8F),
            0.60f to Color(0xFFF0766A),
            0.75f to Color(0xFFFDBA74),
            endY = canvas.height,
        ),
        size = canvas,
    )
    glow(canvas, 0.68f, 0.58f, 0.32f, Color(0xFFFFD27A), 0.60f)
    for (hill in hills) {
        val path = Path()
        val steps = 96
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val y = hill.base + hill.amp * sin(2 * PI * (t * hill.freq + hill.phase)).toFloat() +
                hill.amp * 0.35f * sin(2 * PI * (t * hill.freq * 2.7f + hill.phase)).toFloat()
            if (i == 0) path.moveTo(0f, y * canvas.height) else path.lineTo(t * canvas.width, y * canvas.height)
        }
        path.lineTo(canvas.width, canvas.height)
        path.lineTo(0f, canvas.height)
        path.close()
        val crest = (hill.base - hill.amp * 1.4f) * canvas.height
        drawPath(path, Brush.verticalGradient(listOf(hill.top, hill.bottom), startY = crest, endY = canvas.height))
    }
}

// ---------------------------------------------------------------------------------------------
// 3. Bloom — overlapping glowing petals, like iOS 18's flower
// ---------------------------------------------------------------------------------------------

private fun DrawScope.drawBloom(canvas: Size) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF0A0614), Color(0xFF1A0820)), endY = canvas.height), size = canvas)
    val center = Offset(0.5f * canvas.width, 0.56f * canvas.height)
    glow(canvas, 0.5f, 0.56f, 0.55f, Color(0xFFB0185E), 0.45f)
    petals(center, count = 10, length = 0.46f * canvas.height, width = 0.13f * canvas.width,
        inner = Color(0xFFFF2D78), outer = Color(0xFF7A2BFF), offsetDeg = 0f)
    petals(center, count = 8, length = 0.30f * canvas.height, width = 0.09f * canvas.width,
        inner = Color(0xFFFFB020), outer = Color(0xFFFF4D6D), offsetDeg = 22.5f)
    // The heart sits behind the app grid, so keep it warm rather than white-hot for label contrast.
    glow(canvas, 0.5f, 0.56f, 0.10f, Color(0xFFFFC2A8), 0.40f)
    drawRect(Color.Black.copy(alpha = 0.18f), size = canvas)
}

private fun DrawScope.petals(
    center: Offset,
    count: Int,
    length: Float,
    width: Float,
    inner: Color,
    outer: Color,
    offsetDeg: Float,
) {
    repeat(count) { i ->
        rotate(offsetDeg + i * 360f / count, pivot = center) {
            drawOval(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.25f to outer.copy(alpha = 0.55f),
                    1f to inner.copy(alpha = 0.75f),
                    startY = center.y - length,
                    endY = center.y,
                ),
                topLeft = Offset(center.x - width / 2f, center.y - length),
                size = Size(width, length),
                // Screen blending brightens where petals overlap, like light rather than paint.
                blendMode = BlendMode.Screen,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 4. Ribbons — translucent flowing bands with a glassy top edge (iOS 26 / iPadOS 16)
// ---------------------------------------------------------------------------------------------

private class Ribbon(val y: Float, val rise: Float, val fall: Float, val thickness: Float, val colors: List<Color>)

private val ribbons = listOf(
    Ribbon(0.30f, 0.22f, 0.18f, 0.16f, listOf(Color(0xFF22D3EE), Color(0xFF3B82F6))),
    Ribbon(0.48f, 0.26f, 0.20f, 0.20f, listOf(Color(0xFF3B82F6), Color(0xFFA855F7))),
    Ribbon(0.66f, 0.18f, 0.24f, 0.18f, listOf(Color(0xFFA855F7), Color(0xFFEC4899))),
    Ribbon(0.84f, 0.20f, 0.16f, 0.14f, listOf(Color(0xFF14B8A6), Color(0xFF6366F1))),
)

private fun DrawScope.drawRibbons(canvas: Size) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF040B1F), Color(0xFF0A1E4A)), endY = canvas.height), size = canvas)
    glow(canvas, 0.25f, 0.35f, 0.45f, Color(0xFF1D4ED8), 0.45f)
    for (r in ribbons) {
        val w = canvas.width
        val h = canvas.height
        val path = Path().apply {
            moveTo(-0.1f * w, r.y * h)
            cubicTo(0.30f * w, (r.y - r.rise) * h, 0.62f * w, (r.y + r.fall) * h, 1.1f * w, (r.y - r.rise * 0.6f) * h)
        }
        val brush = Brush.horizontalGradient(r.colors.map { it.copy(alpha = 0.55f) })
        drawPath(path, brush, style = Stroke(r.thickness * h, cap = StrokeCap.Round), blendMode = BlendMode.Screen)
        // A thin bright line just above centre reads as light catching a glass edge.
        translate(top = -r.thickness * h * 0.32f) {
            drawPath(path, Color.White.copy(alpha = 0.22f), style = Stroke(0.0035f * h, cap = StrokeCap.Round))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 5. Horizon — a planet's glowing limb against the stars (iOS 16 Astronomy)
// ---------------------------------------------------------------------------------------------

private class Star(val x: Float, val y: Float, val r: Float, val alpha: Float)

// Seeded, so the sky is identical every launch and in every glass pane.
private val stars = Random(7).let { rnd ->
    List(160) { Star(rnd.nextFloat(), rnd.nextFloat() * 0.72f, 0.4f + rnd.nextFloat() * 1.1f, 0.25f + rnd.nextFloat() * 0.7f) }
}

private fun DrawScope.drawHorizon(canvas: Size) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF000000), Color(0xFF030918)), endY = canvas.height), size = canvas)
    val px = canvas.width / 960f
    for (s in stars) {
        drawCircle(Color.White.copy(alpha = s.alpha), radius = s.r * px, center = Offset(s.x * canvas.width, s.y * canvas.height))
    }
    val radius = 1.15f * canvas.width
    val center = Offset(0.5f * canvas.width, 0.74f * canvas.height + radius)
    // Atmosphere: a thin cyan-to-blue ring hugging the planet's edge.
    drawCircle(
        Brush.radialGradient(
            0f to Color.Transparent,
            0.935f to Color.Transparent,
            0.952f to Color(0xFF7DD3FC).copy(alpha = 0.95f),
            0.972f to Color(0xFF2563EB).copy(alpha = 0.40f),
            1f to Color.Transparent,
            center = center,
            radius = radius * 1.06f,
        ),
        radius = radius * 1.06f,
        center = center,
    )
    drawCircle(
        Brush.radialGradient(
            0f to Color(0xFF000000),
            0.90f to Color(0xFF03101F),
            1f to Color(0xFF0B3A6E),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
    glow(canvas, 0.64f, 0.735f, 0.12f, Color(0xFFE0F2FE), 0.75f)
}

// ---------------------------------------------------------------------------------------------
// 6. Pastel — out-of-focus light on a soft gradient (iOS 7)
// ---------------------------------------------------------------------------------------------

private class Bokeh(val x: Float, val y: Float, val r: Float, val alpha: Float)

private val bokeh = Random(3).let { rnd ->
    List(26) { Bokeh(rnd.nextFloat(), rnd.nextFloat(), 0.03f + rnd.nextFloat() * 0.09f, 0.06f + rnd.nextFloat() * 0.16f) }
}

private fun DrawScope.drawPastel(canvas: Size) {
    drawRect(
        Brush.linearGradient(
            listOf(Color(0xFF7FB3F5), Color(0xFFC3A6F2), Color(0xFFF2A7C3)),
            start = Offset.Zero,
            end = Offset(canvas.width, canvas.height),
        ),
        size = canvas,
    )
    for (b in bokeh) {
        val center = Offset(b.x * canvas.width, b.y * canvas.height)
        val r = b.r * canvas.width
        drawCircle(
            Brush.radialGradient(
                0f to Color.White.copy(alpha = b.alpha * 0.55f),
                0.85f to Color.White.copy(alpha = b.alpha),
                1f to Color.Transparent,
                center = center,
                radius = r,
            ),
            radius = r,
            center = center,
        )
    }
    // A light veil keeps white labels legible on the brightest wallpaper.
    drawRect(Color(0xFF1E1B4B).copy(alpha = 0.34f), size = canvas)
}
