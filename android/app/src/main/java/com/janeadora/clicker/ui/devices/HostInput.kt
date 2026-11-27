package com.janeadora.clicker.ui.devices

private val HOSTNAME = Regex("[A-Za-z0-9.-]{1,253}")
private val LABEL = Regex("[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
private val OCTET = Regex("0|[1-9][0-9]{0,2}")

/**
 * The address typed into "Enter IP address", trimmed: an IPv4 dotted quad
 * or a hostname such as `tv.local`. Null for anything else, including
 * URLs, ports and IPv6 literals. A trailing dot is dropped.
 */
fun normalizeHost(raw: String): String? {
    val h = raw.trim().removeSuffix(".")
    if (!HOSTNAME.matches(h)) return null
    // Digits and dots only: it has to be a whole, valid IPv4 address.
    if (h.all { it.isDigit() || it == '.' }) return h.takeIf { isIpv4(it) }
    return h.takeIf { it.split('.').all { label -> LABEL.matches(label) } }
}

private fun isIpv4(h: String): Boolean {
    val parts = h.split('.')
    return parts.size == 4 && parts.all { OCTET.matches(it) && it.toInt() <= 255 }
}
