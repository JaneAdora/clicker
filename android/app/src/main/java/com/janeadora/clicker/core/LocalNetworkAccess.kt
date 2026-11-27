package com.janeadora.clicker.core

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What the local network notice offers. */
enum class LocalNetworkAction { Ask, OpenSettings }

/**
 * The notice's action, or null when access is [granted]. Android reports
 * no rationale both before the first prompt and once it will not prompt
 * again, so only a [refused] request with no rationale ([canAskAgain]
 * false) means the prompt is gone and the app's settings page is the way.
 */
fun localNetworkAction(granted: Boolean, refused: Boolean, canAskAgain: Boolean): LocalNetworkAction? = when {
    granted -> null
    refused && !canAskAgain -> LocalNetworkAction.OpenSettings
    else -> LocalNetworkAction.Ask
}

/**
 * Android 17's (API 37) local network permission, for [activity]. Build it
 * while the activity is constructed: it registers the permission prompt.
 *
 * [isDenied] and [storeDenied] read and keep the stored refusal, so a
 * relaunch shows the notice instead of prompting. [onGranted] runs after
 * the prompt grants access, with the connect that waited on it (a device
 * id or the caller's marker for the last TV), or null when none did.
 */
class LocalNetworkAccess(
    private val activity: ComponentActivity,
    private val isDenied: () -> Boolean,
    private val storeDenied: (Boolean) -> Unit,
    private val onGranted: (String?) -> Unit,
) {
    /** False while access is missing; the TV switcher then says why. */
    var granted by mutableStateOf(true)
        private set

    /** What the notice offers while access is missing. */
    var action by mutableStateOf(LocalNetworkAction.Ask)
        private set

    /** A connect waiting on the prompt. Kept across recreation by [save] and [restore]. */
    var pending: String? = null
        private set

    // The last prompt came back refused; until then no rationale means "never asked".
    private var refused = false

    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val waiting = pending
        pending = null
        refused = !ok
        refresh()
        if (ok) onGranted(waiting)
    }

    /** True when the permission exists on this Android and is not held. */
    fun needed(): Boolean =
        Build.VERSION.SDK_INT >= 37 &&
            activity.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED

    /** Re-reads the system (on start, on return from settings, after a prompt). */
    fun refresh() {
        val needed = needed()
        granted = !needed
        if (!needed) refused = false
        val canAskAgain = Build.VERSION.SDK_INT >= 37 &&
            activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_LOCAL_NETWORK)
        // A refusal stored by an earlier run counts too, so a cold start knows the prompt may be gone.
        action = localNetworkAction(!needed, refused || isDenied(), canAskAgain) ?: LocalNetworkAction.Ask
        if (isDenied() != needed && (!needed || refused)) storeDenied(needed)
    }

    /**
     * Runs [connect] at once when access is held. Otherwise asks, and
     * [onGranted] gets [waiting] if the answer is yes.
     */
    fun request(waiting: String?, connect: () -> Unit) {
        if (needed()) {
            pending = waiting
            launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        } else {
            refresh()
            connect()
        }
    }

    /** The notice's button: ask, or open the app's settings page when Android will not ask again. */
    fun allow() {
        refresh()
        if (action == LocalNetworkAction.OpenSettings) openSettings() else request(null) {}
    }

    private fun openSettings() {
        val intent = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null))
        activity.startActivity(intent)
    }

    fun save(out: Bundle) {
        out.putString(KEY_PENDING, pending)
        out.putBoolean(KEY_REFUSED, refused)
    }

    fun restore(saved: Bundle?) {
        pending = saved?.getString(KEY_PENDING)
        refused = saved?.getBoolean(KEY_REFUSED) ?: false
    }

    private companion object {
        const val KEY_PENDING = "pendingConnect"
        const val KEY_REFUSED = "localNetworkRefused"
    }
}
