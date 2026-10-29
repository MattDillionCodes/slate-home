package com.slatehome.tv.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.slatehome.tv.PhotoUploadServer
import com.slatehome.tv.WallpaperStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_PICKED_BYTES = 40 * 1024 * 1024

/** Reads the whole stream, or returns null if it's bigger than [limit] — no giant allocations. */
private fun java.io.InputStream.readCapped(limit: Int): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val n = read(buffer)
        if (n == -1) return out.toByteArray()
        if (out.size() + n > limit) return null
        out.write(buffer, 0, n)
    }
}

private val ThumbW = 192.dp
private val ThumbH = 108.dp
private val ThumbShape = RoundedCornerShape(18.dp)
private val SidePadding = 56.dp

/** iOS-style wallpaper gallery: the six built-in looks, then the user's own photos. */
@Composable
fun WallpaperPicker(store: WallpaperStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val phoneTile = remember { FocusRequester() }
    var showUpload by remember { mutableStateOf(false) }
    var uploadWasOpen by remember { mutableStateOf(false) }

    // Android's photo picker, for pictures already on the TV or a plugged-in USB drive.
    val canBrowse = remember { PickVisualMedia.isPhotoPickerAvailable(context) }

    // Open the way iOS does: focused on, and scrolled to, the wallpaper in use right now.
    // Plain remember (not the saveable default) so every visit starts from that, not old scroll.
    val openedWith = remember { store.selectedId }
    val styleIndex = remember { WallpaperStyle.entries.indexOfFirst { it.id == openedWith } }
    val photoIndex = remember { store.photos.indexOfFirst { it.id == openedWith } }
    val focusTarget = if (styleIndex >= 0 || photoIndex >= 0) openedWith else WallpaperStyle.Aurora.id
    val collectionsState = remember { LazyListState((styleIndex - 1).coerceAtLeast(0)) }
    val photosState = remember {
        val actionTiles = if (canBrowse) 2 else 1
        LazyListState(if (photoIndex >= 0) (photoIndex + actionTiles - 1).coerceAtLeast(0) else 0)
    }
    val initialFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { initialFocus.requestFocusSafely() }
    LaunchedEffect(showUpload) {
        if (!showUpload && uploadWasOpen) phoneTile.requestFocusSafely()
        uploadWasOpen = showUpload
    }
    BackHandler(enabled = showUpload) { showUpload = false }
    val browse = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readCapped(MAX_PICKED_BYTES) } }.getOrNull()
            }
            if (bytes != null) store.addPhoto(bytes)
        }
    }

    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalHomeInteractive provides !showUpload) {
            Column(Modifier.fillMaxSize().padding(top = 28.dp, bottom = 16.dp)) {
                Column(Modifier.padding(horizontal = SidePadding)) {
                    Text("Wallpaper", color = Palette.label, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Choose a look for your Home Screen  ·  Press Back when you're done",
                        color = Palette.secondary,
                        fontSize = 14.sp,
                    )
                }
                Spacer(Modifier.height(18.dp))

                SectionTitle("Collections")
                LazyRow(
                    state = collectionsState,
                    contentPadding = PaddingValues(horizontal = SidePadding, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    items(WallpaperStyle.entries) { style ->
                        WallpaperThumb(
                            title = style.title,
                            selected = store.selectedId == style.id,
                            onClick = { scope.launch { store.select(style.id) } },
                            image = rememberStyleThumbnail(style),
                            focusRequester = if (style.id == focusTarget) initialFocus else null,
                        )
                    }
                }

                SectionTitle("Your Photos")
                LazyRow(
                    state = photosState,
                    contentPadding = PaddingValues(horizontal = SidePadding, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    item {
                        ActionTile(Icons.Rounded.QrCode2, "Add from Phone", Modifier.focusRequester(phoneTile)) {
                            showUpload = true
                        }
                    }
                    if (canBrowse) {
                        item {
                            ActionTile(Icons.Rounded.PhotoLibrary, "Browse This TV") {
                                browse.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                            }
                        }
                    }
                    items(store.photos, key = { it.id }) { photo ->
                        WallpaperThumb(
                            title = "Photo",
                            selected = store.selectedId == photo.id,
                            onClick = { scope.launch { store.select(photo.id) } },
                            image = photo.thumbnail,
                            focusRequester = if (photo.id == focusTarget) initialFocus else null,
                            onLongClick = { scope.launch { store.remove(photo) } },
                        )
                    }
                }
                if (store.photos.isNotEmpty()) {
                    Text(
                        "Tip: hold OK on one of your photos to remove it",
                        color = Palette.secondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = SidePadding),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showUpload,
            enter = fadeIn(tween(220)) + scaleIn(tween(260), initialScale = 0.94f),
            exit = fadeOut(tween(160)),
        ) {
            PhoneUploadSheet(store)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = Palette.label,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = SidePadding),
    )
}

/** Each style drawn once at thumbnail size, so focus animations don't re-run the artwork. */
@Composable
private fun rememberStyleThumbnail(style: WallpaperStyle): ImageBitmap? {
    val px = with(LocalDensity.current) { IntSize(ThumbW.roundToPx(), ThumbH.roundToPx()) }
    val image by produceState<ImageBitmap?>(null, style, px) {
        value = withContext(Dispatchers.Default) { rasterize(px.width, px.height) { drawStyle(style, size) } }
    }
    return image
}

@Composable
private fun WallpaperThumb(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    image: ImageBitmap?,
    focusRequester: FocusRequester? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(Modifier.width(ThumbW), horizontalAlignment = Alignment.CenterHorizontally) {
        Pressable(
            onClick = onClick,
            shape = ThumbShape,
            modifier = Modifier
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .size(ThumbW, ThumbH),
            focusedScale = 1.08f,
            interactionSource = interaction,
            onLongClick = onLongClick,
        ) { isFocused ->
            Canvas(Modifier.matchParentSize().clip(ThumbShape)) {
                if (image != null) drawCover(image, size) else drawRect(Color.White.copy(alpha = 0.08f))
            }
            Box(
                Modifier
                    .matchParentSize()
                    .border(
                        if (isFocused) 3.dp else 1.dp,
                        Color.White.copy(alpha = if (isFocused) 1f else 0.25f),
                        ThumbShape,
                    )
            )
            if (selected) SelectedBadge(Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            title,
            color = if (focused) Palette.label else Palette.label.copy(alpha = 0.78f),
            fontSize = 13.sp,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

/** iOS's blue checkmark for "this is the one in use". */
@Composable
private fun SelectedBadge(modifier: Modifier) {
    Box(
        modifier
            .size(26.dp)
            .background(Palette.blue, CircleShape)
            .border(2.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Check, "Selected", tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ActionTile(icon: ImageVector, title: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(modifier.width(ThumbW), horizontalAlignment = Alignment.CenterHorizontally) {
        Pressable(onClick, ThumbShape, Modifier.size(ThumbW, ThumbH), 1.08f, interaction) { isFocused ->
            Box(
                Modifier
                    .matchParentSize()
                    .background(if (isFocused) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.12f), ThumbShape)
                    .border(1.dp, Color.White.copy(alpha = 0.3f), ThumbShape)
            )
            Icon(icon, null, tint = if (isFocused) Palette.ink else Color.White, modifier = Modifier.size(38.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            title,
            color = if (focused) Palette.label else Palette.label.copy(alpha = 0.78f),
            fontSize = 13.sp,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Add from Phone
// ---------------------------------------------------------------------------------------------

@Composable
private fun PhoneUploadSheet(store: WallpaperStore) {
    // The server lives exactly as long as this sheet is on screen.
    val server = remember { PhotoUploadServer { bytes -> store.addPhoto(bytes) } }
    LaunchedEffect(server) { server.run() }
    DisposableEffect(server) { onDispose { server.stop() } }
    val url = server.url

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(Modifier.width(680.dp), RoundedCornerShape(36.dp), Color.Black.copy(alpha = 0.30f)) {
            Row(
                Modifier.padding(28.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(212.dp)
                        .background(Color.White, RoundedCornerShape(22.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (url != null) {
                        QrCode(url, Modifier.fillMaxSize())
                    } else {
                        Text(
                            if (server.offline) "Connect the TV to Wi-Fi first" else "Starting…",
                            color = Palette.ink,
                            fontSize = 14.sp,
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text("Add from Your Phone", color = Palette.label, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(14.dp))
                    Step(1, "Scan the code with your phone's camera")
                    Step(2, "Tap Choose Photos and pick a few")
                    Step(3, "The newest one becomes your wallpaper")
                    Spacer(Modifier.height(12.dp))
                    if (url != null) {
                        Text(
                            "or open  $url",
                            color = Palette.secondary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    UploadStatus(server.received)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Phone and TV need to be on the same Wi-Fi  ·  Press Back when you're done",
                        color = Palette.secondary,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(22.dp).background(Color.White.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", color = Palette.label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, color = Palette.label, fontSize = 14.sp)
    }
}

/** Deliberately static: an endless pulse would keep the whole screen redrawing while you wait. */
@Composable
private fun UploadStatus(received: Int) {
    Row(
        Modifier
            .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (received == 0) {
            Box(Modifier.size(8.dp).background(Palette.orange, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text("Waiting for photos…", color = Palette.label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        } else {
            Icon(Icons.Rounded.Check, null, tint = Color(0xFF30D158), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (received == 1) "1 photo added" else "$received photos added",
                color = Palette.label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun QrCode(text: String, modifier: Modifier = Modifier) {
    // Width/height 0 asks zxing for the minimal matrix: one cell per QR module.
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    }
    Canvas(modifier) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) {
                    // +0.5px overlap hides hairline seams between neighbouring cells.
                    drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}
