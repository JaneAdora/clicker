package com.janeadora.clicker

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.janeadora.clicker.core.Discovery
import com.janeadora.clicker.core.LOG_TAG
import com.janeadora.clicker.core.Link
import com.janeadora.clicker.core.LocalNetworkAccess
import com.janeadora.clicker.core.LocalNetworkAction
import com.janeadora.clicker.core.Prefs
import com.janeadora.clicker.core.RemoteRepository
import com.janeadora.clicker.core.Settings
import com.janeadora.clicker.core.WifiMonitor
import com.janeadora.clicker.core.linkFor
import com.janeadora.clicker.core.wantsConnect
import com.janeadora.clicker.ui.devices.DevicesHost
import com.janeadora.clicker.ui.remote.HardwareAction
import com.janeadora.clicker.ui.remote.HardwareKeyRouter
import com.janeadora.clicker.ui.remote.ProvideKeyFeedback
import com.janeadora.clicker.ui.remote.debugLog
import com.janeadora.clicker.ui.remote.REJECT_HAPTIC
import com.janeadora.clicker.ui.remote.playHaptic
import com.janeadora.clicker.ui.remote.MoreSheet
import com.janeadora.clicker.ui.remote.RemoteAction
import com.janeadora.clicker.ui.remote.RemoteScreen
import com.janeadora.clicker.ui.shortcuts.ShortcutsHost
import com.janeadora.clicker.ui.theme.ClickerTheme
import com.janeadora.clicker.ui.theme.DarkColors
import com.janeadora.clicker.ui.theme.LocalClickerColors
import com.janeadora.clicker.ui.typing.TypingHost
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The remote screen, wired to the repository and the stored settings.
 * Connects while started and disconnects when stopped (not on rotation),
 * follows Wi-Fi coming and going, and takes the phone's volume keys and a
 * physical keyboard.
 */
class MainActivity : ComponentActivity() {
    private val app: ClickerApp get() = application as ClickerApp
    private val repository: RemoteRepository by lazy { app.repository }
    private val wifi: WifiMonitor by lazy { app.wifi }
    private val prefs: Prefs by lazy { Prefs(this) }
    private val discovery: Discovery by lazy { Discovery(this) }

    // Bumped when a press finds no TV; the status line pulses once per bump.
    private var pulse by mutableIntStateOf(0)

    // The latest stored settings, for the key router and auto-connect.
    private var settings = Settings()

    // True while an on-screen control (Retry, a shortcut tile, the TV
    // switcher) has keyboard focus; remote keys never take focus.
    private var controlFocused = false

    private val keys = HardwareKeyRouter({ settings }, ::typing, { controlFocused }, ::perform)

    // Android 17's local network permission. A connect that waited on the
    // prompt runs once it is granted: a saved TV's id, PENDING_LAST for the
    // last used TV, or none (the notice's own button), which auto-connects.
    private val access = LocalNetworkAccess(this, { settings.localNetworkDenied }, ::rememberDenied) { waiting ->
        when (waiting) {
            null -> autoConnect()
            PENDING_LAST -> repository.connectLast()
            else -> repository.connect(waiting)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        access.restore(savedInstanceState)
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                // Fresh Wi-Fi state before any collector restarts, so the
                // screen never shows a value from before it was stopped.
                if (event == Lifecycle.Event.ON_START) wifi.refresh()
                // Also on return from the app's settings page, where access may have changed.
                if (event == Lifecycle.Event.ON_RESUME) access.refresh()
                // A rotation stops and restarts the activity; the connection stays.
                if (event == Lifecycle.Event.ON_STOP) {
                    if (isChangingConfigurations) recreating = true else repository.disconnect()
                }
            },
        )
        lifecycleScope.launch { prefs.settings.collect { settings = it } }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    // The stored denial must be known before deciding to prompt.
                    settings = prefs.settings.first()
                    access.refresh()
                    // After a rotation the link is left as it was, even mid-retry.
                    val rotated = recreating
                    recreating = false
                    if (!rotated && wifi.isConnected()) autoConnect()
                    // The first value is the one held from before; only changes count.
                    wifi.wifiConnected.drop(1).collect { up -> if (up) autoConnect() else repository.disconnect() }
                }
                launch {
                    combine(repository.state.map { it.link }, wifi.wifiConnected) { link, up -> linkFor(up, link) }
                        .distinctUntilChanged()
                        .collect { Log.i(LOG_TAG, "link $it") }
                }
            }
        }
        setContent {
            // Until the stored settings load, the window's own ground shows.
            val settings = prefs.settings.collectAsStateWithLifecycle(initialValue = null).value
                ?: return@setContent
            ClickerTheme(settings.theme) {
                SystemBarsFollowTheme()
                Remote(settings)
            }
        }
    }

    @Composable
    private fun Remote(settings: Settings) {
        val core by repository.state.collectAsStateWithLifecycle()
        val wifiUp by wifi.wifiConnected.collectAsStateWithLifecycle()
        val state = core.copy(link = linkFor(wifiUp, core.link))
        var showMore by rememberSaveable { mutableStateOf(false) }
        var showDevices by rememberSaveable { mutableStateOf(false) }
        // One provider over the screen and the sheet, so both follow Haptics.
        ProvideKeyFeedback(settings.haptics, onNotConnected = { pulse++ }) {
            ShortcutsHost(state, repository, app.shortcuts) { pictures, editShortcut ->
                TypingHost(state, repository::setText, repository::submitText) { typingBar, openTyping ->
                    Box(Modifier.onFocusChanged { controlFocused = it.hasFocus }) {
                        RemoteScreen(
                            state = state,
                            settings = settings,
                            notice = if (settings.localNetworkDenied) LOCAL_NETWORK_NOTICE else null,
                            noticeOpensSettings = access.action == LocalNetworkAction.OpenSettings,
                            pulse = pulse,
                            typingBar = typingBar,
                            pictures = pictures,
                            onAction = { action ->
                                when (action) {
                                    is RemoteAction.Press -> repository.key(action.key)
                                    is RemoteAction.Launch -> repository.launch(action.slot)
                                    RemoteAction.OpenMore -> { showMore = true; true }
                                    RemoteAction.OpenDevices -> { showDevices = true; true }
                                    RemoteAction.OpenTyping -> { openTyping(); true }
                                    RemoteAction.Retry -> { retry(); true }
                                    is RemoteAction.EditShortcut -> { editShortcut(action.slot, action.tile); true }
                                }
                            },
                        )
                    }
                }
            }
            if (showMore) {
                MoreSheet(
                    settings = settings,
                    connected = state.link == Link.Connected,
                    onPress = { repository.key(it) },
                    onSettingsChange = { edit -> lifecycleScope.launch { prefs.update(edit) } },
                    onDismiss = { showMore = false },
                    onVolumeKey = ::dispatchHardwareKey,
                )
            }
        }
        DevicesHost(
            state = state,
            repository = repository,
            discovery = discovery,
            open = showDevices,
            onOpenChange = { showDevices = it },
            canDiscover = access.granted,
            notice = LOCAL_NETWORK_NOTICE,
            allowLabel = if (access.action == LocalNetworkAction.OpenSettings) "Open settings" else "Allow access",
            onAllowLocalNetwork = access::allow,
            onConnect = ::requestConnect,
        )
    }

    // Keys are taken here, before the views see them: otherwise Compose
    // would use the arrows and Enter to move focus between the buttons.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = dispatchHardwareKey(event) || super.dispatchKeyEvent(event)

    private fun dispatchHardwareKey(event: KeyEvent): Boolean = when (event.action) {
        KeyEvent.ACTION_DOWN ->
            keys.down(event.keyCode, event.repeatCount, chord = event.isCtrlPressed || event.isAltPressed || event.isMetaPressed)
        KeyEvent.ACTION_UP -> keys.up(event.keyCode)
        else -> false
    }

    // A text field with focus has an input connection open.
    private fun typing(): Boolean = getSystemService(InputMethodManager::class.java).isAcceptingText

    // With no TV, a physical press buzzes and pulses once, not on every repeat.
    private fun perform(action: HardwareAction, first: Boolean) {
        debugLog(this, "hardware $action first=$first")
        if (repository.state.value.link != Link.Connected) {
            if (first) {
                playHaptic(window.decorView, REJECT_HAPTIC, settings.haptics)
                pulse++
            }
            return
        }
        when (action) {
            is HardwareAction.Press -> repository.key(action.key)
            is HardwareAction.Launch -> repository.launch(action.slot)
        }
    }

    // Status and navigation bar icons follow the app's theme, which can
    // differ from the phone's when the user picks Light or Dark under More.
    @Composable
    private fun SystemBarsFollowTheme() {
        val dark = LocalClickerColors.current == DarkColors
        DisposableEffect(dark) {
            val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
            enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            onDispose {}
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        access.save(outState)
    }

    /** Connects to the saved TV [id], from the TV switcher. */
    private fun requestConnect(id: String) = access.request(id) { repository.connect(id) }

    private fun requestConnectLast() = access.request(PENDING_LAST) { repository.connectLast() }

    // Retry, or a tap on the notice: once Android will not ask again, the settings page is the way.
    private fun retry() = if (access.needed()) access.allow() else requestConnectLast()

    // Connects on start and when Wi-Fi returns. With no saved TV there is
    // nothing to ask permission for; after a refusal the notice shows and
    // the prompt waits for Retry instead of coming back on every start.
    private fun autoConnect() {
        val s = repository.state.value
        if (s.activeId == null || !wantsConnect(s.link)) return
        if (access.needed() && (settings.localNetworkDenied || access.pending != null)) return
        requestConnectLast()
    }

    // Keeps the refusal in Prefs, so a relaunch shows the notice instead of
    // prompting again. Cleared once the permission is granted.
    private fun rememberDenied(denied: Boolean) {
        if (settings.localNetworkDenied == denied) return
        settings = settings.copy(localNetworkDenied = denied)
        lifecycleScope.launch { prefs.update { it.copy(localNetworkDenied = denied) } }
    }

    private companion object {
        // Set while the activity is being rebuilt for a configuration change.
        // Process-wide, so it is false again after the process is killed.
        var recreating = false

        // Never a real device id: the core makes ids from names, never empty.
        const val PENDING_LAST = ""
        const val LOCAL_NETWORK_NOTICE = "Clicker needs local network access to reach your TV."
    }
}
