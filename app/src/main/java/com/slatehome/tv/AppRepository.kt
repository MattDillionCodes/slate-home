package com.slatehome.tv

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.widget.Toast
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Knows which apps exist on the TV, launches them, and remembers what you opened recently. */
class AppRepository(private val context: Context) {

    private val pm = context.packageManager
    private val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)

    /** Every launchable app on the device, alphabetical — feeds the App Library. */
    var installed by mutableStateOf<List<AppEntry>>(emptyList())
        private set

    private var installedPackages by mutableStateOf<Set<String>>(emptySet())

    /** False until the first scan finishes — before that, "not installed" means "don't know yet". */
    var loaded by mutableStateOf(false)
        private set

    /** Apps that are home-screen launchers themselves — left off the default grid. */
    var launchers by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Most-recent-first package names, persisted across restarts. */
    val recents = mutableStateListOf<String>().apply {
        addAll(prefs.getString(KEY_RECENTS, "").orEmpty().split(',').filter { it.isNotBlank() })
    }

    suspend fun refresh() {
        val (apps, homes) = withContext(Dispatchers.IO) { queryLaunchableApps() to queryLaunchers() }
        installed = apps
        installedPackages = apps.mapTo(HashSet()) { it.pkg }
        launchers = homes
        loaded = true
    }

    fun isInstalled(pkg: String) = pkg in installedPackages

    /**
     * Whether an app deserves a spot on the home screen by default: anything you installed
     * yourself, plus preinstalled apps we recognise as real services (YouTube, a bundled Prime
     * Video…). Preinstalled utilities and other launchers stay in the App Library only.
     */
    fun belongsOnHome(app: AppEntry): Boolean =
        app.pkg !in launchers && (!app.isSystem || app.pkg in Catalog.known)

    /** The installed app with this package, real icon and all — null if it isn't installed. */
    fun entryFor(pkg: String): AppEntry? = installed.find { it.pkg == pkg }

    fun launch(activityContext: Context, app: AppEntry) {
        val intent = pm.getLeanbackLaunchIntentForPackage(app.pkg)
            ?: pm.getLaunchIntentForPackage(app.pkg)
        // Uninstalled or disabled since the last scan, or not ours to start: fall through.
        val launched = intent != null && runCatching {
            activityContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (launched) {
            rememberRecent(app.pkg)
            return
        }
        // Disabled or uninstalled since the last scan: its store page offers Install or Enable.
        // On a TV with no store, say so rather than leave the press doing nothing.
        val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${app.pkg}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { activityContext.startActivity(store) }.isFailure) {
            Toast.makeText(activityContext, "${app.label} isn't available on this TV", Toast.LENGTH_SHORT).show()
        }
    }

    private fun rememberRecent(pkg: String) {
        recents.remove(pkg)
        recents.add(0, pkg)
        while (recents.size > MAX_RECENTS) recents.removeAt(recents.lastIndex)
        prefs.edit().putString(KEY_RECENTS, recents.joinToString(",")).apply()
    }

    private fun queryLaunchableApps(): List<AppEntry> =
        listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
            .flatMap { category ->
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
            }
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != context.packageName }
            .map { info ->
                AppEntry(
                    pkg = info.activityInfo.packageName,
                    label = info.loadLabel(pm).toString(),
                    icon = info.loadIcon(pm).toImageBitmap(ICON_PX),
                    isSystem = info.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                )
            }
            .sortedBy { it.label.lowercase() }

    private fun queryLaunchers(): Set<String> =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .mapTo(HashSet()) { it.activityInfo.packageName }

    private companion object {
        const val KEY_RECENTS = "recents"
        const val MAX_RECENTS = 8
        const val ICON_PX = 192
    }
}

private fun Drawable.toImageBitmap(px: Int): ImageBitmap {
    val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    setBounds(0, 0, px, px)
    draw(Canvas(bitmap))
    return bitmap.asImageBitmap()
}
