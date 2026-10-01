package com.inputleaf.android.network

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

/** Try every address for a hostname; an IPv6 result may lead to an IPv4-only server. */
internal object ServerSocketConnector {
    fun <T : Socket> connect(
        host: String,
        port: Int,
        timeoutMs: Int,
        resolve: (String) -> Array<InetAddress> = InetAddress::getAllByName,
        createSocket: () -> T,
    ): T {
        val addresses = resolve(host)
        if (addresses.isEmpty()) throw UnknownHostException(host)
        var failure: IOException? = null
        for (address in addresses) {
            val socket = createSocket()
            try {
                socket.connect(InetSocketAddress(address, port), timeoutMs)
                return socket
            } catch (error: IOException) {
                runCatching { socket.close() }
                failure?.let { error.addSuppressed(it) }
                failure = error
            } catch (error: Exception) {
                runCatching { socket.close() }
                throw error
            }
        }
        throw checkNotNull(failure)
    }
}
