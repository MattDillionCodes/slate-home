package com.slatehome.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutTest {

    private class MemoryStore : KeyValueStore {
        val values = HashMap<String, String>()
        override fun get(key: String) = values[key]
        override fun put(entries: Map<String, String>) { values.putAll(entries) }
        override fun remove(vararg keys: String) { keys.forEach(values::remove) }
    }

    private val installed = setOf("a", "b", "c", "d", "e", "yt", "tv.twitch.android.app")
    private val isInstalled: (String) -> Boolean = { it in installed }

    @Test fun `default grid puts known services first, then the rest in order`() {
        val layout = HomeLayout(MemoryStore())
        val grid = layout.visibleGrid(isInstalled, listOf("a", "b", "tv.twitch.android.app"))
        assertEquals(listOf("tv.twitch.android.app", "a", "b"), grid)
        assertFalse(layout.customized)
    }

    @Test fun `default grid is capped at one page`() {
        val layout = HomeLayout(MemoryStore())
        val many = (1..20).map { "app$it" }
        assertEquals(HomeLayout.CAPACITY, layout.visibleGrid({ true }, many).size)
    }

    @Test fun `moving right reorders and saves`() {
        val store = MemoryStore()
        val layout = HomeLayout(store)
        assertTrue(layout.move("a", 1, 0, listOf("a", "b", "c"), listOf("yt")))
        assertEquals(listOf("b", "a", "c"), layout.visibleGrid(isInstalled, emptyList()))
        assertTrue(layout.customized)
        assertEquals("b,a,c", store.values["grid"])
    }

    @Test fun `moving down jumps a full row`() {
        val layout = HomeLayout(MemoryStore())
        assertTrue(layout.move("a", 0, 1, listOf("a", "b", "c", "d", "e"), emptyList()))
        assertEquals(listOf("b", "c", "d", "e", "a"), layout.grid.toList())
    }

    @Test fun `down past the last row drops into the dock`() {
        val layout = HomeLayout(MemoryStore())
        assertTrue(layout.move("b", 0, 1, listOf("a", "b"), listOf("yt")))
        assertEquals(listOf("a"), layout.grid.toList())
        assertEquals(listOf("yt", "b"), layout.dock.toList())
    }

    @Test fun `down above a short last row lands at its end, not the dock`() {
        val layout = HomeLayout(MemoryStore())
        val ten = (0..9).map { "g$it" } // rows: 0-3, 4-7, 8-9
        assertTrue(layout.move("g6", 0, 1, ten, listOf("yt")))
        assertEquals("g6", layout.grid.last())
        assertEquals(listOf("yt"), layout.dock.toList())
    }

    @Test fun `up from the dock returns to the end of the grid`() {
        val layout = HomeLayout(MemoryStore())
        assertTrue(layout.move("yt", 0, -1, listOf("a"), listOf("yt", "b")))
        assertEquals(listOf("a", "yt"), layout.grid.toList())
        assertEquals(listOf("b"), layout.dock.toList())
    }

    @Test fun `invalid moves change nothing and do not count as an edit`() {
        val store = MemoryStore()
        val layout = HomeLayout(store)
        assertFalse(layout.move("a", -1, 0, listOf("a", "b"), emptyList())) // left edge
        assertFalse(layout.move("a", 0, -1, listOf("a", "b"), emptyList())) // top edge
        assertFalse(layout.move("zz", 1, 0, listOf("a", "b"), emptyList())) // not shown
        assertFalse(layout.customized)
        assertTrue(store.values.isEmpty())
    }

    @Test fun `full dock refuses more apps`() {
        val layout = HomeLayout(MemoryStore())
        val dock = (1..HomeLayout.DOCK_CAPACITY).map { "d$it" }
        assertFalse(layout.move("a", 0, 1, listOf("a"), dock))
    }

    @Test fun `add refuses duplicates and a full grid`() {
        val layout = HomeLayout(MemoryStore())
        assertFalse(layout.add("a", listOf("a"), emptyList()))
        assertFalse(layout.add("yt", listOf("a"), listOf("yt")))
        assertFalse(layout.add("z", (1..HomeLayout.CAPACITY).map { "g$it" }, emptyList()))
        assertTrue(layout.add("c", listOf("a"), emptyList()))
        assertEquals(listOf("a", "c"), layout.grid.toList())
    }

    @Test fun `remove works from the dock too`() {
        val layout = HomeLayout(MemoryStore())
        layout.remove("yt", listOf("a"), listOf("yt", "b"))
        assertEquals(listOf("b"), layout.dock.toList())
    }

    @Test fun `saved layout survives a restart, minus uninstalled apps`() {
        val store = MemoryStore()
        HomeLayout(store).move("a", 1, 0, listOf("a", "b", "gone"), listOf("yt"))
        val reloaded = HomeLayout(store)
        assertTrue(reloaded.customized)
        assertEquals(listOf("b", "a"), reloaded.visibleGrid(isInstalled, emptyList()))
        assertEquals(listOf("yt"), reloaded.visibleDock(isInstalled))
    }

    @Test fun `reset forgets the arrangement`() {
        val store = MemoryStore()
        val layout = HomeLayout(store)
        layout.move("a", 1, 0, listOf("a", "b"), emptyList())
        layout.reset()
        assertFalse(layout.customized)
        assertTrue(store.values.isEmpty())
        assertFalse(HomeLayout(store).customized)
    }

    @Test fun `layouts saved by older builds are treated as untouched`() {
        val store = MemoryStore().apply { values["grid"] = Catalog.services.joinToString(",") }
        assertFalse(HomeLayout(store).customized)
    }
}
