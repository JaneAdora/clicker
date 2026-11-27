package com.janeadora.clicker.debug

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.janeadora.clicker.core.FoundTv
import com.janeadora.clicker.core.LOG_TAG
import com.janeadora.clicker.core.Link
import com.janeadora.clicker.core.RemoteUiState
import com.janeadora.clicker.core.Settings
import com.janeadora.clicker.core.ThemeMode
import com.janeadora.clicker.ui.devices.DeviceSheet
import com.janeadora.clicker.ui.devices.DifferentTvDialog
import com.janeadora.clicker.ui.devices.ManualIpDialog
import com.janeadora.clicker.ui.devices.PairDialog
import com.janeadora.clicker.ui.devices.RemoveDialog
import com.janeadora.clicker.ui.devices.RenameDialog
import com.janeadora.clicker.ui.devices.Search
import com.janeadora.clicker.ui.devices.deviceRows
import com.janeadora.clicker.ui.remote.ProvideKeyFeedback
import com.janeadora.clicker.ui.remote.RemoteScreen
import com.janeadora.clicker.ui.theme.ClickerTheme
import uniffi.clicker_ffi.Device

/**
 * Debug builds only: the TV switcher and its dialogs with sample states,
 * for states the emulator cannot reach (found TVs, pairing).
 *
 *     adb shell am start -n com.janeadora.clicker/.debug.DevicesPreviewActivity --es screen sheet --es theme dark
 *
 * `screen` is sheet, firstrun, permission, settings, failed, pin, pinerror, pinaway,
 * ip, rename, remove or different. Nothing here reaches a TV; actions are
 * logged. In `pin`, a sent code stays at "Checking…": the preview never answers.
 */
class DevicesPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val screen = intent.getStringExtra("screen") ?: "sheet"
        val theme = when (intent.getStringExtra("theme")) {
            "light" -> ThemeMode.Light
            "dark" -> ThemeMode.Dark
            else -> ThemeMode.System
        }
        val log = { what: String -> Log.d(LOG_TAG, "devices preview $what") }
        setContent {
            ClickerTheme(theme) {
                val firstRun = screen in setOf("firstrun", "permission", "settings", "failed")
                var submitting by remember { mutableStateOf(false) }
                val state = if (firstRun) DeviceSamples.empty else DeviceSamples.twoTvs(if (screen.startsWith("pin")) Link.Pairing else Link.Connected)
                ProvideKeyFeedback(haptics = false, onNotConnected = {}) {
                    RemoteScreen(state, Settings(theme = theme), onAction = { true })
                }
                when (screen) {
                    "sheet", "firstrun", "permission", "settings", "failed" -> DeviceSheet(
                        rows = deviceRows(state.devices, state.activeId, if (firstRun) emptyList() else DeviceSamples.found, DeviceSamples.healed),
                        link = state.link,
                        search = when (screen) {
                            "permission", "settings" -> Search.NeedsPermission
                            "firstrun" -> Search.Looking
                            "failed" -> Search.Failed
                            else -> Search.NothingNew
                        },
                        notice = "Clicker needs local network access to reach your TV.",
                        allowLabel = if (screen == "settings") "Open settings" else "Allow access",
                        onPick = { log("pick ${it.name}") },
                        onRename = { log("rename") },
                        onRemove = { log("remove") },
                        onEnterIp = { log("enter ip") },
                        onAllow = { log("allow") },
                        onDismiss = { log("dismiss") },
                    )
                    // The code is never logged, here too.
                    "pin" -> PairDialog(error = null, submitting = submitting, onSubmit = {
                        submitting = true
                        log("code submitted")
                    }, onCancel = { log("cancel") })
                    "pinerror" -> PairDialog(
                        error = "pairing failed",
                        submitting = false,
                        onSubmit = { log("code submitted") },
                        onCancel = { log("cancel") },
                    )
                    "pinaway" -> PairDialog(
                        error = "pairing connect failed: TV not reachable",
                        submitting = false,
                        onSubmit = { log("code submitted") },
                        onCancel = { log("cancel") },
                        wrongCode = false,
                    )
                    "ip" -> ManualIpDialog(onAdd = { log("add") }, onCancel = { log("cancel") }, initial = "192.0.2.300")
                    "rename" -> RenameDialog("Living Room", onSave = { null }, onCancel = { log("cancel") })
                    "remove" -> RemoveDialog("Bedroom", null, onRemove = { log("remove") }, onCancel = { log("cancel") })
                    "different" -> DifferentTvDialog("192.0.2.10", onAdd = { log("add") }, onCancel = { log("cancel") })
                }
            }
        }
    }
}

/** Two saved TVs, one found TV that is saved (at a new address) and one that is new. */
object DeviceSamples {
    private val livingRoom = Device(id = "living-room", name = "Living Room", host = "192.0.2.40", paired = true, lastVolume = 12u)
    private val bedroom = Device(id = "bedroom", name = "Bedroom", host = "192.0.2.41", paired = true, lastVolume = null)

    val found = listOf(FoundTv("Living Room", "192.0.2.52"), FoundTv("Office TV", "192.0.2.60"))
    val healed = mapOf("Living Room" to livingRoom.id)

    val empty = RemoteUiState()

    fun twoTvs(link: Link) = RemoteUiState(
        link = link,
        devices = listOf(livingRoom, bedroom),
        activeId = livingRoom.id,
        tvName = livingRoom.name,
    )
}
