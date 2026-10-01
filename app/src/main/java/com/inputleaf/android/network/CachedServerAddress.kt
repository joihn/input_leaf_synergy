package com.inputleaf.android.network

import java.net.InetAddress

/** A routing hint bound to the certificate trusted for a saved hostname. */
data class CachedServerAddress(val address: String, val fingerprint: String) {
    fun matchesPin(pin: String?): Boolean = pin != null && runCatching {
        TlsFingerprintManager.normalizeFingerprint(fingerprint) == TlsFingerprintManager.normalizeFingerprint(pin)
    }.getOrDefault(false)

    fun numericAddress(): InetAddress? = runCatching {
        val parts = address.split('.')
        when {
            parts.size == 4 && parts.all { it.matches(Regex("[0-9]{1,3}")) && it.toInt() in 0..255 } ->
                InetAddress.getByAddress(parts.map { it.toInt().toByte() }.toByteArray())
            // A colon forces literal IPv6 parsing. Do not persist interface-scoped addresses:
            // scope identifiers can refer to a different interface after changing networks.
            ':' in address && address.matches(Regex("[0-9a-fA-F:.]+")) ->
                InetAddress.getByName(address).takeUnless { it.isLinkLocalAddress }
            else -> null
        }
    }.getOrNull()

    companion object {
        fun isHostname(host: String): Boolean = ':' !in host && host.any { it.isLetter() }

        fun verified(address: String, fingerprint: String): CachedServerAddress? = runCatching {
            CachedServerAddress(address, TlsFingerprintManager.normalizeFingerprint(fingerprint))
                .takeIf { it.numericAddress() != null }
        }.getOrNull()
    }
}
