package com.janeadora.clicker.ui.devices

import com.janeadora.clicker.core.FoundTv
import uniffi.clicker_ffi.Device

/**
 * One line of the TV switcher. Saved TVs have an [id]; a TV found on the
 * network and not saved yet has none. [serviceName] is the discovery name
 * when known.
 */
data class DeviceRow(
    val id: String?,
    val name: String,
    val host: String,
    val saved: Boolean,
    val active: Boolean,
    val serviceName: String?,
)

/**
 * The rows of the TV switcher, in order: saved TVs (the active one first),
 * then found TVs that are not saved. The one place that decides rows, so a
 * physical TV is never listed twice.
 *
 * [healed] maps a found service name to the saved id the core matched it
 * to (`noteDiscovered`). A found TV the core matched, or one at an address
 * a saved TV already has, is that saved TV and gets no row of its own. A
 * found TV with a saved TV's name but no match is new. Saved rows always
 * show the saved address: discovery never changes it here.
 */
fun deviceRows(saved: List<Device>, activeId: String?, found: List<FoundTv>, healed: Map<String, String>): List<DeviceRow> {
    val savedIds = saved.mapTo(HashSet()) { it.id }
    val serviceOf = healed.entries.filter { it.value in savedIds }.associate { it.value to it.key }
    val savedRows = saved
        .sortedByDescending { it.id == activeId } // stable: the rest keep their order
        .map { DeviceRow(it.id, it.name, it.host, saved = true, active = it.id == activeId, serviceName = serviceOf[it.id]) }
    val takenHosts = saved.mapTo(HashSet()) { it.host }
    val seenNames = HashSet<String>()
    val newRows = found
        .filter { healed[it.serviceName] !in savedIds }
        .filter { takenHosts.add(it.host) && seenNames.add(it.serviceName) }
        .map { DeviceRow(null, it.serviceName, it.host, saved = false, active = false, serviceName = it.serviceName) }
    return savedRows + newRows
}

/**
 * Whether the switcher opens by itself when the app starts: only on first
 * run (no saved TV), and only once. [alreadyDecided] is true once the
 * question has been asked, so a rotation, a resume or a dismissed sheet
 * never brings it back.
 */
fun openOnStart(alreadyDecided: Boolean, hasDevices: Boolean, startupFailed: Boolean): Boolean =
    !alreadyDecided && !hasDevices && !startupFailed
