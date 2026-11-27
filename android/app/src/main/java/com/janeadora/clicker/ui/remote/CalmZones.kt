package com.janeadora.clicker.ui.remote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.janeadora.clicker.ui.theme.CalmZone

/**
 * The calm zones in a stable order (sorted by key), or null until every
 * key in [keys] has a zone, so the first frame never shows dots behind a
 * control.
 */
internal fun <T> orderedCalm(zones: Map<String, T>, keys: Collection<String>): List<T>? =
    if (keys.isEmpty() || keys.any { it !in zones }) null else keys.sorted().map { zones.getValue(it) }

/**
 * Collects the shapes the dot ground must keep clear of. On the remote
 * that is one: the body, which covers everything inside it. Bounds are
 * taken relative to the container the dot ground fills, so they hold
 * still while that container scrolls.
 */
internal class CalmZones {
    private var container: LayoutCoordinates? = null
    private val zones = mutableStateMapOf<String, CalmZone>()
    private val keys = mutableStateListOf<String>()

    /** Put on the layout the dot ground fills. Placed before any child reports its position. */
    fun container(): Modifier = Modifier.onPlaced { container = it }

    /** Marks one element, a rectangle with [corner] rounding, under a unique [key]. */
    @Composable
    fun track(key: String, corner: Dp): Modifier {
        val cornerPx = with(LocalDensity.current) { corner.toPx() }
        DisposableEffect(key) {
            keys.add(key)
            onDispose {
                keys.remove(key)
                zones.remove(key)
            }
        }
        return Modifier.onGloballyPositioned { child ->
            val parent = container?.takeIf { it.isAttached } ?: return@onGloballyPositioned
            val r = parent.localBoundingBoxOf(child, clipBounds = false)
            val zone = CalmZone.Rounded(r, cornerPx)
            if (zones[key] != zone) zones[key] = zone
        }
    }

    /**
     * The calm zones, or null while something is still unplaced. Reads
     * snapshot state, so call it from the draw phase: a change then redraws
     * the dot ground without recomposing the screen.
     */
    fun ready(): List<CalmZone>? = orderedCalm(zones, keys)
}

/** The remote's calm zones, when the composable sits on a dot ground. */
internal val LocalCalmZones: ProvidableCompositionLocal<CalmZones?> = staticCompositionLocalOf { null }

/**
 * Keeps the dot ground calm around this element, a rectangle with [corner]
 * rounding. Does nothing where there is no dot ground (previews of single
 * parts).
 */
@Composable
internal fun Modifier.calm(key: String, corner: Dp): Modifier {
    val zones = LocalCalmZones.current ?: return this
    return this.then(zones.track(key, corner))
}
