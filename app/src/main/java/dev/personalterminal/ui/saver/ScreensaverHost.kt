package dev.personalterminal.ui.saver

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.withFrameNanos

private val LIME = Color(0xFFC5FF4A)
private val SAVER_CHARS = "01ABCDEF$#*+=<>/\\"

/**
 * Idle screensaver host: wraps the whole UI, tracks input, and after [afterMin] minutes without
 * any (or when the app resumes following a long sleep) fades to the pattern until a tap or key.
 * Patterns: `matrix` rain, Conway's `life`, `stars` — pure Canvas, no assets.
 */
@Composable
fun ScreensaverHost(pattern: String, afterMin: Int, content: @Composable BoxScope.() -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var lastActiveMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var active by remember { mutableStateOf(false) }

    fun wake() {
        active = false
        lastActiveMs = System.currentTimeMillis()
    }

    // Any pointer event or key counts as activity, even while the saver shows (it dismisses too).
    val activityModifier = Modifier
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent()
                    wake()
                }
            }
        }
        .onPreviewKeyEvent {
            wake()
            false
        }

    // Idle countdown restarts whenever activity resets it.
    LaunchedEffect(pattern, afterMin, lastActiveMs) {
        if (pattern == "off" || afterMin <= 0) return@LaunchedEffect
        val waitMs = afterMin * 60_000L - (System.currentTimeMillis() - lastActiveMs)
        if (waitMs <= 0) { active = true; return@LaunchedEffect }
        delay(waitMs)
        active = true
    }

    // Returning to the foreground is not "new input": the countdown still applies on resume.
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) lastActiveMs = System.currentTimeMillis()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Box(Modifier.fillMaxSize().then(activityModifier)) {
        content()
        if (active && pattern != "off") {
            SaverCanvas(pattern, Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
                detectTapGestures { wake() }
            })
        }
    }
}

@Composable
private fun SaverCanvas(pattern: String, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    var frame by remember { mutableLongStateOf(0L) }
    var lastNs by remember { mutableLongStateOf(0L) }

    // Animation state, built once per pattern.
    val rng = remember(pattern) { Random(pattern.hashCode()) }
    val matrix = remember(pattern) { if (pattern == "matrix") MatrixState() else null }
    val life = remember(pattern) { if (pattern == "life") LifeState() else null }
    val stars = remember(pattern) { if (pattern == "stars") StarsState() else null }

    LaunchedEffect(pattern) {
        while (true) {
            withFrameNanos { now ->
                val dt = if (lastNs == 0L) 0f else ((now - lastNs) / 1e9f).coerceIn(0f, 0.1f)
                lastNs = now
                matrix?.advance(dt, rng)
                life?.advance(dt)
                stars?.advance(dt)
                frame = now
            }
        }
    }

    Canvas(modifier) {
        when (pattern) {
            "matrix" -> matrix?.draw(this, measurer, frame)
            "life" -> life?.draw(this)
            "stars" -> stars?.draw(this)
        }
    }
}

// ------------------------------------------------------------------ pattern state

private class MatrixState {
    private class Col(var head: Float, var speed: Float, var trail: Int)
    private var cols: List<Col>? = null
    private var w = 0f
    private var h = 0f

    private fun ensure(width: Float, height: Float) {
        if (cols != null && w == width && h == height) return
        w = width; h = height
        val count = (width / 16f).toInt().coerceAtLeast(8)
        cols = List(count) {
            Col(
                head = -Random.nextInt(0, (height + 400f).toInt()).toFloat(),
                speed = 140f + Random.nextFloat() * 380f,
                trail = 8 + Random.nextInt(14),
            )
        }
    }

    fun advance(dt: Float, rng: Random) {
        val cs = cols ?: return
        for (c in cs) {
            c.head += c.speed * dt
            if (c.head - c.trail * 18f > h) {
                c.head = -rng.nextInt(0, 300).toFloat()
                c.speed = 140f + rng.nextFloat() * 380f
                c.trail = 8 + rng.nextInt(14)
            }
        }
    }

    fun draw(scope: DrawScope, measurer: TextMeasurer, frame: Long) {
        val width = scope.size.width
        val height = scope.size.height
        ensure(width, height)
        val cs = cols ?: return
        val cell = 16f
        val style = TextStyle(color = Color(0xFF9C9C9C), fontSize = 14.sp, fontFamily = FontFamily.Monospace)
        val headStyle = style.copy(color = LIME)
        cs.forEachIndexed { i, c ->
            val x = i * cell + 2f
            for (t in 0 until c.trail) {
                val y = c.head - t * cell
                if (y < -cell || y > height + cell) continue
                val ch = SAVER_CHARS[(x.toInt() * 31 + y.toInt() + t * 7 + (frame / 240).toInt()) % SAVER_CHARS.length]
                val glyph = measurer.measure(ch.toString(), if (t == 0) headStyle else style)
                scope.drawText(glyph, topLeft = Offset(x, y - cell))
            }
        }
    }
}

private class LifeState {
    private var cell = 14f
    private var gw = 0
    private var gh = 0
    private var grid = BooleanArray(0)
    private var next = BooleanArray(0)
    private var acc = 0f
    private var w = 0f
    private var h = 0f

    private fun ensure(width: Float, height: Float) {
        if (gw > 0 && w == width && h == height) return
        w = width; h = height
        gw = (width / cell).toInt().coerceAtLeast(10)
        gh = (height / cell).toInt().coerceAtLeast(10)
        grid = BooleanArray(gw * gh) { Random.nextFloat() < 0.28f }
        next = BooleanArray(gw * gh)
    }

    private fun step() {
        for (y in 0 until gh) for (x in 0 until gw) {
            var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = (x + dx + gw) % gw
                val ny = (y + dy + gh) % gh
                if (grid[ny * gw + nx]) n++
            }
            val alive = grid[y * gw + x]
            next[y * gw + x] = if (alive) n == 2 || n == 3 else n == 3
        }
        val tmp = grid
        grid = next
        next = tmp
    }

    fun advance(dt: Float) {
        acc += dt
        if (acc > 0.35f) { acc = 0f; step() }
    }

    fun draw(scope: DrawScope) {
        val width = scope.size.width
        val height = scope.size.height
        ensure(width, height)
        val ox = (width - gw * cell) / 2f
        val oy = (height - gh * cell) / 2f
        for (y in 0 until gh) for (x in 0 until gw) {
            if (!grid[y * gw + x]) continue
            val recent = acc < 0.12f
            scope.drawRect(
                color = if (recent) LIME else Color(0xFF597321),
                topLeft = Offset(ox + x * cell + 1f, oy + y * cell + 1f),
                size = androidx.compose.ui.geometry.Size(cell - 2f, cell - 2f),
            )
        }
    }
}

private class StarsState {
    private class Star(var x: Float, var y: Float, var z: Float)
    private var stars: List<Star>? = null
    private fun ensure() {
        if (stars != null) return
        stars = List(180) {
            Star(Random.nextFloat() * 2f - 1f, Random.nextFloat() * 2f - 1f, 0.05f + Random.nextFloat() * 0.95f)
        }
    }

    fun advance(dt: Float) {
        val ss = stars ?: return
        for (s in ss) {
            s.z -= dt * 0.16f
            if (s.z < 0.03f) {
                s.x = Random.nextFloat() * 2f - 1f
                s.y = Random.nextFloat() * 2f - 1f
                s.z = 1f
            }
        }
    }

    fun draw(scope: DrawScope) {
        ensure()
        val cx = scope.size.width / 2f
        val cy = scope.size.height / 2f
        val radius = minOf(cx, cy) * 1.15f
        for (s in stars.orEmpty()) {
            val k = 1f / s.z
            val px = cx + s.x * radius * k * 0.5f
            val py = cy + s.y * radius * k * 0.5f
            if (px < -10 || px > scope.size.width + 10 || py < -10 || py > scope.size.height + 10) continue
            val r = (1.6f / s.z).coerceAtMost(5f)
            val a = ((1.1f - s.z) * 0.9f).coerceIn(0.15f, 1f)
            scope.drawCircle(color = Color.White.copy(alpha = a), radius = r, center = Offset(px, py))
        }
    }
}
