package com.slatehome.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slatehome.tv.AppEntry

/**
 * A full-screen grid of apps over the frosted home screen — iPad's App Library. Also reused as
 * the "Add to Home Screen" picker in edit mode.
 */
@Composable
fun AppLibrary(
    apps: List<AppEntry>,
    onSelect: (AppEntry) -> Unit,
    title: String = "App Library",
    subtitle: String = "${apps.size} apps on this TV  ·  Press Back to go home",
    emptyMessage: String? = null,
) {
    val first = remember { FocusRequester() }
    val hasApps = apps.isNotEmpty()
    LaunchedEffect(hasApps) { if (hasApps) first.requestFocusSafely() }

    Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 28.dp)) {
        Text(title, color = Palette.label, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Palette.secondary, fontSize = 14.sp)
        Spacer(Modifier.height(18.dp))
        if (!hasApps && emptyMessage != null) {
            Text(emptyMessage, color = Palette.label, fontSize = 18.sp, modifier = Modifier.padding(top = 40.dp))
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(8),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
        ) {
            itemsIndexed(apps, key = { _, app -> app.pkg }) { index, app ->
                AppTile(
                    app = app,
                    onClick = { onSelect(app) },
                    iconSize = 64.dp,
                    focusRequester = if (index == 0) first else null,
                )
            }
        }
    }
}
