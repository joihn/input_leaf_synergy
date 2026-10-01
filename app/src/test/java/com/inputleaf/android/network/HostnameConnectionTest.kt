package com.inputleaf.android.network

import com.google.common.truth.Truth.assertThat
import com.inputleaf.android.model.WireProtocol
import com.inputleaf.android.testutil.LoopbackServer
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.UnknownHostException

class HostnameConnectionTest {
    // Fixtures listen on IPv4 only, matching the installed Synergy 3.7.2 core.
    private val addresses = arrayOf(InetAddress.getByName("::1"), InetAddress.getByName("127.0.0.1"))

    @Test fun `TLS connection reaches IPv4 Synergy when the hostname also resolves to IPv6`() = runBlocking {
        val identity = TestTlsIdentity.create()
        TlsLoopbackServer(identity.context) { socket, _ ->
            socket.startHandshake()
            performServerHandshake(socket, serverMinor = 8, protocol = WireProtocol.SYNERGY)
        }.use { server ->
            val connection = InputLeapConnection(
                ip = "mac.local", port = server.port,
                transportPolicy = ConnectionTransportPolicy.TLS_ONLY,
                pinnedFingerprint = TlsFingerprintManager.fingerprintOf(identity.certificate),
                resolveAddresses = { addresses },
                onCertificate = { throw AssertionError("Pinned identity must remain trusted") },
            )
            try {
                assertThat(connection.connect("android", 1920, 1080)).isEqualTo(
                    ConnectResult.Ok(InputLeapConnection.ServerBanner(1, 8), ServerTransport.TLS)
                )
            } finally { connection.close() }
        }
    }

    @Test fun `transport probing tries IPv4 after an IPv6 refusal`() = runBlocking {
        LoopbackServer(connectionCount = 2) { socket, _ ->
            writeFrame(DataOutputStream(socket.outputStream), helloBody())
        }.use { server ->
            assertThat(TransportProber.detect("mac.local", server.port) { addresses })
                .isEqualTo(ServerSecurityMode.PLAIN)
        }
    }

    @Test fun `declined certificate is not retried against another address`() = runBlocking {
        val identity = TestTlsIdentity.create()
        TlsLoopbackServer(identity.context) { socket, _ ->
            socket.startHandshake()
            assertThat(socket.inputStream.read()).isEqualTo(-1)
        }.use { server ->
            val connection = InputLeapConnection(
                ip = "mac.local", port = server.port,
                transportPolicy = ConnectionTransportPolicy.TLS_ONLY,
                resolveAddresses = { arrayOf(addresses[1], addresses[1]) },
                onCertificate = { false },
            )
            try {
                assertThat(connection.connect("android", 1920, 1080)).isEqualTo(ConnectResult.RejectedByUser)
            } finally { connection.close() }
        }
    }

    @Test fun `unresolvable hostname has a specific failure without transport downgrade`() = runBlocking {
        for (policy in ConnectionTransportPolicy.entries) {
            val connection = InputLeapConnection(
                ip = "missing.local", transportPolicy = policy,
                resolveAddresses = { throw UnknownHostException(it) },
                onCertificate = { throw AssertionError("No server was reached") },
            )
            try {
                val result = connection.connect("android", 1920, 1080) as ConnectResult.Failed
                assertThat(result.reason).isEqualTo(ConnectResult.FailureReason.NAME_RESOLUTION)
                assertThat(policy.shouldFallbackWithinAttempt(result.reason)).isFalse()
            } finally { connection.close() }
        }
    }
}
