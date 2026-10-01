package com.inputleaf.android.network

import com.google.common.truth.Truth.assertThat
import com.inputleaf.android.testutil.LoopbackServer
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException

class CachedHostnameConnectionTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test fun `a new connection reuses verified IP without any hostname resolution`() = runBlocking {
        val identity = TestTlsIdentity.create()
        val pin = TlsFingerprintManager.fingerprintOf(identity.certificate)
        TlsLoopbackServer(identity.context, connectionCount = 2) { socket, _ ->
            socket.startHandshake()
            performServerHandshake(socket)
        }.use { server ->
            val first = InputLeapConnection(
                "mac-a.local", server.port, transportPolicy = ConnectionTransportPolicy.TLS_ONLY,
                resolveAddresses = { arrayOf(loopback) }, onCertificate = { true },
            )
            val cached = try {
                assertThat(first.connect("android", 1920, 1080)).isInstanceOf(ConnectResult.Ok::class.java)
                checkNotNull(first.verifiedServerAddress).also {
                    assertThat(it).isEqualTo(CachedServerAddress("127.0.0.1", pin))
                }
            } finally { first.close() }
            val next = InputLeapConnection(
                "mac-a.local", server.port, pinnedFingerprint = pin, cachedAddress = cached,
                resolveAddresses = { throw AssertionError("Bonjour must not be needed for a working cache") },
                onCertificate = { throw AssertionError("Cached identity must be pinned, not prompted") },
            )
            try {
                assertThat(next.connect("android", 1920, 1080)).isInstanceOf(ConnectResult.Ok::class.java)
                assertThat(next.verifiedServerAddress).isEqualTo(cached)
            } finally { next.close() }
        }
    }

    @Test fun `failed old IP resolves the hostname and remembers its new verified address`() = runBlocking {
        val identity = TestTlsIdentity.create()
        val pin = TlsFingerprintManager.fingerprintOf(identity.certificate)
        var resolutions = 0
        TlsLoopbackServer(identity.context) { socket, _ ->
            socket.startHandshake()
            performServerHandshake(socket)
        }.use { server ->
            val connection = InputLeapConnection(
                "mac.local", server.port, pinnedFingerprint = pin,
                cachedAddress = CachedServerAddress("127.0.0.2", pin),
                resolveAddresses = { resolutions++; arrayOf(loopback) },
                onCertificate = { throw AssertionError("A changed IP must not change trust") },
            )
            try {
                assertThat(connection.connect("android", 1920, 1080)).isInstanceOf(ConnectResult.Ok::class.java)
                assertThat(resolutions).isEqualTo(1)
                assertThat(connection.verifiedServerAddress).isEqualTo(CachedServerAddress("127.0.0.1", pin))
            } finally { connection.close() }
        }
    }

    @Test fun `reassigned cache and spoofed DNS cannot replace the pinned Mac certificate`() = runBlocking {
        val unrelated = TestTlsIdentity.create()
        val expectedPin = "00".repeat(32)
        var applicationBytes = 0
        TlsLoopbackServer(unrelated.context, connectionCount = 2) { socket, _ ->
            try {
                socket.startHandshake()
                if (socket.inputStream.read() >= 0) applicationBytes++
            } catch (_: IOException) { /* Client aborts TLS when the certificate differs. */ }
        }.use { server ->
            val connection = InputLeapConnection(
                "mac.local", server.port, pinnedFingerprint = expectedPin,
                cachedAddress = CachedServerAddress("127.0.0.1", expectedPin),
                resolveAddresses = { arrayOf(loopback) },
                onCertificate = { throw AssertionError("Never ask to trust the occupant of a stale IP") },
            )
            try {
                val result = connection.connect("android", 1920, 1080) as ConnectResult.Failed
                assertThat(result.reason).isEqualTo(ConnectResult.FailureReason.CERTIFICATE_MISMATCH)
                assertThat(connection.verifiedServerAddress).isNull()
            } finally { connection.close() }
        }
        assertThat(applicationBytes).isEqualTo(0)
    }

    @Test fun `cached address is never used for an unencrypted connection`() = runBlocking {
        var resolutions = 0
        LoopbackServer { socket, _ -> performServerHandshake(socket) }.use { server ->
            val pin = "ab".repeat(32)
            val connection = InputLeapConnection(
                "mac.local", server.port, transportPolicy = ConnectionTransportPolicy.PLAIN_ONLY,
                pinnedFingerprint = pin, cachedAddress = CachedServerAddress("127.0.0.1", pin),
                resolveAddresses = { resolutions++; arrayOf(loopback) }, onCertificate = { false },
            )
            try {
                assertThat(connection.connect("android", 1920, 1080)).isInstanceOf(ConnectResult.Ok::class.java)
                assertThat(resolutions).isEqualTo(1)
                assertThat(connection.verifiedServerAddress).isNull()
            } finally { connection.close() }
        }
    }

    @Test fun `TLS acceptance alone does not cache an unregistered screen`() = runBlocking {
        val identity = TestTlsIdentity.create()
        TlsLoopbackServer(identity.context) { socket, _ ->
            socket.startHandshake()
            val input = DataInputStream(socket.inputStream)
            val output = DataOutputStream(socket.outputStream)
            writeFrame(output, helloBody())
            readFrame(input)
            writeFrame(output, "EUNK".toByteArray())
        }.use { server ->
            val connection = InputLeapConnection(
                "mac.local", server.port, transportPolicy = ConnectionTransportPolicy.TLS_ONLY,
                resolveAddresses = { arrayOf(loopback) }, onCertificate = { true },
            )
            try {
                val result = connection.connect("android", 1920, 1080) as ConnectResult.Failed
                assertThat(result.reason).isEqualTo(ConnectResult.FailureReason.UNKNOWN_SCREEN)
                assertThat(connection.verifiedServerAddress).isNull()
            } finally { connection.close() }
        }
    }

    @Test fun `failed cache followed by unavailable Bonjour reports name resolution failure`() = runBlocking {
        val pin = "ab".repeat(32)
        LoopbackServer { socket, _ ->
            assertThat(socket.inputStream.read()).isEqualTo(0x16) // TLS, never an input hello.
        }.use { server ->
            val connection = InputLeapConnection(
                "mac.local", server.port, pinnedFingerprint = pin,
                cachedAddress = CachedServerAddress("127.0.0.1", pin),
                resolveAddresses = { throw UnknownHostException(it) }, onCertificate = { false },
            )
            try {
                val result = connection.connect("android", 1920, 1080) as ConnectResult.Failed
                assertThat(result.reason).isEqualTo(ConnectResult.FailureReason.NAME_RESOLUTION)
                assertThat(connection.verifiedServerAddress).isNull()
            } finally { connection.close() }
        }
    }

    @Test fun `cache rejects names malformed addresses and interface-scoped IPv6`() {
        val pin = "ab".repeat(32)
        for (address in listOf("another.local", "999.1.2.3", "", "fe80::1%wlan0", "fe80::1")) {
            assertThat(CachedServerAddress.verified(address, pin)).isNull()
        }
        assertThat(CachedServerAddress.verified("192.168.1.10", pin)?.numericAddress()?.hostAddress)
            .isEqualTo("192.168.1.10")
        assertThat(CachedServerAddress.verified("fd00::1", pin)).isNotNull()
        assertThat(CachedServerAddress("192.168.1.10", pin).matchesPin(null)).isFalse()
        assertThat(CachedServerAddress("192.168.1.10", pin).matchesPin("cd".repeat(32))).isFalse()
    }
}
