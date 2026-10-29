package com.slatehome.tv

import androidx.compose.ui.graphics.ImageBitmap

/** One app on the TV, with its real launcher icon. */
data class AppEntry(
    val pkg: String,
    val label: String,
    val icon: ImageBitmap? = null,
    /** Preinstalled on the system image (TV inputs, quick panels, the maker's own launcher…). */
    val isSystem: Boolean = false,
)

/** Apps we know by package name — no artwork, just who's who. */
object Catalog {

    /** Pinned in the dock when installed (iPad keeps 4–6 there). */
    val dock = listOf(
        "com.google.android.youtube.tv", // YouTube
        "com.netflix.ninja", // Netflix
        "com.spotify.tv.android", // Spotify
        "com.android.vending", // Play Store
        "com.android.tv.settings", // Settings
    )

    /** Well-known services: they head the default grid, and count even when preinstalled. */
    val services = listOf(
        "com.amazon.amazonvideo.livingroom", // Prime Video
        "com.disney.disneyplus", // Disney+
        "com.wbd.stream", // Max
        "com.hulu.livingroomplus", // Hulu
        "com.apple.atve.androidtv.appletv", // Apple TV
        "com.plexapp.android", // Plex
        "tv.twitch.android.app", // Twitch
        "com.google.android.youtube.tvmusic", // YouTube Music
        "com.peacocktv.peacockandroid", // Peacock
        "com.cbs.ott", // Paramount+
        "com.tubitv", // Tubi
        "com.google.android.tv", // Live Channels
    )

    val known: Set<String> = (dock + services).toSet()
}
