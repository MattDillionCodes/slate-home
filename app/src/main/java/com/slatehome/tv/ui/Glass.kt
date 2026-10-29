package com.slatehome.tv.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.draw.shadow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toIntSize
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

object Palette {
    val label = Color.White
    val secondary = Color.White.copy(alpha = 0.62f)
    val blue = Color(0xFF0A84FF)
    val red = Color(0xFFFF453A)
    val orange = Color(0xFFFF9F0A)
    val ink = Color(0xFF1C1C1E)
}

// ---------------------------------------------------------------------------------------------
// Shapes
// ---------------------------------------------------------------------------------------------

/**
 * Apple's "continuous corner": a superellipse |x|^n + |y|^n = 1 with n ≈ 5, rather than a
 * rounded rectangle. The curvature eases in instead of snapping from straight to circular,
 * which is most of why iOS icons look softer than Android's rounded squares.
 */
object SquircleShape : Shape {
    private const val EXPONENT = 5.0
    private const val STEPS = 120

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val rx = size.width / 2f
        val ry = size.height / 2f
        val power = 2.0 / EXPONENT
        val path = Path()
        for (i in 0..STEPS) {
            val t = 2.0 * PI * i / STEPS
            val c = cos(t)
            val s = sin(t)
            val x = rx + rx * (sign(c) * abs(c).pow(power)).toFloat()
            val y = ry + ry * (sign(s) * abs(s).pow(power)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }
}

// ---------------------------------------------------------------------------------------------
// Ambient state shared through the tree
// ---------------------------------------------------------------------------------------------

/** Full-screen size in px, so glass surfaces can line their blurred backdrop up with the wallpaper. */
val LocalBackdropSize = compositionLocalOf { Size.Zero }

/** False while an overlay (Control Center, App Library) owns the remote. */
val LocalHomeInteractive = compositionLocalOf { true }

/** A ticking clock. Read `.value` as deep in the tree as possible so only leaves recompose. */
val LocalNow = compositionLocalOf<State<LocalDateTime>> { error("LocalNow not provided") }

@Composable
fun rememberNow(): State<LocalDateTime> = produceState(LocalDateTime.now()) {
    while (true) {
        delay(1000 - System.currentTimeMillis() % 1000)
        value = LocalDateTime.now()
    }
}

// ---------------------------------------------------------------------------------------------
// Wallpaper
// ---------------------------------------------------------------------------------------------

/** The active wallpaper, drawn directly only until its raster is ready. */
val LocalWallpaper = compositionLocalOf<Backdrop> { Backdrop.Style(WallpaperStyle.Aurora) }

/** The active wallpaper as pixels — the screen and every glass pane draw from this. */
val LocalWallpaperRaster = compositionLocalOf<WallpaperRaster?> { null }

/** Static on purpose: a TV home screen sits idle for hours, so nothing should redraw by itself. */
@Composable
fun Wallpaper(modifier: Modifier = Modifier) {
    val backdrop = LocalWallpaper.current
    Crossfade(LocalWallpaperRaster.current, modifier.fillMaxSize(), tween(450), label = "wallpaper") { raster ->
        Canvas(Modifier.fillMaxSize()) {
            if (raster != null) drawImage(raster.sharp, dstSize = size.toIntSize()) else drawBackdrop(backdrop, size)
        }
    }
}

/**
 * Draws this subtree once into its own GPU texture and reuses it until the content changes
 * (Android's "hardware layer"). Without it, a TV GPU redraws every gradient, rounded clip and
 * blurred text shadow on screen for each frame of *any* animation — measured on a TCL Google TV,
 * that was ~20 ms/frame of static decoration.
 * The layer clips to its bounds, so leave room for anything that spills out (shadows, glows).
 */
fun Modifier.cached(): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }

// ---------------------------------------------------------------------------------------------
// Liquid Glass
// ---------------------------------------------------------------------------------------------

private val GlassRim = Brush.linearGradient(
    listOf(
        Color.White.copy(alpha = 0.55f),
        Color.White.copy(alpha = 0.06f),
        Color.White.copy(alpha = 0.06f),
        Color.White.copy(alpha = 0.28f),
    ),
)
private val GlassSheen = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.10f),
    0.45f to Color.Transparent,
)

/**
 * A translucent pane that shows a *blurred* copy of the wallpaper behind it.
 *
 * Android can't blur what's behind a view, and a live GPU blur per pane gets re-run on every
 * frame of every animation. Instead each pane draws its slice of the pre-frosted wallpaper
 * bitmap, lined up with where the pane sits on screen.
 * Content is not clipped, so focused children can scale past the edge like iPad's dock.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    tint: Color = Color.White.copy(alpha = 0.10f),
    content: @Composable BoxScope.() -> Unit,
) {
    val canvas = LocalBackdropSize.current
    val raster = LocalWallpaperRaster.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.onGloballyPositioned { origin = it.positionInRoot() }) {
        // The pane itself is static, so it lives in a cached layer; content() stays outside it
        // because focused dock icons and their tooltips spill past the pane's edges.
        Box(Modifier.matchParentSize().cached()) {
            Canvas(Modifier.matchParentSize().clip(shape)) {
                if (raster != null) {
                    drawImage(
                        raster.frosted,
                        dstOffset = IntOffset(-origin.x.roundToInt(), -origin.y.roundToInt()),
                        dstSize = canvas.toIntSize(),
                        filterQuality = FilterQuality.Low,
                    )
                }
            }
            Box(
                Modifier
                    .matchParentSize()
                    .clip(shape)
                    .background(tint)
                    .background(GlassSheen)
                    .border(1.dp, GlassRim, shape)
            )
        }
        content()
    }
}

// ---------------------------------------------------------------------------------------------
// Focus — the tvOS-style "lift" that replaces iPad's touch feedback
// ---------------------------------------------------------------------------------------------

/**
 * Anything the remote can land on. Focus scales it up on a soft spring and drops a shadow
 * under it; pressing OK squishes it slightly. [content] gets whether it's currently focused.
 */
@Composable
fun Pressable(
    onClick: () -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    focusedScale: Float = 1.12f,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val focused by interactionSource.collectIsFocusedAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> focusedScale * 0.94f
            focused -> focusedScale
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 420f),
        label = "focusScale",
    )
    val lift by animateDpAsState(if (focused) 20.dp else 0.dp, tween(220), label = "focusLift")
    val interactive = LocalHomeInteractive.current

    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(lift, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
            .focusProperties { canFocus = interactive }
            .then(if (onLongClick != null) Modifier.holdToLongPress(interactionSource, onClick, onLongClick) else Modifier)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content(focused)
    }
}

/**
 * Holding OK on the remote = long press, like a long tap on iPad. Compose's combinedClickable
 * only does this for touch, so we read the keys ourselves: Android re-sends KeyDown with a
 * rising repeatCount while a key is held, and the first repeat means "this is a hold".
 */
@Composable
private fun Modifier.holdToLongPress(
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
): Modifier {
    val hold = remember { HoldState() }
    return onPreviewKeyEvent { event ->
        if (event.key != Key.DirectionCenter && event.key != Key.Enter && event.key != Key.NumPadEnter) {
            return@onPreviewKeyEvent false
        }
        when {
            event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0 -> {
                hold.fired = false
                hold.press = PressInteraction.Press(Offset.Zero).also { interactionSource.tryEmit(it) }
            }
            event.type == KeyEventType.KeyDown && !hold.fired -> {
                hold.fired = true
                hold.release(interactionSource)
                onLongClick()
            }
            event.type == KeyEventType.KeyUp -> {
                hold.release(interactionSource)
                if (!hold.fired) onClick()
            }
        }
        true // we own OK/Enter here, so clickable never sees a half-finished press
    }
}

private class HoldState {
    var press: PressInteraction.Press? = null
    var fired = false

    fun release(source: MutableInteractionSource) {
        press?.let { source.tryEmit(PressInteraction.Release(it)) }
        press = null
    }
}

/**
 * Requests focus one frame later, once layout has attached the target. Lazy lists and
 * just-appeared overlays aren't measured yet in the frame that composes them.
 */
suspend fun FocusRequester.requestFocusSafely() {
    withFrameNanos { }
    runCatching { requestFocus() }
}

/**
 * A diagonal glint that sweeps across once when focus lands — Liquid Glass catching the light.
 * Painted *as* [shape] instead of clipped to it: custom-path clips are slow on TV GPUs.
 */
@Composable
fun Modifier.focusSheen(focused: Boolean, shape: Shape): Modifier {
    val progress = remember { Animatable(1f) }
    LaunchedEffect(focused) {
        if (focused) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
        }
    }
    return drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        onDrawWithContent {
            drawContent()
            val p = progress.value
            if (focused && p < 1f) {
                val cx = -0.6f * size.width + p * 2.2f * size.width
                drawOutline(
                    outline,
                    Brush.linearGradient(
                        0f to Color.Transparent,
                        0.5f to Color.White.copy(alpha = 0.42f),
                        1f to Color.Transparent,
                        start = Offset(cx - size.width * 0.35f, 0f),
                        end = Offset(cx + size.width * 0.35f, size.height),
                    ),
                )
            }
        }
    }
}
