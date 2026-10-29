package com.slatehome.tv.ui

import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Input
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val ModuleShape = RoundedCornerShape(24.dp)
private val ModuleFill = Color.White.copy(alpha = 0.10f)
private val TileShape = RoundedCornerShape(22.dp)
private const val DEFAULT_HINT = "Press Back to close"
private val HeaderFormat = DateTimeFormatter.ofPattern("h:mm · EEE MMM d")

/** iPadOS-style Control Center: a glass sheet of modules that drops in from the top-right. */
@Composable
fun ControlCenter(onDone: () -> Unit, onOpenWallpaper: () -> Unit, onStandBy: () -> Unit) {
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    val volume = rememberVolumeState()
    var hint by remember { mutableStateOf(DEFAULT_HINT) }
    val online = remember { context.isOnline() }
    val bluetoothOn = remember { context.isBluetoothOn() }

    LaunchedEffect(Unit) { first.requestFocusSafely() }

    fun open(action: String) {
        context.openSettings(action)
        onDone()
    }

    GlassSurface(
        Modifier.padding(top = 20.dp, end = 40.dp).width(440.dp),
        RoundedCornerShape(36.dp),
        Color.Black.copy(alpha = 0.20f),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Control Center", color = Palette.label, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(LocalNow.current.value.format(HeaderFormat), color = Palette.secondary, fontSize = 13.sp)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                // Connectivity module — the 2×2 of round buttons in iPad's top-left corner.
                Column(
                    Modifier
                        .size(200.dp)
                        .background(ModuleFill, ModuleShape)
                        .padding(horizontal = 12.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        CircleToggle(Icons.Rounded.Wifi, "Wi-Fi", online, Modifier.focusRequester(first)) {
                            open(Settings.ACTION_WIFI_SETTINGS)
                        }
                        CircleToggle(Icons.Rounded.Bluetooth, "Bluetooth", bluetoothOn) {
                            open(Settings.ACTION_BLUETOOTH_SETTINGS)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        CircleToggle(Icons.Rounded.Tv, "Display", false) {
                            open(Settings.ACTION_DISPLAY_SETTINGS)
                        }
                        CircleToggle(Icons.Rounded.Accessibility, "Accessibility", false) {
                            open(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        }
                    }
                }

                VolumeSlider(volume, Modifier.size(84.dp, 200.dp)) { focused ->
                    hint = if (focused) "Press ▲ ▼ on your remote to change the volume" else DEFAULT_HINT
                }

                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SquareTile(
                        icon = if (volume.muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                        label = if (volume.muted) "Unmute" else "Mute",
                        active = volume.muted,
                        modifier = Modifier.size(84.dp, 93.dp),
                    ) { volume.toggleMute() }
                    SquareTile(Icons.Rounded.Bedtime, "StandBy", false, Modifier.size(84.dp, 93.dp)) {
                        onStandBy()
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ModuleTile(Icons.Rounded.Input, "Inputs", Modifier.weight(1f)) {
                    context.openInputs()
                    onDone()
                }
                ModuleTile(Icons.Rounded.Wallpaper, "Wallpaper", Modifier.weight(1f)) {
                    onOpenWallpaper()
                }
                ModuleTile(Icons.Rounded.Settings, "All Settings", Modifier.weight(1f)) {
                    open(Settings.ACTION_SETTINGS)
                }
            }

            Text(
                hint,
                color = Palette.secondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CircleToggle(
    icon: ImageVector,
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(modifier.width(80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Pressable(onClick, CircleShape, Modifier.size(56.dp), 1.12f, interaction) { isFocused ->
            Box(
                Modifier
                    .matchParentSize()
                    .background(if (active) Palette.blue else Color.White.copy(alpha = 0.18f), CircleShape)
                    .border(2.dp, if (isFocused) Color.White else Color.Transparent, CircleShape)
            )
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(7.dp))
        Text(
            label,
            color = if (focused) Palette.label else Palette.secondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun SquareTile(
    icon: ImageVector,
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Pressable(onClick, TileShape, modifier, 1.08f) { focused ->
        val ink = if (active) Palette.ink else Color.White
        Box(
            Modifier
                .matchParentSize()
                .background(if (active) Color.White else ModuleFill, TileShape)
                .border(2.dp, if (focused) Color.White else Color.Transparent, TileShape)
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = ink, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, color = ink, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

/** A compact module: icon in a circle over its label, three to a row. */
@Composable
private fun ModuleTile(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Pressable(onClick, TileShape, modifier.height(76.dp), 1.06f) { focused ->
        Box(
            Modifier
                .matchParentSize()
                .background(ModuleFill, TileShape)
                .border(2.dp, if (focused) Color.White else Color.Transparent, TileShape)
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(34.dp).background(Color.White.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text(label, color = Palette.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Volume
// ---------------------------------------------------------------------------------------------

/** Media volume, read from and written straight to the TV's AudioManager. */
private class VolumeState(private val audio: AudioManager) {
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    var level by mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        private set
    private var beforeMute = max / 2

    val muted get() = level == 0

    fun set(value: Int) {
        level = value.coerceIn(0, max)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0) }
    }

    fun toggleMute() {
        if (level > 0) {
            beforeMute = level
            set(0)
        } else {
            set(beforeMute.coerceAtLeast(1))
        }
    }
}

@Composable
private fun rememberVolumeState(): VolumeState {
    val context = LocalContext.current
    return remember { VolumeState(context.getSystemService(AudioManager::class.java)) }
}

/** iPad's tall pill slider. Up/Down adjust while focused; Left/Right move on as usual. */
@Composable
private fun VolumeSlider(volume: VolumeState, modifier: Modifier, onFocusChange: (Boolean) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { onFocusChange(focused) }

    val scale by animateFloatAsState(if (focused) 1.06f else 1f, spring(0.6f, 420f), label = "volumeScale")
    val fill by animateFloatAsState(volume.level / volume.max.toFloat(), spring(stiffness = 600f), label = "volumeFill")
    val shape = RoundedCornerShape(26.dp)

    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> { volume.set(volume.level + 1); true }
                    Key.DirectionDown -> { volume.set(volume.level - 1); true }
                    else -> false
                }
            }
            .focusable(interactionSource = interaction)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.14f))
            .border(2.dp, if (focused) Color.White else Color.Transparent, shape)
    ) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(fill)
                .background(Color.White)
        )
        Text(
            "${(fill * 100).roundToInt()}%",
            color = if (fill > 0.88f) Palette.ink else Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp),
        )
        Icon(
            when {
                volume.muted -> Icons.AutoMirrored.Rounded.VolumeOff
                fill < 0.5f -> Icons.AutoMirrored.Rounded.VolumeDown
                else -> Icons.AutoMirrored.Rounded.VolumeUp
            },
            "Volume",
            tint = if (fill > 0.16f) Palette.ink else Color.White,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).size(26.dp),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// System helpers
// ---------------------------------------------------------------------------------------------

/** Opens a Settings screen, falling back to the main page on TVs that don't expose that one. */
private fun Context.openSettings(action: String) {
    val flags = Intent.FLAG_ACTIVITY_NEW_TASK
    try {
        startActivity(Intent(action).addFlags(flags))
    } catch (e: ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(flags))
    }
}

/**
 * The TV's own inputs picker (HDMI, AV, antenna) — the same panel as the remote's Input button.
 * Some TVs (TCL among them) don't publish their inputs to apps, but they all answer this.
 */
private fun Context.openInputs() {
    try {
        startActivity(Intent("com.android.tv.action.VIEW_INPUTS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        openSettings(Settings.ACTION_SETTINGS)
    }
}

private fun Context.isOnline(): Boolean {
    val cm = getSystemService(ConnectivityManager::class.java) ?: return false
    return cm.getNetworkCapabilities(cm.activeNetwork) != null
}

private fun Context.isBluetoothOn(): Boolean =
    runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true }
        .getOrDefault(false)
