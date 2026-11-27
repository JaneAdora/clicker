package com.janeadora.clicker.ui.devices

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.janeadora.clicker.core.Link
import com.janeadora.clicker.ui.remote.ButtonLabel
import com.janeadora.clicker.ui.remote.SHEET_SHAPE
import com.janeadora.clicker.ui.remote.SheetBarsFollowTheme
import com.janeadora.clicker.ui.theme.ClickerShapes
import com.janeadora.clicker.ui.theme.ClickerType
import com.janeadora.clicker.ui.theme.DarkColors
import com.janeadora.clicker.ui.theme.LocalClickerColors

/** What the "On this network" section says when it has no new TV to list. */
enum class Search {
    /** Still looking, in the first seconds. */
    Looking,

    /** Looked for a while and found nothing at all. */
    NothingFound,

    /** Found TVs, but every one is already saved. */
    NothingNew,

    /** Discovery cannot run without the local network permission. */
    NeedsPermission,

    /** Discovery could not start. */
    Failed,
}

const val LOOKING = "Looking for TVs…"
const val NOTHING_FOUND = "No TVs found. Check that your phone and TV are on the same Wi-Fi."
const val NOTHING_NEW = "No other TVs found."
const val SEARCH_FAILED = "Could not search for TVs. You can still enter an IP address."

/**
 * The TV switcher: saved TVs, the current one first and marked
 * with the accent dot, then TVs found on the network, then "Enter IP
 * address". Renders [rows] as given; [deviceRows] decides them. A tap picks
 * a row; a long press on a saved TV shows Rename and Remove under it.
 * [link] is the current link, shown beside the current TV.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSheet(
    rows: List<DeviceRow>,
    link: Link,
    search: Search,
    notice: String?,
    allowLabel: String,
    onPick: (DeviceRow) -> Unit,
    onRename: (DeviceRow) -> Unit,
    onRemove: (DeviceRow) -> Unit,
    onEnterIp: () -> Unit,
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalClickerColors.current
    val saved = rows.filter { it.saved }
    val found = rows.filter { !it.saved }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = SHEET_SHAPE,
        containerColor = c.surfaceContainer,
        contentColor = c.onSurface,
        tonalElevation = 0.dp,
        scrimColor = c.surface.copy(alpha = 0.7f),
        // No handle: the sheet drags by its body and closes from the scrim or Back.
        dragHandle = null,
        properties = ModalBottomSheetProperties(
            isAppearanceLightStatusBars = c != DarkColors,
            isAppearanceLightNavigationBars = c != DarkColors,
        ),
    ) {
        SheetBarsFollowTheme(light = c != DarkColors)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (saved.isEmpty()) {
                Text("Add a TV", style = ClickerType.title, color = c.heading)
                Text(
                    "Turn the TV on, then pick it below or enter its IP address.",
                    style = ClickerType.body.copy(lineBreak = LineBreak.Paragraph),
                    color = c.muted,
                )
            } else {
                Text("TVs", style = ClickerType.title, color = c.heading)
                Column {
                    saved.forEachIndexed { i, row ->
                        if (i > 0) Hairline()
                        TvRow(row, link, expanded = expanded == row.id, onClick = { onPick(row) }, onLongClick = {
                            expanded = if (expanded == row.id) null else row.id
                        }, onRename = { expanded = null; onRename(row) }, onRemove = { expanded = null; onRemove(row) })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("On this network", style = ClickerType.button, color = c.heading)
            if (found.isNotEmpty()) {
                Column {
                    found.forEachIndexed { i, row ->
                        if (i > 0) Hairline()
                        TvRow(row, link, expanded = false, onClick = { onPick(row) })
                    }
                }
            } else if (search == Search.NeedsPermission && notice != null) {
                Text(notice, style = ClickerType.body.copy(lineBreak = LineBreak.Heading), color = c.muted)
                DialogButton(allowLabel, onAllow)
            } else {
                Text(
                    when (search) {
                        Search.Looking -> LOOKING
                        Search.NothingNew -> NOTHING_NEW
                        Search.Failed -> SEARCH_FAILED
                        else -> NOTHING_FOUND
                    },
                    Modifier.heightIn(min = 48.dp).padding(vertical = 4.dp),
                    style = ClickerType.body.copy(lineBreak = LineBreak.Heading),
                    color = c.muted,
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .clip(ClickerShapes.control)
                    .border(ClickerShapes.border, c.outlineStrong, ClickerShapes.control)
                    .clickable(role = Role.Button, onClick = onEnterIp),
                contentAlignment = Alignment.Center,
            ) {
                ButtonLabel("Enter IP address", color = c.heading)
            }
        }
    }
}

@Composable
private fun Hairline() {
    Box(Modifier.fillMaxWidth().height(ClickerShapes.border).background(LocalClickerColors.current.outline))
}

/** What the current TV's row says about the link, or null. */
private fun linkWord(link: Link): String? = when (link) {
    Link.Connected -> "Connected"
    Link.Connecting -> "Connecting…"
    Link.Pairing -> "Pairing…"
    Link.Unreachable -> "Not reachable"
    Link.NoWifi -> "No Wi-Fi"
    Link.Idle -> null
}

/**
 * One TV: a dot (accent when current), the name in Anek and, when it
 * differs from the name, the address in Fira Code. A found TV says "Add" at the end. A saved TV has Rename and
 * Remove, shown under it after a long press and offered to TalkBack as
 * actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvRow(
    row: DeviceRow,
    link: Link,
    expanded: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onRename: () -> Unit = {},
    onRemove: () -> Unit = {},
) {
    val c = LocalClickerColors.current
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .clip(ClickerShapes.field)
                .combinedClickable(
                    onClickLabel = if (row.saved) "Connect" else "Add and connect",
                    onLongClickLabel = if (onLongClick != null) "Rename or remove" else null,
                    onLongClick = onLongClick,
                    onClick = onClick,
                    role = Role.Button,
                )
                .semantics(mergeDescendants = true) {
                    if (row.active) stateDescription = "Current TV"
                    if (row.saved) {
                        customActions = listOf(
                            CustomAccessibilityAction("Rename") { onRename(); true },
                            CustomAccessibilityAction("Remove") { onRemove(); true },
                        )
                    }
                }
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val dot = Modifier.size(10.dp).clip(ClickerShapes.control)
            when {
                row.active -> Box(dot.background(c.accent))
                row.saved -> Box(dot.border(1.5.dp, c.outlineStrong, ClickerShapes.control))
                else -> Spacer(Modifier.size(10.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(row.name, style = ClickerType.button, color = c.heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // A TV added by address is named by it until renamed; no need to say it twice.
                if (row.host != row.name) {
                    Text(row.host, style = ClickerType.status, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val trailing = if (row.saved) linkWord(link).takeIf { row.active } else "Add"
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                Text(trailing, style = if (row.saved) ClickerType.status else ClickerType.label, color = if (row.saved) c.muted else c.accent)
            }
        }
        if (expanded) {
            Row(Modifier.padding(start = 28.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton("Rename", onRename)
                DialogButton("Remove", onRemove)
            }
        }
    }
}
