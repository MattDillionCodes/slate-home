package com.slatehome.tv.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.unit.toSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.slatehome.tv.AppEntry
import com.slatehome.tv.AppRepository
import com.slatehome.tv.Catalog
import com.slatehome.tv.HomeLayout
import com.slatehome.tv.WallpaperStore
import com.slatehome.tv.WeatherRepository
import com.slatehome.tv.WeatherState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private enum class Overlay { None, ControlCenter, Library, Wallpaper, AddApp, RemoveApp, ResetHome, StandBy }

private val TimeFormat = DateTimeFormatter.ofPattern("h:mm")
private val DateFormat = DateTimeFormatter.ofPattern("EEE MMM d")

@Composable
fun HomeApp(
    repo: AppRepository,
    weather: WeatherRepository,
    wallpapers: WallpaperStore,
    layout: HomeLayout,
    resumeTick: Int,
    homeTick: Int,
) {
    val context = LocalContext.current
    var overlay by remember { mutableStateOf(Overlay.None) }
    var rootSize by remember { mutableStateOf(Size.Zero) }
    // Where focus should land when an overlay closes — the thing that opened it.
    var restoreTo by remember { mutableStateOf<FocusRequester?>(null) }

    val gridFirst = remember { FocusRequester() }
    val ccButton = remember { FocusRequester() }
    val libraryButton = remember { FocusRequester() }

    val edit = remember { EditState() }
    val idle = remember { IdleClock() }
    val canSearch = remember { context.canSearch() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Only installed apps ever appear on the home screen, and by default only ones you'd want
    // there — not preinstalled utilities or other launchers (those live in the App Library).
    val dockApps = layout.visibleDock(repo::isInstalled).mapNotNull(repo::entryFor)
    val shownDock = dockApps.map { it.pkg }
    val gridApps = layout.visibleGrid(
        isInstalled = repo::isInstalled,
        candidates = repo.installed.filter(repo::belongsOnHome).map { it.pkg }.filter { it !in Catalog.dock },
    )
    val firstFocus = if (gridApps.isNotEmpty()) gridFirst else libraryButton

    val launch: (AppEntry) -> Unit = { app ->
        overlay = Overlay.None
        edit.done()
        repo.launch(context, app)
    }

    LaunchedEffect(resumeTick) {
        idle.touch()
        repo.refresh()
    }
    // A launcher can sit on screen for hours, so refresh on a timer too, not just on resume.
    LaunchedEffect(resumeTick) {
        while (true) {
            weather.refreshIfStale()
            delay(WeatherRepository.REFRESH_INTERVAL_MS)
        }
    }
    LaunchedEffect(Unit) { wallpapers.load() }
    // The grid is empty until the first app scan lands, so focus it then — or the dock if it's empty.
    LaunchedEffect(repo.loaded) {
        if (repo.loaded) firstFocus.requestFocusSafely()
    }
    LaunchedEffect(homeTick) {
        if (homeTick > 0) {
            overlay = Overlay.None
            edit.done()
            firstFocus.requestFocusSafely()
        }
    }
    LaunchedEffect(overlay) {
        if (overlay == Overlay.None) restoreTo?.requestFocusSafely()
    }
    // Focus follows a lifted app as it moves — including between the grid and the dock.
    LaunchedEffect(edit.lifted, gridApps, shownDock) {
        edit.lifted?.let { edit.focusFor(it).requestFocusSafely() }
    }
    // StandBy: after a while with no remote presses, but only while we're actually on screen.
    val latestFirstFocus by rememberUpdatedState(firstFocus) // this loop outlives many recompositions
    LaunchedEffect(Unit) {
        val after = standByDelayMs(context)
        while (true) {
            delay(5_000)
            val onScreen = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (onScreen && overlay == Overlay.None && !edit.editing && idle.quietFor() > after) {
                restoreTo = latestFirstFocus
                overlay = Overlay.StandBy
            }
        }
    }

    // Back unwinds one step at a time; at the bottom we're a launcher, so it has nowhere to go.
    BackHandler {
        when {
            overlay != Overlay.None -> overlay = Overlay.None
            edit.lifted != null -> edit.lifted = null
            edit.editing -> edit.done()
        }
    }

    // Draw the wallpaper (and its frosted twin) into pixels once, whenever it or the screen changes.
    val backdrop = wallpapers.backdrop
    val raster by produceState<WallpaperRaster?>(null, backdrop, rootSize) {
        val w = rootSize.width.roundToInt()
        val h = rootSize.height.roundToInt()
        if (w > 0 && h > 0) value = withContext(Dispatchers.Default) { renderWallpaper(backdrop, w, h) }
    }

    // iPad blurs the Home Screen behind panels. A live blur re-runs on every animation frame,
    // so instead we snapshot the home once as a panel opens and frost the snapshot.
    val homeLayer = rememberGraphicsLayer()
    var frostedHome by remember { mutableStateOf<ImageBitmap?>(null) }
    var lastOverlay by remember { mutableStateOf(Overlay.None) }
    LaunchedEffect(overlay) { if (overlay != Overlay.None) lastOverlay = overlay }
    LaunchedEffect(overlay != Overlay.None) {
        if (overlay != Overlay.None) {
            frostedHome = null
            val snapshot = homeLayer.toImageBitmap()
            frostedHome = withContext(Dispatchers.Default) { frost(snapshot) }
        }
    }
    // The picker and StandBy sit on the frosted wallpaper alone: the picker so a new pick shows
    // up behind it, StandBy because a clock over blurry app icons looks busy.
    val wallpaperOnly = lastOverlay == Overlay.Wallpaper || lastOverlay == Overlay.StandBy
    val frostImage = if (wallpaperOnly) raster?.frosted else frostedHome
    val frostAlpha by animateFloatAsState(
        if (overlay != Overlay.None && frostImage != null) 1f else 0f,
        tween(if (lastOverlay == Overlay.StandBy) 700 else 320),
        label = "frost",
    )
    val scrim by animateFloatAsState(
        when (overlay) {
            Overlay.None -> 0f
            Overlay.StandBy -> 0.55f
            Overlay.Wallpaper -> 0.30f
            else -> 0.22f
        },
        tween(if (overlay == Overlay.StandBy) 700 else 320),
        label = "scrim",
    )

    CompositionLocalProvider(
        LocalBackdropSize provides rootSize,
        LocalNow provides rememberNow(),
        LocalWallpaper provides backdrop,
        LocalWallpaperRaster provides raster,
    ) {
        // No background here: the opaque wallpaper covers every pixel, and each full-screen
        // fill is a few milliseconds of GPU time on a TV.
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { rootSize = it.toSize() }
                .onPreviewKeyEvent { event ->
                    idle.touch()
                    when {
                        // Any button wakes StandBy; the whole press is swallowed so it does nothing else.
                        overlay == Overlay.StandBy -> {
                            if (event.type == KeyEventType.KeyUp) overlay = Overlay.None
                            true
                        }
                        edit.lifted != null && overlay == Overlay.None ->
                            moveLifted(event, edit, layout, gridApps, shownDock)
                        // Remotes with a ☰ Menu key get a shortcut straight to Control Center.
                        event.type == KeyEventType.KeyDown && event.key == Key.Menu -> {
                            if (overlay == Overlay.ControlCenter) {
                                overlay = Overlay.None
                            } else {
                                restoreTo = restoreTo ?: firstFocus
                                overlay = Overlay.ControlCenter
                            }
                            true
                        }
                        else -> false
                    }
                }
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        homeLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(homeLayer)
                    }
            ) {
                Wallpaper()
                CompositionLocalProvider(LocalHomeInteractive provides (overlay == Overlay.None)) {
                    HomeScreen(
                        repo = repo,
                        weather = weather.state,
                        edit = edit,
                        gridApps = gridApps,
                        dockApps = dockApps,
                        gridFirst = gridFirst,
                        ccButton = ccButton,
                        libraryButton = libraryButton,
                        onLaunch = launch,
                        onOpenControlCenter = {
                            restoreTo = ccButton
                            overlay = Overlay.ControlCenter
                        },
                        onOpenLibrary = {
                            restoreTo = libraryButton
                            overlay = Overlay.Library
                        },
                        onAddApp = {
                            restoreTo = edit.focusFor(EditState.ADD_TILE)
                            overlay = Overlay.AddApp
                        },
                        onRemoveApp = { app ->
                            edit.removing = app
                            restoreTo = edit.focusFor(app.pkg)
                            overlay = Overlay.RemoveApp
                        },
                        onReset = {
                            restoreTo = ccButton
                            overlay = Overlay.ResetHome
                        },
                        onSearch = if (canSearch) ({ context.openSearch() }) else null,
                    )
                }
            }
            // Only exist while visible: an invisible full-screen layer still costs GPU fill.
            if (frostImage != null && frostAlpha > 0f) {
                Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = frostAlpha }) {
                    drawImage(frostImage, dstSize = size.toIntSize(), filterQuality = FilterQuality.Low)
                }
            }
            if (scrim > 0f) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim)))
            }

            AnimatedVisibility(
                visible = overlay == Overlay.ControlCenter,
                modifier = Modifier.align(Alignment.TopEnd),
                enter = fadeIn(tween(220)) + scaleIn(
                    spring(dampingRatio = 0.72f, stiffness = 380f),
                    initialScale = 0.86f,
                    transformOrigin = TransformOrigin(1f, 0f),
                ),
                exit = fadeOut(tween(160)) + scaleOut(tween(180), targetScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f)),
            ) {
                ControlCenter(
                    onDone = { overlay = Overlay.None },
                    onOpenWallpaper = { overlay = Overlay.Wallpaper },
                    onStandBy = { overlay = Overlay.StandBy },
                )
            }

            AnimatedVisibility(
                visible = overlay == Overlay.Wallpaper,
                enter = fadeIn(tween(240)) + scaleIn(tween(300), initialScale = 1.04f),
                exit = fadeOut(tween(180)),
            ) {
                WallpaperPicker(wallpapers)
            }

            AnimatedVisibility(
                visible = overlay == Overlay.Library,
                enter = fadeIn(tween(240)) + scaleIn(tween(300), initialScale = 1.06f),
                exit = fadeOut(tween(180)) + scaleOut(tween(200), targetScale = 1.04f),
            ) {
                AppLibrary(apps = repo.installed, onSelect = launch)
            }

            AnimatedVisibility(
                visible = overlay == Overlay.AddApp,
                enter = fadeIn(tween(240)) + scaleIn(tween(300), initialScale = 1.06f),
                exit = fadeOut(tween(180)) + scaleOut(tween(200), targetScale = 1.04f),
            ) {
                AppLibrary(
                    apps = repo.installed.filter { it.pkg !in gridApps && it.pkg !in shownDock },
                    onSelect = { app ->
                        layout.add(app.pkg, gridApps, shownDock)
                        restoreTo = edit.focusFor(app.pkg)
                        overlay = Overlay.None
                    },
                    title = "Add to Home Screen",
                    subtitle = "Choose an app  ·  Press Back to cancel",
                    emptyMessage = "Every app on this TV is already on your Home Screen.",
                )
            }

            AnimatedVisibility(
                visible = overlay == Overlay.RemoveApp,
                enter = fadeIn(tween(200)) + scaleIn(spring(dampingRatio = 0.7f, stiffness = 420f), initialScale = 0.9f),
                exit = fadeOut(tween(150)),
            ) {
                edit.removing?.let { app ->
                    RemoveAppDialog(
                        app = app,
                        onRemove = {
                            val inDock = app.pkg in shownDock
                            val row = if (inDock) shownDock else gridApps
                            val next = row.getOrNull(row.indexOf(app.pkg) + 1)
                            layout.remove(app.pkg, gridApps, shownDock)
                            // Focus whatever slides into its place — or the "+" tile / App Library.
                            restoreTo = when {
                                next != null -> edit.focusFor(next)
                                inDock -> libraryButton
                                else -> edit.focusFor(EditState.ADD_TILE)
                            }
                            overlay = Overlay.None
                        },
                        onCancel = { overlay = Overlay.None },
                    )
                }
            }

            AnimatedVisibility(
                visible = overlay == Overlay.ResetHome,
                enter = fadeIn(tween(200)) + scaleIn(spring(dampingRatio = 0.7f, stiffness = 420f), initialScale = 0.9f),
                exit = fadeOut(tween(150)),
            ) {
                ConfirmDialog(
                    title = "Reset Home Screen?",
                    message = "Your grid and dock go back to the apps you have installed.",
                    confirmLabel = "Reset",
                    onConfirm = {
                        layout.reset()
                        edit.done()
                        // The default grid may well be empty, so land on the top-right button.
                        restoreTo = ccButton
                        overlay = Overlay.None
                    },
                    onCancel = { overlay = Overlay.None },
                )
            }

            AnimatedVisibility(
                visible = overlay == Overlay.StandBy,
                enter = fadeIn(tween(700)),
                exit = fadeOut(tween(300)),
            ) {
                StandBy(weather.state)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    repo: AppRepository,
    weather: WeatherState,
    edit: EditState,
    gridApps: List<String>,
    dockApps: List<AppEntry>,
    gridFirst: FocusRequester,
    ccButton: FocusRequester,
    libraryButton: FocusRequester,
    onLaunch: (AppEntry) -> Unit,
    onOpenControlCenter: () -> Unit,
    onOpenLibrary: () -> Unit,
    onAddApp: () -> Unit,
    onRemoveApp: (AppEntry) -> Unit,
    onReset: () -> Unit,
    onSearch: (() -> Unit)?,
) {
    val dockPackages = dockApps.map { it.pkg }
    val recents = repo.recents.filter { it !in dockPackages }.mapNotNull(repo::entryFor).take(2)
    // One shared jiggle for grid and dock, only while editing and nothing covers the screen.
    val wiggle = if (edit.editing && LocalHomeInteractive.current) rememberWiggle() else null
    val lifted = edit.lifted

    // 960×540dp canvas; 48/22dp keeps everything inside the TV's overscan-safe area.
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 22.dp)) {
        StatusBar(
            ccButton = ccButton,
            edit = edit,
            liftedLabel = lifted?.let { repo.entryFor(it)?.label },
            liftedInDock = lifted != null && lifted in dockPackages,
            onOpenControlCenter = onOpenControlCenter,
            onReset = onReset,
            onSearch = onSearch,
        )
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(296.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ClockWidget(Modifier.size(140.dp))
                    CalendarWidget(Modifier.size(140.dp))
                }
                WeatherWidget(weather, Modifier.size(296.dp, 166.dp))
            }
            Spacer(Modifier.width(36.dp))
            AppGrid(repo, gridApps, edit, wiggle, gridFirst, onLaunch, onAddApp, onRemoveApp, Modifier.weight(1f))
        }
        Spacer(Modifier.weight(1f))
        Dock(
            favorites = dockApps,
            recents = recents,
            libraryPreviews = repo.installed.filter(repo::belongsOnHome).take(4),
            edit = edit,
            wiggle = wiggle,
            libraryButton = libraryButton,
            onLaunch = onLaunch,
            onOpenLibrary = onOpenLibrary,
            onRemoveApp = onRemoveApp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun StatusBar(
    ccButton: FocusRequester,
    edit: EditState,
    liftedLabel: String?,
    liftedInDock: Boolean,
    onOpenControlCenter: () -> Unit,
    onReset: () -> Unit,
    onSearch: (() -> Unit)?,
) {
    val now = LocalNow.current.value
    val text = TextStyle(shadow = LabelShadow)
    Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        // Cached with room for the blurred shadow, and nudged left so the text still lines up.
        Row(
            Modifier.offset(x = (-6).dp).cached().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(now.format(TimeFormat), color = Palette.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, style = text)
            Spacer(Modifier.width(8.dp))
            Text(now.format(DateFormat), color = Palette.label, fontSize = 17.sp, fontWeight = FontWeight.Medium, style = text)
        }
        Spacer(Modifier.weight(1f))
        if (edit.editing) {
            EditHint(liftedLabel, liftedInDock)
            Spacer(Modifier.weight(1f))
        } else if (onSearch != null) {
            // iOS's Search pill: Google TV's own search, the same as the remote's mic button.
            StatusPill("Search", Icons.Rounded.Search, onSearch)
            Spacer(Modifier.width(16.dp))
        }
        Icon(rememberNetworkIcon(), "Network", tint = Palette.label, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        // Like iPad's jiggle mode, the top-right button becomes "Done" while editing.
        if (edit.editing) {
            StatusPill("Reset", null, onReset)
            Spacer(Modifier.width(10.dp))
            StatusPill("Done", null, edit::done, Modifier.focusRequester(ccButton))
        } else {
            StatusPill("Control Center", Icons.Rounded.Tune, onOpenControlCenter, Modifier.focusRequester(ccButton))
        }
    }
}

private val PillShape = RoundedCornerShape(17.dp)

@Composable
private fun StatusPill(label: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Pressable(onClick = onClick, shape = PillShape, modifier = modifier, focusedScale = 1.08f) { focused ->
        Row(
            Modifier
                .background(if (focused) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.16f), PillShape)
                .border(1.dp, Color.White.copy(alpha = 0.3f), PillShape)
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val ink = if (focused) Palette.ink else Palette.label
            if (icon != null) {
                Icon(icon, null, tint = ink, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(7.dp))
            }
            Text(label, color = ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun rememberNetworkIcon(): ImageVector {
    val context = LocalContext.current
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
    return when {
        caps == null -> Icons.Rounded.WifiOff
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Icons.Rounded.Lan
        else -> Icons.Rounded.Wifi
    }
}

private val SearchActions = listOf("android.search.action.GLOBAL_SEARCH", Intent.ACTION_ASSIST)

/** Whether this TV has a search app at all — bare Android TV images (like the emulator) don't. */
private fun Context.canSearch(): Boolean =
    SearchActions.any { packageManager.queryIntentActivities(Intent(it), 0).isNotEmpty() }

/** Google TV's search screen; falls back to the Assistant on TVs without a global search. */
private fun Context.openSearch() {
    for (action in SearchActions) {
        try {
            startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: ActivityNotFoundException) {
            // try the next one
        }
    }
}

/** Neighbours rock in opposite directions, like iOS; the lifted app holds still. */
private fun rock(wiggle: State<Float>?, index: Int, pkg: String, edit: EditState): (() -> Float)? =
    wiggle?.let { w -> { if (edit.lifted == pkg) 0f else if (index % 2 == 0) w.value else -w.value } }

// A lazy grid clips to its bounds, so it gets headroom for lifted/focused icons and is shifted
// up by the same amount, keeping the icons where the old fixed grid had them.
private val GridHeadroom = 16.dp
private val GridHeight = 340.dp + GridHeadroom // 3 rows of ~100dp + 2 gaps

/**
 * The user's own apps, in their order. A lazy grid (rather than rows of tiles) means each app
 * keeps its identity when it moves, so focus stays on it and the others slide over with
 * animateItem() — iPad's reflow.
 */
@Composable
private fun AppGrid(
    repo: AppRepository,
    apps: List<String>,
    edit: EditState,
    wiggle: State<Float>?,
    firstFocus: FocusRequester,
    onLaunch: (AppEntry) -> Unit,
    onAddApp: () -> Unit,
    onRemoveApp: (AppEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(HomeLayout.COLUMNS),
        modifier = modifier
            .offset(y = -GridHeadroom)
            .height(GridHeight),
        contentPadding = PaddingValues(top = GridHeadroom),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        userScrollEnabled = false,
    ) {
        itemsIndexed(apps, key = { _, pkg -> pkg }) { index, pkg ->
            val app = repo.entryFor(pkg) ?: AppEntry(pkg, "")
            AppTile(
                app = app,
                onClick = { if (edit.editing) edit.lifted = pkg else onLaunch(app) },
                onLongClick = { if (edit.editing) onRemoveApp(app) else edit.editing = true },
                modifier = Modifier
                    .animateItem()
                    .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                focusRequester = edit.focusFor(pkg),
                wiggle = rock(wiggle, index, pkg, edit),
                lifted = edit.lifted == pkg,
            )
        }
        // Shown while editing — and always on an empty grid, which has no app to hold OK on.
        if ((edit.editing || apps.isEmpty()) && apps.size < HomeLayout.CAPACITY) {
            item(key = EditState.ADD_TILE) {
                AddAppTile(onAddApp, edit.focusFor(EditState.ADD_TILE), Modifier.animateItem())
            }
        }
    }
}

/** While an app is lifted, the arrows move it and OK drops it — instead of moving focus. */
private fun moveLifted(
    event: KeyEvent,
    edit: EditState,
    layout: HomeLayout,
    shownGrid: List<String>,
    shownDock: List<String>,
): Boolean {
    val pkg = edit.lifted ?: return false
    val (dx, dy) = when (event.key) {
        Key.DirectionLeft -> -1 to 0
        Key.DirectionRight -> 1 to 0
        Key.DirectionUp -> 0 to -1
        Key.DirectionDown -> 0 to 1
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
            if (event.type == KeyEventType.KeyUp) edit.lifted = null
            return true
        }
        else -> return false
    }
    if (event.type == KeyEventType.KeyDown) layout.move(pkg, dx, dy, shownGrid, shownDock)
    return true // swallow arrow ups too, so focus never wanders off mid-move
}

@OptIn(ExperimentalComposeUiApi::class) // FocusRequester.Cancel
@Composable
private fun Dock(
    favorites: List<AppEntry>,
    recents: List<AppEntry>,
    libraryPreviews: List<AppEntry>,
    edit: EditState,
    wiggle: State<Float>?,
    libraryButton: FocusRequester,
    onLaunch: (AppEntry) -> Unit,
    onOpenLibrary: () -> Unit,
    onRemoveApp: (AppEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(modifier, RoundedCornerShape(30.dp), Color.White.copy(alpha = 0.12f)) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The dock's ends are walls: without them, 2D focus search jumps diagonally up into
            // the grid when you press past the last icon, which feels like focus "escaping".
            favorites.forEachIndexed { index, app ->
                // Favourites are part of the editable layout: lift, move, remove — like the grid.
                AppTile(
                    app = app,
                    onClick = { if (edit.editing) edit.lifted = app.pkg else onLaunch(app) },
                    onLongClick = { if (edit.editing) onRemoveApp(app) else edit.editing = true },
                    modifier = if (index == 0) Modifier.focusProperties { left = FocusRequester.Cancel } else Modifier,
                    iconSize = DockIconSize,
                    labelMode = LabelMode.Tooltip,
                    focusedScale = 1.18f,
                    focusRequester = edit.focusFor(app.pkg),
                    wiggle = rock(wiggle, index, app.pkg, edit),
                    lifted = edit.lifted == app.pkg,
                )
            }
            // Like iPad: a hairline divider, then recently used apps that aren't pinned.
            if (recents.isNotEmpty()) {
                if (favorites.isNotEmpty()) DockDivider()
                recents.forEach { app ->
                    AppTile(
                        app = app,
                        onClick = { onLaunch(app) },
                        iconSize = DockIconSize,
                        labelMode = LabelMode.Tooltip,
                        focusedScale = 1.18f,
                    )
                }
            }
            if (favorites.isNotEmpty() || recents.isNotEmpty()) DockDivider()
            AppLibraryTile(
                onOpenLibrary,
                libraryPreviews,
                Modifier
                    .focusRequester(libraryButton)
                    .focusProperties { right = FocusRequester.Cancel },
            )
        }
    }
}

private val DockIconSize = 56.dp

@Composable
private fun DockDivider() {
    Box(Modifier.width(1.dp).height(42.dp).background(Color.White.copy(alpha = 0.28f)))
}
