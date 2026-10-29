package com.slatehome.tv

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Which apps sit on the home-screen grid and in the dock, in what order. Only installed apps are
 * ever shown.
 *
 * Until the user edits anything, both follow defaults built from what's installed, so new apps
 * just appear. The first edit freezes exactly what's on screen into a saved arrangement.
 */
class HomeLayout(private val store: KeyValueStore) {

    /** The saved arrangements; only used once [customized]. */
    val grid = mutableStateListOf<String>()
    val dock = mutableStateListOf<String>()

    var customized by mutableStateOf(false)
        private set

    init {
        val savedGrid = read(KEY_GRID)
        if (savedGrid == null || savedGrid == LEGACY_DEFAULT) {
            // Earlier builds saved the stock catalog grid even when nobody had edited it.
            clearSaved()
        } else {
            grid.addAll(savedGrid)
            dock.addAll(read(KEY_DOCK) ?: Catalog.dock)
            customized = true
        }
    }

    fun visibleDock(isInstalled: (String) -> Boolean): List<String> =
        (if (customized) dock else Catalog.dock).filter(isInstalled)

    /**
     * The grid: the saved arrangement minus anything since uninstalled — or, until the first
     * edit, the A→Z [candidates] with well-known services moved to the front.
     */
    fun visibleGrid(isInstalled: (String) -> Boolean, candidates: List<String>): List<String> {
        if (customized) return grid.filter(isInstalled)
        val services = Catalog.services.filter { it in candidates }
        return (services + (candidates - services.toSet())).take(CAPACITY)
    }

    /**
     * One remote step for the lifted app. In the grid, left/right walk the reading order (like
     * iPad's reflow), up/down jump a row, and down past the last row drops it into the dock.
     * In the dock, left/right reorder and up sends it back to the end of the grid.
     * Returns false when it can't go that way.
     */
    fun move(pkg: String, dx: Int, dy: Int, shownGrid: List<String>, shownDock: List<String>): Boolean {
        val inGrid = shownGrid.indexOf(pkg)
        val inDock = shownDock.indexOf(pkg)
        val lastRow = (shownGrid.size - 1) / COLUMNS
        val gridTo = (inGrid + dx + dy * COLUMNS).let {
            // ▼ from a row above a short last row lands at its end; only the last row drops into the dock.
            if (dy > 0 && it >= shownGrid.size && inGrid >= 0 && inGrid / COLUMNS < lastRow) shownGrid.size - 1 else it
        }
        val dockTo = inDock + dx
        val step = when {
            inGrid >= 0 && gridTo in shownGrid.indices && gridTo != inGrid -> Step.WithinGrid
            inGrid >= 0 && dy > 0 && gridTo >= shownGrid.size && shownDock.size < DOCK_CAPACITY -> Step.IntoDock
            inDock >= 0 && dy == 0 && dockTo in shownDock.indices && dockTo != inDock -> Step.WithinDock
            inDock >= 0 && dy < 0 && shownGrid.size < CAPACITY -> Step.IntoGrid
            else -> return false
        }
        adopt(shownGrid, shownDock)
        when (step) {
            Step.WithinGrid -> grid.add(gridTo, grid.removeAt(inGrid))
            Step.IntoDock -> {
                grid.removeAt(inGrid)
                dock.add(pkg)
            }
            Step.WithinDock -> dock.add(dockTo, dock.removeAt(inDock))
            Step.IntoGrid -> {
                dock.removeAt(inDock)
                grid.add(pkg)
            }
        }
        save()
        return true
    }

    fun add(pkg: String, shownGrid: List<String>, shownDock: List<String>): Boolean {
        if (shownGrid.size >= CAPACITY || pkg in shownGrid || pkg in shownDock) return false
        adopt(shownGrid, shownDock)
        grid.add(pkg)
        save()
        return true
    }

    fun remove(pkg: String, shownGrid: List<String>, shownDock: List<String>) {
        adopt(shownGrid, shownDock)
        if (grid.remove(pkg) || dock.remove(pkg)) save()
    }

    /** Back to the defaults: grid and dock follow what's installed again. */
    fun reset() {
        clearSaved()
        grid.clear()
        dock.clear()
        customized = false
    }

    /** Every edit starts from exactly what's on screen, so indices always match what you see. */
    private fun adopt(shownGrid: List<String>, shownDock: List<String>) {
        if (!customized || grid.toList() != shownGrid || dock.toList() != shownDock) {
            grid.clear()
            grid.addAll(shownGrid)
            dock.clear()
            dock.addAll(shownDock)
            customized = true
        }
    }

    private fun read(key: String): List<String>? =
        store.get(key)?.split(',')?.filter { it.isNotBlank() }

    private fun save() {
        store.put(mapOf(KEY_GRID to grid.joinToString(","), KEY_DOCK to dock.joinToString(",")))
    }

    private fun clearSaved() {
        store.remove(KEY_GRID, KEY_DOCK)
    }

    private enum class Step { WithinGrid, IntoDock, WithinDock, IntoGrid }

    companion object {
        const val COLUMNS = 4
        /** One iPad-style page: 4 × 3, which is what fits beside the widgets. */
        const val CAPACITY = 12
        /** Favourites in the dock, before the recents divider and the App Library. */
        const val DOCK_CAPACITY = 6
        private const val KEY_GRID = "grid"
        private const val KEY_DOCK = "dock"
        private val LEGACY_DEFAULT = Catalog.services
    }
}

/** Where [HomeLayout] keeps its arrangement — an interface so the logic is unit-testable. */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(entries: Map<String, String>)
    fun remove(vararg keys: String)
}

/** The app's real store: the same "home" SharedPreferences the rest of the app uses. */
class PrefsStore(context: Context) : KeyValueStore {
    private val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(entries: Map<String, String>) {
        prefs.edit().apply { entries.forEach { (k, v) -> putString(k, v) } }.apply()
    }

    override fun remove(vararg keys: String) {
        prefs.edit().apply { keys.forEach(::remove) }.apply()
    }
}
