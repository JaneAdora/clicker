package com.janeadora.clicker.ui.theme

import android.app.Activity
import android.content.Context
import android.view.animation.AccelerateInterpolator
import androidx.annotation.RequiresApi
import android.provider.Settings as AndroidSettings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow

/** True when the phone's animator duration scale is 0 (Remove animations). */
fun reduceMotion(context: Context): Boolean =
    AndroidSettings.Global.getFloat(
        context.contentResolver,
        AndroidSettings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f

/**
 * The one easing for things that settle: quick away, soft landing, no
 * overshoot. Key returns, lit arrows, the status pulse and the type bar all use it.
 */
val Settle: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/** How long a panel (the type bar) takes to arrive. */
const val PANEL_IN_MS = 180

/** How long the launch entrance runs, from the first frame to the still screen. */
const val ENTRANCE_MS = 520L

/**
 * The entrance is one front that leaves the top right corner and crosses the
 * screen. This is the share of the screen's diagonal behind the front in
 * which things are still growing; further back they have arrived.
 */
const val ENTRANCE_BAND = 0.24f

/**
 * Where the entrance's front is [elapsedMs] after it started: 0 at the seed
 * corner, 1 once everything has arrived, and 1 from [ENTRANCE_MS] on. It
 * slows as it goes, so most of the screen is there early.
 */
fun entranceFront(elapsedMs: Long): Float {
    val t = (elapsedMs.toFloat() / ENTRANCE_MS).coerceIn(0f, 1f)
    return 1f - (1f - t).pow(2.2f)
}

/**
 * How far from the seed (the top right corner) the point ([x], [y]) lies in
 * a [w] by [h] screen: 0 at the corner, 1 at the opposite one.
 */
fun seedDistance(x: Float, y: Float, w: Float, h: Float): Float {
    val diagonal = hypot(w, h)
    return if (diagonal <= 0f) 0f else hypot(w - x, y) / diagonal
}

/**
 * How much of something [distance] from the seed (see [seedDistance]) has
 * arrived when the front is at [front]: 0 before the front reaches it, then
 * growing evenly to 1. With the front at 1 everything on screen is at 1.
 */
fun arrived(front: Float, distance: Float): Float =
    if (front >= 1f) 1f else ((front * (1f + ENTRANCE_BAND) - distance) / ENTRANCE_BAND).coerceIn(0f, 1f)

/**
 * Decides whether the launch entrance plays. Once per process, and only for
 * an activity started fresh: never after a rotation, on a return from the
 * background or for an activity rebuilt from saved state.
 */
class EntranceGate {
    private var used = false

    /** True the first time, when the activity is [fresh] (it has no saved state). False ever after. */
    fun claim(fresh: Boolean): Boolean {
        val play = fresh && !used
        used = true
        return play
    }
}

/**
 * The launch entrance's clock. [front] is read in draw phases only, so a
 * running entrance redraws the ground and the body without recomposing
 * anything. Once [front] is 1 nothing reads the clock again.
 */
@Stable
class Entrance internal constructor(start: Float) {
    var front by mutableFloatStateOf(start)
        internal set

    /** Changes once, when the entrance ends, so reading it while composing recomposes once. */
    val running: Boolean by derivedStateOf { front < 1f }
}

/** The entrance the dot ground and the body follow; null where there is none (previews of single parts). */
val LocalEntrance: ProvidableCompositionLocal<Entrance?> = staticCompositionLocalOf { null }

/**
 * The entrance for this screen. With [play] it starts at nothing and runs
 * for [ENTRANCE_MS] against the frame clock, so it takes the same time at
 * 60 and 120Hz. Without [play], or with reduce motion on, it starts and
 * stays at the end state. It first waits for [splashGone], so the splash's
 * mark and the body never share the screen, but never longer than
 * [SPLASH_WAIT_MS]; the screen under it takes touches all the while.
 * [onPhase] hears "entrance start" and "entrance end", for logs.
 */
@Composable
fun rememberEntrance(play: Boolean, splashGone: suspend () -> Unit = {}, onPhase: (String) -> Unit = {}): Entrance {
    val context = LocalContext.current
    val entrance = remember { Entrance(if (play && !reduceMotion(context)) 0f else 1f) }
    LaunchedEffect(entrance) {
        if (!entrance.running) return@LaunchedEffect
        try {
            withTimeoutOrNull(SPLASH_WAIT_MS) { splashGone() }
            onPhase("entrance start")
            val start = withFrameNanos { it }
            while (entrance.running) {
                val elapsed = (withFrameNanos { it } - start) / 1_000_000
                entrance.front = entranceFront(elapsed)
            }
        } finally {
            entrance.front = 1f
            onPhase("entrance end")
        }
    }
    return entrance
}

/** The longest the entrance waits for the system splash to hand over before it starts anyway. */
const val SPLASH_WAIT_MS = 300L

/** How long the splash's mark takes to draw back into a dot. Short, so it is gone before the body prints over its place. */
const val SPLASH_OUT_MS = 100L

/**
 * Takes over from the system splash (Android 12 and later) when the first
 * frame is ready. The splash's ground comes off at once: the screen's own
 * ground is under it, the same color. The mark draws back into a dot and
 * out over [SPLASH_OUT_MS] while the entrance starts in the corner, and is gone. With
 * reduce motion the splash just goes. [onGone] runs at the hand over.
 */
@RequiresApi(31)
fun handOverFromSplash(activity: Activity, onGone: () -> Unit) {
    activity.splashScreen.setOnExitAnimationListener { splash ->
        onGone()
        val mark = splash.iconView
        if (mark == null || reduceMotion(activity)) {
            splash.remove()
        } else {
            splash.background = null
            mark.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(SPLASH_OUT_MS)
                .setInterpolator(AccelerateInterpolator()).withEndAction { splash.remove() }
        }
    }
}

/** One beat of the link light while a connection is on its way. */
const val LINK_PULSE_MS = 1100L

/**
 * The link light's glow [elapsedMs] into connecting: from 1 down to 0 and
 * back once every [LINK_PULSE_MS], starting full.
 */
fun linkPulse(elapsedMs: Long): Float {
    val phase = (elapsedMs % LINK_PULSE_MS).toFloat() / LINK_PULSE_MS
    return (0.5 + 0.5 * cos(2 * PI * phase)).toFloat()
}
