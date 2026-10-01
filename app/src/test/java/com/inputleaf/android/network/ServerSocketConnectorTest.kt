package com.inputleaf.android.network

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.UnknownHostException

class ServerSocketConnectorTest {
    private val ipv6 = InetAddress.getByName("::1")
    private val ipv4 = InetAddress.getByName("127.0.0.1")

    @Test fun `IPv6 refusal closes that socket and tries IPv4 with a fresh socket`() {
        val failed = TestSocket(refuse = true)
        val accepted = TestSocket(refuse = false)
        val sockets = ArrayDeque(listOf(failed, accepted))
        val result = ServerSocketConnector.connect("mac.local", 24800, 800, { arrayOf(ipv6, ipv4) }) {
            if (sockets.size == 1) assertThat(failed.wasClosed).isTrue()
            sockets.removeFirst()
        }
        assertThat(result).isSameInstanceAs(accepted)
        assertThat(failed.destination).isEqualTo(InetSocketAddress(ipv6, 24800))
        assertThat(accepted.destination).isEqualTo(InetSocketAddress(ipv4, 24800))
        assertThat(accepted.timeout).isEqualTo(800)
        assertThat(accepted.wasClosed).isFalse()
    }

    @Test fun `successful first address does not contact other endpoints`() {
        var created = 0
        ServerSocketConnector.connect("mac.local", 24800, 800, { arrayOf(ipv6, ipv4) }) {
            created++
            TestSocket(refuse = false)
        }
        assertThat(created).isEqualTo(1)
    }

    @Test fun `all failed connections close their sockets and report failure`() {
        val sockets = mutableListOf<TestSocket>()
        assertThrows(ConnectException::class.java) {
            ServerSocketConnector.connect("mac.local", 24800, 800, { arrayOf(ipv6, ipv4) }) {
                TestSocket(refuse = true).also { sockets += it }
            }
        }
        assertThat(sockets).hasSize(2)
        assertThat(sockets.all { it.wasClosed }).isTrue()
    }

    @Test fun `empty DNS answer fails without creating a socket`() {
        assertThrows(UnknownHostException::class.java) {
            ServerSocketConnector.connect("missing.local", 24800, 800, { emptyArray() }) {
                throw AssertionError("Socket must not be created without an address")
            }
        }
    }

    private class TestSocket(private val refuse: Boolean) : Socket() {
        var wasClosed = false
        var destination: SocketAddress? = null
        var timeout = 0
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            destination = endpoint
            this.timeout = timeout
            if (refuse) throw ConnectException("Connection refused")
        }
        override fun close() { wasClosed = true }
    }
}
