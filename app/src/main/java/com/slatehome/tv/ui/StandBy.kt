package com.slatehome.tv.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slatehome.tv.WeatherState
import java.time.format.DateTimeFormatter
import kotlin.random.Random

private val ClockFormat = DateTimeFormatter.ofPattern("h:mm")
private val LongDateFormat = DateTimeFormatter.ofPattern("EEEE, MMMM d")

/** When the remote was last touched. Plain field, not state: reading it must not recompose. */
class IdleClock {
    @Volatile
    var last = SystemClock.uptimeMillis()
        private set

    fun touch() {
        last = SystemClock.uptimeMillis()
    }

    fun quietFor(): Long = SystemClock.uptimeMillis() - last
}

/** Five quiet minutes on the home screen — or 30 seconds in debug builds, so it's testable. */
fun standByDelayMs(context: Context): Long =
    if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) 30_000L else 5 * 60_000L

/**
 * iOS StandBy, for a TV left on: a big clock with the date and weather over the frosted
 * wallpaper. Nothing animates, so the screen only redraws when the minute changes.
 */
@Composable
fun StandBy(weather: WeatherState) {
    val now = LocalNow.current.value
    // A clock left in one spot for hours can burn into some panels, so it drifts a little each minute.
    val driftX = remember(now.hour, now.minute) { Random.nextInt(-28, 29) }
    val driftY = remember(now.hour, now.minute) { Random.nextInt(-18, 19) }

    // Remote presses only reach the screen's key handler through whatever holds focus, and the
    // home screen underneath gives it up — so StandBy holds focus itself to be dismissable.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocusSafely() }
    Box(Modifier.fillMaxSize().focusRequester(focus).focusable()) {
        Column(
            Modifier.align(Alignment.Center).offset(driftX.dp, driftY.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                now.format(ClockFormat),
                color = Color.White,
                fontSize = 168.sp,
                lineHeight = 172.sp,
                fontWeight = FontWeight.Thin,
                letterSpacing = (-6).sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(now.format(LongDateFormat), color = Color.White.copy(alpha = 0.82f), fontSize = 26.sp)
                if (weather is WeatherState.Ready) {
                    val forecast = weather.forecast
                    Spacer(Modifier.width(28.dp))
                    WeatherGlyph(forecast.sky, forecast.isDay, 30.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("${forecast.temperature}°", color = Color.White.copy(alpha = 0.82f), fontSize = 26.sp)
                }
            }
        }
        Text(
            "Press any button to return",
            color = Color.White.copy(alpha = 0.35f),
            fontSize = 13.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
        )
    }
}
