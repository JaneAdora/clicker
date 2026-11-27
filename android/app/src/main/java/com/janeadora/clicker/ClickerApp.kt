package com.janeadora.clicker

import android.app.Application
import com.janeadora.clicker.core.RemoteRepository
import com.janeadora.clicker.core.ShortcutImages
import com.janeadora.clicker.core.WifiMonitor
import com.janeadora.clicker.ui.shortcuts.ShortcutStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Holds the one RemoteRepository, the one WifiMonitor and the shortcut
 * pictures for the process lifetime, so none is rebuilt when the activity
 * is. The Rust Remote inside the repository is freed when the process ends;
 * the monitor's system callback is only registered while the remote screen
 * is started.
 */
class ClickerApp : Application() {
    lateinit var repository: RemoteRepository
        private set
    lateinit var wifi: WifiMonitor
        private set
    lateinit var shortcuts: ShortcutStore
        private set

    override fun onCreate() {
        super.onCreate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        repository = RemoteRepository(filesDir, scope)
        wifi = WifiMonitor(this, scope)
        // Looked up now, so the first frame already knows which tiles have a picture.
        shortcuts = ShortcutStore(ShortcutImages(File(filesDir, "shortcut-icons")), scope).also { it.refresh() }
    }
}
