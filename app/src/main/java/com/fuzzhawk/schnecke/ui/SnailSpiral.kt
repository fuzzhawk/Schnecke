package com.fuzzhawk.schnecke.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

/**
 * A snail-shell spiral. While [animate] is on it turns slowly and a highlight crawls along it
 * from the centre outwards; [drawn] (0..1) traces it in from the centre.
 */
@Composable
fun SnailSpiral(
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    drawn: Float = 1f,
    turns: Float = 3.1f,
    strokeWidth: Dp = 5.dp,
    inner: Color = Palette.sky,
    outer: Color = Palette.amber,
    background: Color = Palette.surface,
    /** Where the spiral starts, as a fraction of its outer radius (leave room for something inside). */
    innerRatio: Float = 0.05f,
) {
    val spin: Float
    val crawl: Float
    if (animate) {
        val t = rememberInfiniteTransition(label = "spiral")
        spin = t.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "spin").value
        crawl = t.animateFloat(-0.15f, 1.15f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "crawl").value
    } else {
        spin = 0f
        crawl = -1f
    }
    Canvas(modifier) {
        val n = 240
        val thetaMax = turns * 2 * PI
        val b = ln(1 / innerRatio.coerceIn(0.01f, 0.95f).toDouble()) / thetaMax
        val radius = min(size.width, size.height) / 2f * 0.92f
        val c = Offset(size.width / 2f, size.height / 2f)
        val rot = Math.toRadians(-spin.toDouble())
        fun point(i: Int): Offset {
            val theta = thetaMax * i / n
            val r = radius * exp(b * (theta - thetaMax))
            return Offset(c.x + (r * cos(theta + rot)).toFloat(), c.y + (r * sin(theta + rot)).toFloat())
        }
        val last = (drawn.coerceIn(0f, 1f) * n).toInt()
        val w = strokeWidth.toPx()
        var prev = point(0)
        for (i in 1..last) {
            val p = point(i)
            val f = i / n.toFloat()
            val d = f - crawl
            val glow = exp(-(d * d) / (2 * 0.05f * 0.05f))
            // Opaque colours (dimmed toward the background) so overlapping round caps don't bead.
            val color = lerp(background, lerp(inner, outer, f), (0.6f + 0.4f * glow).coerceAtMost(1f))
            drawLine(
                color,
                prev, p,
                strokeWidth = w * (0.3f + 0.7f * f) * (1f + 0.35f * glow),
                cap = StrokeCap.Round,
            )
            prev = p
        }
        if (last == n) drawCircle(outer, radius = w * 1.1f, center = prev)
    }
}

/** Full-screen spiral with a caption, for "working on it" moments. */
@Composable
fun SpiralOverlay(text: String) {
    Box(Modifier.fillMaxSize().background(Palette.background.copy(alpha = 0.82f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SnailSpiral(Modifier.size(132.dp), background = Palette.background)
            Text(text, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** The launch animation: the shell traces itself in around the snail. */
@Composable
fun IntroSplash(onDone: () -> Unit) {
    val drawn = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        drawn.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
        kotlinx.coroutines.delay(450)
        onDone()
    }
    Box(Modifier.fillMaxSize().background(Palette.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(contentAlignment = Alignment.Center) {
                SnailSpiral(Modifier.size(220.dp), animate = false, drawn = drawn.value, strokeWidth = 7.dp, background = Palette.background)
                Text("🐌", fontSize = 64.sp)
            }
            Text("Schnecke", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}
