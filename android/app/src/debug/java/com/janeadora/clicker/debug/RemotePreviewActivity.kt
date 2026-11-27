package com.janeadora.clicker.debug

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import com.janeadora.clicker.core.DpadStyle
import com.janeadora.clicker.core.LOG_TAG
import com.janeadora.clicker.core.Link
import com.janeadora.clicker.core.Settings
import com.janeadora.clicker.core.ThemeMode
import com.janeadora.clicker.ui.remote.HardwareKeyRouter
import com.janeadora.clicker.ui.remote.MoreSheet
import com.janeadora.clicker.ui.remote.ProvideKeyFeedback
import com.janeadora.clicker.ui.remote.RemoteAction
import com.janeadora.clicker.ui.remote.RemoteScreen
import com.janeadora.clicker.ui.theme.ClickerTheme
import com.janeadora.clicker.ui.typing.TypingHost

/**
 * Debug builds only: the remote screen with a sample state, no TV needed.
 *
 *     adb shell am start -n com.janeadora.clicker/.debug.RemotePreviewActivity \
 *         --es state unreachable --ez more true --ez notice true --es dpad swipe --es theme light
 *
 * `state` is one of [RemoteSamples.byName] (default connected). `more` opens
 * the More sheet; `notice` shows the missing local network access state
 * (with `settings true`, as when Android will no longer ask); `dpad swipe`
 * shows the swipe pad; `theme` is light or dark (default system). Settings changed
 * in the sheet live in memory only.
 *
 * The type bar works as in the app, sending nothing (the host logs
 * "text sent, length N"). To change the sample while it runs, start it
 * again with `--activity-single-top`: `state` swaps the sample and
 * `field true` or `field false` says whether the TV has a text field active.
 * Physical keys go through the app's key router and are logged as
 * "preview hardware ...", with the router's view of typing as in the app.
 */
class RemotePreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // After a rotation the sample is the one last set, not the first intent's.
        readSample(savedInstanceState?.getBundle("sample")?.let { Intent().putExtras(it) } ?: intent)
        val openMore = intent.getBooleanExtra("more", false)
        val notice = if (intent.getBooleanExtra("notice", false)) {
            "Clicker needs local network access to reach your TV."
        } else {
            null
        }
        val opensSettings = intent.getBooleanExtra("settings", false)
        setContent {
            var settings by remember { mutableStateOf(initialSettings()) }
            var showMore by rememberSaveable { mutableStateOf(openMore) }
            var pulse by remember { mutableIntStateOf(0) }
            ClickerTheme(settings.theme) {
                ProvideKeyFeedback(settings.haptics, onNotConnected = { pulse++ }) {
                    TypingHost(state, onSetText = {}, onSubmitText = {}) { typingBar, openTyping ->
                        RemoteScreen(state, settings, notice = notice, noticeOpensSettings = opensSettings, pulse = pulse, typingBar = typingBar, onAction = { action ->
                            // Logged so gesture checks can see which key a press sent.
                            Log.d(LOG_TAG, "preview $action")
                            if (action == RemoteAction.OpenMore) showMore = true
                            if (action == RemoteAction.OpenTyping) openTyping()
                            true
                        })
                    }
                    if (showMore) {
                        MoreSheet(
                            settings = settings,
                            connected = state.link == Link.Connected,
                            onPress = { Log.d(LOG_TAG, "preview sheet $it"); true },
                            onSettingsChange = { edit -> settings = edit(settings) },
                            onDismiss = { showMore = false },
                        )
                    }
                }
            }
        }
    }

    private var state by mutableStateOf(RemoteSamples.connected)

    private val keys = HardwareKeyRouter({ Settings() }, { getSystemService(InputMethodManager::class.java).isAcceptingText }, { false }) { action, first ->
        Log.d(LOG_TAG, "preview hardware $action first=$first")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val taken = when (event.action) {
            KeyEvent.ACTION_DOWN -> keys.down(event.keyCode, event.repeatCount)
            KeyEvent.ACTION_UP -> keys.up(event.keyCode)
            else -> false
        }
        return taken || super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readSample(intent)
    }

    private var sampleName: String? = null

    private fun readSample(intent: Intent) {
        if (intent.hasExtra("state")) sampleName = intent.getStringExtra("state")
        val base = RemoteSamples.byName[sampleName] ?: RemoteSamples.connected
        val field = if (intent.hasExtra("field")) intent.getBooleanExtra("field", false) else state.textFieldActive
        // A field reported active is one more keyboard request, as from the TV.
        val shows = state.textFieldShows + if (intent.hasExtra("field") && field) 1 else 0
        state = base.copy(textFieldActive = field, textFieldShows = shows)
        Log.d(LOG_TAG, "preview sample ${base.link} field=$field")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBundle("sample", Bundle().apply {
            putString("state", sampleName)
            putBoolean("field", state.textFieldActive)
        })
    }

    private fun initialSettings() = Settings(
        theme = when (intent.getStringExtra("theme")) {
            "light" -> ThemeMode.Light
            "dark" -> ThemeMode.Dark
            else -> ThemeMode.System
        },
        dpad = if (intent.getStringExtra("dpad") == "swipe") DpadStyle.Swipe else DpadStyle.Buttons,
    )
}
