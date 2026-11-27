package com.janeadora.clicker.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.janeadora.clicker.ui.theme.ANEK_CAP_LIFT_EM
import com.janeadora.clicker.ui.theme.AnekGurmukhi
import com.janeadora.clicker.ui.theme.ClickerShapes
import com.janeadora.clicker.ui.theme.ClickerType
import com.janeadora.clicker.ui.theme.Direction
import com.janeadora.clicker.ui.theme.DotGround
import com.janeadora.clicker.ui.theme.DottedChevron
import com.janeadora.clicker.ui.theme.FiraCode
import com.janeadora.clicker.ui.theme.LocalClickerColors

/** Debug gallery: swatches, each text style, and a 3x3 block of buttons on the dot ground. */
@Composable
fun ThemeGallery() {
    val c = LocalClickerColors.current
    val calm = remember { mutableStateMapOf<String, Rect>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    fun Modifier.calm(key: String) = onGloballyPositioned { calm[key] = it.boundsInRoot() }

    Box(
        Modifier.fillMaxSize().background(c.surface)
            .onGloballyPositioned { origin = it.positionInRoot() },
    ) {
        DotGround(calm.values.map { it.translate(-origin) }, Modifier.fillMaxSize())
        Column(
            Modifier.safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.calm("text"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Living Room", style = ClickerType.title, color = c.heading)
                Text("Body text sits on calm ground.", style = ClickerType.body, color = c.onSurface)
                Text("Muted secondary text", style = ClickerType.body, color = c.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    for (w in listOf(400, 500, 600)) {
                        Text("Anek $w", style = TextStyle(fontFamily = AnekGurmukhi, fontWeight = FontWeight(w), fontSize = 20.sp), color = c.onSurface)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    for (w in listOf(400, 500, 600)) {
                        Text("Fira $w", style = TextStyle(fontFamily = FiraCode, fontWeight = FontWeight(w), fontSize = 16.sp), color = c.onSurface)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Button 17", style = ClickerType.button, color = c.heading)
                    Text("Label 14", style = ClickerType.label, color = c.heading)
                }
                Text("connected  vol 12", style = ClickerType.status, color = c.muted)
                Text("A3F9C2", style = ClickerType.pin, color = c.onSurface)
            }
            Swatches(Modifier.calm("swatches"))
            Spacer(Modifier.height(8.dp))
            ButtonBlock(onPlaced = { key, r -> calm[key] = r })
        }
    }
}

@Composable
private fun Swatches(modifier: Modifier) {
    val c = LocalClickerColors.current
    val roles = listOf(
        "surface" to c.surface, "container" to c.surfaceContainer, "raised" to c.surfaceRaised,
        "onSurface" to c.onSurface, "heading" to c.heading, "muted" to c.muted,
        "accent" to c.accent, "pressed" to c.accentPressed, "onAccent" to c.onAccent,
        "outline" to c.outline, "outlineHi" to c.outlineStrong, "focus" to c.focus,
    )
    Column(
        modifier.fillMaxWidth()
            .background(c.surfaceContainer, ClickerShapes.panel)
            .border(ClickerShapes.border, c.outline, ClickerShapes.panel)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (row in roles.chunked(3)) {
            Row {
                for ((name, color) in row) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Chip(color)
                        Spacer(Modifier.width(6.dp))
                        Text(name, style = ClickerType.status, color = c.onSurface)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ground", style = ClickerType.status, color = c.onSurface)
            Spacer(Modifier.width(6.dp))
            c.dotGround.forEach { Chip(it); Spacer(Modifier.width(4.dp)) }
            Spacer(Modifier.width(14.dp))
            Text("mark", style = ClickerType.status, color = c.onSurface)
            Spacer(Modifier.width(6.dp))
            c.dotMark.forEach { Chip(it); Spacer(Modifier.width(4.dp)) }
        }
    }
}

@Composable
private fun Chip(color: Color) {
    val c = LocalClickerColors.current
    Box(Modifier.size(22.dp).background(color, ClickerShapes.control).border(ClickerShapes.border, c.outlineStrong, ClickerShapes.control))
}

@Composable
private fun ButtonBlock(onPlaced: (String, Rect) -> Unit) {
    val rows = listOf(
        listOf<Any>("Back", Direction.Up, "Home"),
        listOf<Any>(Direction.Left, "OK", Direction.Right),
        listOf<Any>("Menu", Direction.Down, "Mute"),
    )
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        rows.forEachIndexed { i, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEachIndexed { j, item ->
                    Disc(item, 76.dp, Modifier.onGloballyPositioned { onPlaced("b$i$j", it.boundsInRoot()) })
                }
            }
        }
    }
}

@Composable
private fun Disc(item: Any, size: Dp, modifier: Modifier) {
    val c = LocalClickerColors.current
    val filled = item == "OK"
    val labelPadding = with(LocalDensity.current) { ClickerType.button.fontSize.toDp() } * (2 * ANEK_CAP_LIFT_EM)
    Box(
        modifier.size(size)
            .background(if (filled) c.accent else c.surfaceContainer, ClickerShapes.control)
            .border(ClickerShapes.border, if (filled) c.accent else c.outlineStrong, ClickerShapes.control),
        contentAlignment = Alignment.Center,
    ) {
        when (item) {
            is Direction -> DottedChevron(item, c.accent, Modifier.size(28.dp))
            is String -> Text(
                item,
                style = ClickerType.button,
                color = if (filled) c.onAccent else c.heading,
                modifier = Modifier.padding(top = labelPadding),
            )
        }
    }
}
