package com.slatehome.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dehaze
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slatehome.tv.Forecast
import com.slatehome.tv.Sky
import com.slatehome.tv.WeatherState
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private val WidgetShape = RoundedCornerShape(26.dp)
private val WidgetTint = Color.Black.copy(alpha = 0.22f)

/** iPad's small Clock widget: white face, black hands, orange second hand. */
@Composable
fun ClockWidget(modifier: Modifier = Modifier) {
    val now = LocalNow.current
    GlassSurface(modifier, WidgetShape, WidgetTint) {
        // Cached: redrawn once a second when the hands move, not on every animation frame.
        Canvas(Modifier.fillMaxSize().cached().padding(14.dp)) {
            val t = now.value
            val r = size.minDimension / 2f
            drawCircle(Color.White, r)
            for (i in 0 until 60) {
                val major = i % 5 == 0
                drawLine(
                    color = if (major) Palette.ink else Color(0xFF8E8E93).copy(alpha = 0.55f),
                    start = polar(i * 6f, r * if (major) 0.76f else 0.84f),
                    end = polar(i * 6f, r * 0.92f),
                    strokeWidth = if (major) 2.4.dp.toPx() else 1.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            val minutes = t.minute + t.second / 60f
            hand((t.hour % 12 + minutes / 60f) * 30f, r * 0.50f, 4.5.dp, Palette.ink)
            hand(minutes * 6f, r * 0.78f, 3.2.dp, Palette.ink)
            hand(t.second * 6f, r * 0.86f, 1.4.dp, Palette.orange, tail = r * 0.16f)
            drawCircle(Palette.orange, 3.5.dp.toPx())
            drawCircle(Color.White, 1.4.dp.toPx())
        }
    }
}

/** Point on a circle around the centre; 0° is twelve o'clock. */
private fun DrawScope.polar(degrees: Float, length: Float): Offset {
    val rad = Math.toRadians(degrees - 90.0)
    return Offset(center.x + cos(rad).toFloat() * length, center.y + sin(rad).toFloat() * length)
}

private fun DrawScope.hand(degrees: Float, length: Float, width: Dp, color: Color, tail: Float = 0f) {
    drawLine(color, polar(degrees + 180f, tail), polar(degrees, length), width.toPx(), StrokeCap.Round)
}

/** iPad's small Calendar widget: red weekday, big date, and a time-of-day greeting. */
@Composable
fun CalendarWidget(modifier: Modifier = Modifier) {
    val now = LocalNow.current.value
    val greeting = when (now.hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Good night"
    }
    GlassSurface(modifier, WidgetShape, WidgetTint) {
        Column(Modifier.fillMaxSize().cached().padding(16.dp)) {
            Text(
                now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()).uppercase(),
                color = Palette.red,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
            )
            Text(
                now.dayOfMonth.toString(),
                color = Palette.label,
                fontSize = 50.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = (-1.5).sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                now.month.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                color = Palette.label,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(greeting, color = Palette.secondary, fontSize = 12.sp)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Weather
// ---------------------------------------------------------------------------------------------

/** iPad's medium Weather widget: conditions now on top, a 5-day strip below, tinted by the sky. */
@Composable
fun WeatherWidget(state: WeatherState, modifier: Modifier = Modifier) {
    val forecast = (state as? WeatherState.Ready)?.forecast
    GlassSurface(modifier, WidgetShape, Color.Transparent) {
        Box(Modifier.matchParentSize().cached()) {
            Box(Modifier.matchParentSize().clip(WidgetShape).background(skyGradient(forecast)))
            when (state) {
                is WeatherState.Ready -> ForecastContent(state.forecast)
                WeatherState.Loading -> WeatherMessage(Sky.PartlyCloudy, "Loading forecast…", null)
                WeatherState.Unavailable -> WeatherMessage(Sky.Cloudy, "Weather unavailable", "Check your internet connection")
            }
        }
    }
}

@Composable
private fun ForecastContent(forecast: Forecast) {
    val today = forecast.days.firstOrNull()
    val locale = Locale.getDefault()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        forecast.place,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 150.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Rounded.NearMe, null, tint = Color.White, modifier = Modifier.size(11.dp))
                }
                Text("${forecast.temperature}°", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Light)
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                WeatherGlyph(forecast.sky, forecast.isDay, 20.dp)
                Spacer(Modifier.height(3.dp))
                Text(
                    if (forecast.isDay) forecast.sky.dayLabel else forecast.sky.nightLabel,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (today != null) {
                    Text("H:${today.high}°  L:${today.low}°", color = Color.White, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            forecast.days.forEachIndexed { index, day ->
                Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (index == 0) "Today" else day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    WeatherGlyph(day.sky, isDay = true, size = 22.dp)
                    Spacer(Modifier.height(4.dp))
                    Text("${day.high}°", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("${day.low}°", color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun BoxScope.WeatherMessage(sky: Sky, title: String, detail: String?) {
    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
        WeatherGlyph(sky, isDay = true, size = 34.dp)
        Spacer(Modifier.height(8.dp))
        Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (detail != null) Text(detail, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
    }
}

/** Sky-coloured backdrop like the iOS Weather app: blue days, slate rain, navy nights. */
private fun skyGradient(forecast: Forecast?): Brush {
    val (top, bottom) = when {
        forecast == null -> 0xFF3A4A66 to 0xFF1E2840
        !forecast.isDay -> 0xFF22305A to 0xFF0B1433
        forecast.sky == Sky.Clear || forecast.sky == Sky.PartlyCloudy -> 0xFF4AA0FF to 0xFF1C5FD4
        forecast.sky == Sky.Snow -> 0xFF9AB3D6 to 0xFF5E7CA8
        forecast.sky == Sky.Cloudy || forecast.sky == Sky.Fog -> 0xFF7F92AD to 0xFF46586F
        else -> 0xFF5B6A84 to 0xFF2C384D
    }
    return Brush.verticalGradient(listOf(Color(top).copy(alpha = 0.9f), Color(bottom).copy(alpha = 0.9f)))
}

private val SunYellow = Color(0xFFFFD60A)
private val CloudWhite = Color(0xFFF2F2F7)
private val RainBlue = Color(0xFF64D2FF)

/**
 * iOS draws weather with multicolour SF Symbols (yellow sun, white cloud, blue drops).
 * Material only has single-colour icons, so we stack two of them per condition.
 */
@Composable
fun WeatherGlyph(sky: Sky, isDay: Boolean, size: Dp) {
    Box(Modifier.size(size)) {
        val celestial = if (isDay) Icons.Rounded.WbSunny else Icons.Rounded.Bedtime
        val celestialTint = if (isDay) SunYellow else CloudWhite
        when (sky) {
            Sky.Clear -> Layer(celestial, celestialTint, size, Alignment.Center)
            Sky.PartlyCloudy -> {
                Layer(celestial, celestialTint, size * 0.64f, Alignment.TopStart)
                Layer(Icons.Rounded.Cloud, CloudWhite, size * 0.80f, Alignment.BottomEnd)
            }
            Sky.Cloudy -> Layer(Icons.Rounded.Cloud, CloudWhite, size, Alignment.Center)
            Sky.Fog -> {
                Layer(Icons.Rounded.Cloud, CloudWhite.copy(alpha = 0.8f), size * 0.80f, Alignment.TopCenter)
                Layer(Icons.Rounded.Dehaze, CloudWhite, size * 0.50f, Alignment.BottomCenter)
            }
            // One bold drop reads as "rain" from the couch; Material's dotted Grain looked like snow.
            Sky.Drizzle -> CloudOver(Icons.Rounded.WaterDrop, RainBlue, size, 0.42f)
            Sky.Rain -> CloudOver(Icons.Rounded.WaterDrop, RainBlue, size, 0.58f)
            Sky.Snow -> CloudOver(Icons.Rounded.AcUnit, CloudWhite, size, 0.52f)
            Sky.Storm -> CloudOver(Icons.Rounded.Bolt, SunYellow, size, 0.60f)
        }
    }
}

@Composable
private fun BoxScope.CloudOver(precipitation: ImageVector, tint: Color, size: Dp, scale: Float) {
    Layer(Icons.Rounded.Cloud, CloudWhite, size * 0.78f, Alignment.TopCenter)
    Layer(precipitation, tint, size * scale, Alignment.BottomCenter)
}

@Composable
private fun BoxScope.Layer(icon: ImageVector, tint: Color, size: Dp, alignment: Alignment) {
    Icon(icon, null, tint = tint, modifier = Modifier.size(size).align(alignment))
}
