package com.slatehome.tv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import com.slatehome.tv.ui.HomeApp

class MainActivity : ComponentActivity() {

    // Bumped on every resume so the home screen re-scans installed apps.
    private val resumeTick = mutableIntStateOf(0)

    // Bumped when the remote's Home button is pressed while we're already in front.
    private val homeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = AppRepository(applicationContext)
        val weather = WeatherRepository()
        val wallpapers = WallpaperStore(applicationContext)
        val layout = HomeLayout(PrefsStore(applicationContext))
        setContent {
            HomeApp(
                repo = repo,
                weather = weather,
                wallpapers = wallpapers,
                layout = layout,
                resumeTick = resumeTick.intValue,
                homeTick = homeTick.intValue,
            )
        }
        // The wallpaper paints every pixel, so the window's own black fill is pure overdraw.
        window.setBackgroundDrawable(null)
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) homeTick.intValue++
    }
}
