package com.janeadora.clicker.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.janeadora.clicker.ClickerApp

/**
 * Debug builds only: saves a TV and makes it the last used one, so the
 * lifecycle and Wi-Fi checks can run without a real TV.
 *
 *     adb shell am broadcast -n com.janeadora.clicker/.debug.AddDeviceReceiver --es host 192.0.2.10
 *
 * The connect it starts goes around the local network permission prompt.
 * `--ez remove true` removes the test TVs it saved instead.
 */
class AddDeviceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val repository = (context.applicationContext as ClickerApp).repository
        if (intent.getBooleanExtra("remove", false)) {
            repository.state.value.devices.filter { it.name == NAME }.forEach { repository.removeDevice(it.id) }
            return
        }
        val host = intent.getStringExtra("host")?.trim().orEmpty()
        if (host.isEmpty()) return
        val device = repository.addDevice(name = NAME, host = host, serviceName = null) ?: return
        repository.connect(device.id)
    }

    private companion object {
        const val NAME = "Test TV"
    }
}
