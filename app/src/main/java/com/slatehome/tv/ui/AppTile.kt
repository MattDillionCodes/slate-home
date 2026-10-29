package com.slatehome.tv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slatehome.tv.AppEntry

private val IconSheen = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.32f),
    0.42f to Color.White.copy(alpha = 0.0f),
)
private val IconRim = Brush.linearGradient(
    listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.22f)),
)

/** Blurred, so only ever draw it inside a [cached] layer — live, it cost ~8 ms/frame on a TV. */
val LabelShadow = Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 1.5f), 6f)

/**
 * Android 12 can only cast shadows natively for rects, rounded rects and circles, so the focus
 * shadow uses this near-identical rounded rect instead of the squircle.
 */
val IconClip = RoundedCornerShape(percent = 23)

private val EmptyIconFill = Brush.verticalGradient(listOf(Color(0xFF3A3A3C), Color(0xFF1C1C1E)))

/**
 * The app's real icon, cut to iOS's squircle with a touch of Liquid Glass on top. Squircle clips
 * are slow per frame on TV GPUs, but inside a [cached] layer this one is drawn just once.
 */
@Composable
fun AppArtwork(app: AppEntry, modifier: Modifier = Modifier) {
    Box(
        modifier
            .cached()
            .clip(SquircleShape)
            .background(EmptyIconFill),
        contentAlignment = Alignment.Center,
    ) {
        if (app.icon != null) {
            Image(app.icon, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else {
            Text(app.label.take(1), color = Color.White, fontWeight = FontWeight.Bold)
        }
        // A soft specular highlight on top and a light-catching rim.
        Box(Modifier.matchParentSize().background(IconSheen).border(1.dp, IconRim, SquircleShape))
    }
}

enum class LabelMode { Below, Tooltip, None }

/**
 * An app the remote can land on. [LabelMode.Below] is the home-grid look; [LabelMode.Tooltip]
 * is the dock look (no labels until focused, then a small pill floats above — like macOS).
 */
@Composable
fun AppTile(
    app: AppEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 68.dp,
    labelMode: LabelMode = LabelMode.Below,
    labelSize: TextUnit = 13.sp,
    focusedScale: Float = 1.12f,
    focusRequester: FocusRequester? = null,
    onLongClick: (() -> Unit)? = null,
    /** Jiggle-mode rotation in degrees, read at draw time so the rocking never recomposes. */
    wiggle: (() -> Float)? = null,
    /** Picked up in edit mode: drawn bigger and floating until it's dropped. */
    lifted: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Column(
        modifier.then(if (wiggle != null) Modifier.graphicsLayer { rotationZ = wiggle() } else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Pressable(
                onClick = onClick,
                shape = IconClip, // only used for the focus shadow
                modifier = Modifier
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .size(iconSize),
                focusedScale = if (lifted) 1.26f else focusedScale,
                interactionSource = interaction,
                onLongClick = onLongClick,
            ) { isFocused ->
                AppArtwork(app, Modifier.matchParentSize())
                if (isFocused) {
                    Box(Modifier.matchParentSize().border(2.dp, Color.White.copy(alpha = 0.95f), SquircleShape))
                }
                Box(Modifier.matchParentSize().focusSheen(isFocused, SquircleShape))
            }
            if (labelMode == LabelMode.Tooltip && focused) {
                FloatingLabel(app.label, Modifier.align(Alignment.TopCenter))
            }
        }
        if (labelMode == LabelMode.Below) {
            Spacer(Modifier.height(6.dp))
            AppLabel(app.label, focused, labelSize)
        }
    }
}

@Composable
private fun AppLabel(label: String, focused: Boolean, size: TextUnit) {
    // Padding inside the cached layer leaves room for the blurred shadow, which it would clip.
    Text(
        label,
        color = if (focused) Palette.label else Palette.label.copy(alpha = 0.82f),
        fontSize = size,
        fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(shadow = LabelShadow),
        modifier = Modifier.cached().padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

/** Zero-size anchor that draws its pill *above* the parent without changing anyone's layout. */
@Composable
private fun FloatingLabel(label: String, modifier: Modifier) {
    Box(
        modifier.layout { measurable, _ ->
            val placeable = measurable.measure(Constraints())
            layout(0, 0) {
                placeable.place(-placeable.width / 2, -placeable.height - 14.dp.roundToPx())
            }
        }
    ) {
        Text(
            label,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Palette.ink.copy(alpha = 0.82f))
                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** iPad's App Library dock icon: a glass squircle holding a 2×2 of your own apps, in miniature. */
@Composable
fun AppLibraryTile(
    onClick: () -> Unit,
    previews: List<AppEntry>,
    modifier: Modifier = Modifier,
    iconSize: Dp = 56.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(modifier, contentAlignment = Alignment.Center) {
        Pressable(onClick, IconClip, Modifier.size(iconSize), 1.18f, interaction) { isFocused ->
            Box(
                Modifier
                    .matchParentSize()
                    .cached()
                    .background(Color.White.copy(alpha = 0.22f), SquircleShape)
                    .border(if (isFocused) 2.dp else 1.dp, Color.White.copy(alpha = if (isFocused) 0.95f else 0.4f), SquircleShape)
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                previews.take(4).chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        pair.forEach { AppArtwork(it, Modifier.size(iconSize * 0.34f)) }
                    }
                }
            }
            Box(Modifier.matchParentSize().focusSheen(isFocused, SquircleShape))
        }
        if (focused) FloatingLabel("App Library", Modifier.align(Alignment.TopCenter))
    }
}
