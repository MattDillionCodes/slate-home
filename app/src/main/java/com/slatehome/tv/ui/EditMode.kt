package com.slatehome.tv.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slatehome.tv.AppEntry

/** iPad's "jiggle mode" for the home grid, driven by the remote. */
@Stable
class EditState {
    var editing by mutableStateOf(false)

    /** The app picked up with OK and now following the arrow keys. */
    var lifted by mutableStateOf<String?>(null)

    /** The app waiting on "Remove …?" confirmation. Kept after the dialog closes so it can animate out. */
    var removing by mutableStateOf<AppEntry?>(null)

    // One FocusRequester per package, so focus can follow an app as it moves or reappears.
    private val requesters = HashMap<String, FocusRequester>()

    fun focusFor(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    fun done() {
        editing = false
        lifted = null
    }

    companion object {
        const val ADD_TILE = "+add"
    }
}

/** iOS's jiggle: a fast ±1.8° rock. Only composed while editing, so it costs nothing otherwise. */
@Composable
fun rememberWiggle(): State<Float> = rememberInfiniteTransition(label = "wiggle").animateFloat(
    initialValue = -1.8f,
    targetValue = 1.8f,
    animationSpec = infiniteRepeatable(tween(130, easing = LinearEasing), RepeatMode.Reverse),
    label = "angle",
)

/** The "+" cell at the end of the grid while there's room for another app. */
@Composable
fun AddAppTile(onClick: () -> Unit, focusRequester: FocusRequester, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Pressable(
            onClick = onClick,
            shape = IconClip,
            modifier = Modifier.focusRequester(focusRequester).size(68.dp),
            interactionSource = interaction,
        ) { isFocused ->
            Box(
                Modifier
                    .matchParentSize()
                    .background(Color.White.copy(alpha = if (isFocused) 0.92f else 0.14f), SquircleShape)
                    .border(1.5.dp, Color.White.copy(alpha = 0.45f), SquircleShape)
            )
            Icon(Icons.Rounded.Add, null, tint = if (isFocused) Palette.ink else Color.White, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Add App",
            color = if (focused) Palette.label else Palette.label.copy(alpha = 0.82f),
            fontSize = 13.sp,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}

/** The small pill in the status bar that says what the remote does right now. */
@Composable
fun EditHint(liftedLabel: String?, liftedInDock: Boolean) {
    Text(
        when {
            liftedLabel == null -> "Editing Home Screen  ·  OK: move  ·  Hold OK: remove  ·  Back: done"
            liftedInDock -> "Moving “$liftedLabel”  ·  ◀ ▶ reorder  ·  ▲ back to the grid  ·  OK: drop"
            else -> "Moving “$liftedLabel”  ·  Arrows move it  ·  ▼ past the last row: Dock  ·  OK: drop"
        },
        color = Palette.label,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/** iOS's "Remove App?" alert. */
@Composable
fun RemoveAppDialog(app: AppEntry, onRemove: () -> Unit, onCancel: () -> Unit) {
    ConfirmDialog(
        title = "Remove “${app.label}”?",
        message = "It'll stay in your App Library.",
        confirmLabel = "Remove",
        onConfirm = onRemove,
        onCancel = onCancel,
        artwork = { AppArtwork(app, Modifier.size(64.dp)) },
    )
}

/** An iOS-style alert with a destructive action. Focus starts on Cancel so a stray OK is harmless. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    artwork: (@Composable () -> Unit)? = null,
) {
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancel.requestFocusSafely() }
    // Opened by *holding* OK, so that same press is still repeating when Cancel gets focus.
    // Ignore OK until a fresh press starts, or letting go would tap Cancel straight away.
    val armed = remember { BooleanArray(1) }
    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            val isOk = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
            if (!isOk) return@onPreviewKeyEvent false
            if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) armed[0] = true
            !armed[0]
        },
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(Modifier.width(440.dp), RoundedCornerShape(30.dp), Color.Black.copy(alpha = 0.35f)) {
            Column(Modifier.padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (artwork != null) {
                    artwork()
                    Spacer(Modifier.height(14.dp))
                }
                Text(title, color = Palette.label, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(message, color = Palette.secondary, fontSize = 14.sp)
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    DialogButton(confirmLabel, destructive = true, onClick = onConfirm)
                    DialogButton("Cancel", Modifier.focusRequester(cancel), onClick = onCancel)
                }
            }
        }
    }
}

@Composable
private fun DialogButton(
    text: String,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Pressable(onClick, shape, modifier, focusedScale = 1.06f) { focused ->
        Box(
            Modifier
                .width(150.dp)
                .height(46.dp)
                .background(if (focused) Color.White else Color.White.copy(alpha = 0.14f), shape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                color = if (destructive) Palette.red else if (focused) Palette.ink else Palette.label,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
