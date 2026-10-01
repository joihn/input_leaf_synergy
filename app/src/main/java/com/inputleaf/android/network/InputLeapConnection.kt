package com.inputleaf.android.network

import android.util.Log
import com.inputleaf.android.BuildConfig
import com.inputleaf.android.model.InputLeapEvent
import com.inputleaf.android.protocol.ProtocolConstants
import com.inputleaf.android.protocol.ProtocolParser
import com.inputleaf.android.protocol.ProtocolWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.net.InetAddress
import java.net.Socket
import java.net.UnknownHostException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

private const val TAG = "InputLeapConnection"
private const val HANDSHAKE_READ_TIMEOUT_MS = 15_000
private const val TLS_CONNECT_TIMEOUT_CACHED_MS = 800
private const val TLS_CONNECT_TIMEOUT_MS = 2_000
private const val TLS_HANDSHAKE_TIMEOUT_MS = 1_500
private const val TLS_CLIENT_AUTH_HANDSHAKE_TIMEOUT_MS = 90_000
private const val PLAIN_CONNECT_TIMEOUT_CACHED_MS = 800
private const val PLAIN_CONNECT_TIMEOUT_MS = 2_000

private fun logD(message: String) { if (BuildConfig.DEBUG) runCatching { Log.d(TAG, message) } }
private fun logW(message: String) { runCatching { Log.w(TAG, message) } }
private fun logE(message: String) { runCatching { Log.e(TAG, message) } }

class InputLeapConnection(
    private val ip: String,
    private val port: Int = 24800,
    private val preferredTransport: ServerTransport? = null,
    private val pinnedFingerprint: String? = null,
    private val transportPolicy: ConnectionTransportPolicy = ConnectionTransportPolicy.AUTO,
    private val clientCertificate: ClientCertificateMaterial? = null,
    private val resolveAddresses: (String) -> Array<InetAddress> = InetAddress::getAllByName,
    private val cachedAddress: CachedServerAddress? = null,
    private val onCertificate: suspend (X509Certificate) -> Boolean,
) {
    private val _events = MutableSharedFlow<InputLeapEvent>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<InputLeapEvent> = _events

    /** Set only after TLS identity verification and the complete input-protocol handshake. */
    var verifiedServerAddress: CachedServerAddress? = null
        private set

    private val trustedCachedAddress: InetAddress?
        get() = cachedAddress?.takeIf {
            transportPolicy != ConnectionTransportPolicy.PLAIN_ONLY &&
                CachedServerAddress.isHostname(ip) && it.matchesPin(pinnedFingerprint)
        }?.numericAddress()

    private var socket: Socket? = null
    private var writer: ProtocolWriter? = null
    private var sharedDin: DataInputStream? = null
    private var sharedParser: ProtocolParser? = null
    private val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readJob: Job? = null
    private val connectMutex = Mutex()

    /** Version advertised by the server before client-side minor-version negotiation. */
    data class ServerBanner(val major: Int, val minor: Int)

    /**
     * Opens and handshakes a connection. Attempts are serialized, and calling this while the
     * connection is already open throws [IllegalStateException]. Call [close] before reconnecting.
     */
    suspend fun connect(screenName: String, screenWidth: Int, screenHeight: Int): ConnectResult =
        connectMutex.withLock {
            check(socket == null) { "Connection is already open; close it before reconnecting" }
            withContext(Dispatchers.IO) {
                verifiedServerAddress = null
                val cachedIp = trustedCachedAddress
                if (cachedIp != null) {
                    logD("Trying cached address ${cachedIp.hostAddress} for $ip with pinned TLS")
                    when (val opened = openTlsSocket({ arrayOf(cachedIp) }, cachedAttempt = true)) {
                        is SocketOpenResult.Ok -> {
                            val result = runHandshake(opened.socket, opened.transport, screenName, screenWidth, screenHeight)
                            if (result is ConnectResult.Ok) {
                                rememberVerifiedAddress(opened)
                                return@withContext result
                            }
                            if (result is ConnectResult.Failed && result.reason != ConnectResult.FailureReason.HANDSHAKE) {
                                return@withContext result
                            }
                        }
                        is SocketOpenResult.Rejected -> return@withContext ConnectResult.RejectedByUser
                        is SocketOpenResult.Failed -> logD("Cached address failed for $ip: ${opened.failure.reason}")
                    }
                    logD("Resolving $ip after cached address failed")
                }
                val detectedMode =
                    if (
                        transportPolicy == ConnectionTransportPolicy.AUTO &&
                        pinnedFingerprint == null
                    ) {
                        TransportProber.detect(ip, port, resolveAddresses)
                    } else {
                        null
                    }
                val transports =
                    if (
                        transportPolicy == ConnectionTransportPolicy.AUTO &&
                        pinnedFingerprint != null
                    ) {
                        listOf(ServerTransport.TLS)
                    } else {
                        transportPolicy.order(
                            preferredTransport = preferredTransport,
                            detectedMode = detectedMode,
                        )
                    }
                var lastFailure: ConnectResult.Failed? = null
                for (transport in transports) {
                    when (val opened = openSocket(transport)) {
                        is SocketOpenResult.Ok -> {
                            val result = runHandshake(
                                opened.socket,
                                opened.transport,
                                screenName,
                                screenWidth,
                                screenHeight,
                            )
                            if (result is ConnectResult.Ok) {
                                rememberVerifiedAddress(opened)
                                return@withContext result
                            }
                            if (result is ConnectResult.Failed) {
                                lastFailure = selectFailureToReport(lastFailure, result)
                                val shouldStop = pinnedFingerprint != null ||
                                    !transportPolicy.shouldFallbackWithinAttempt(result.reason)
                                if (shouldStop) break
                            }
                        }
                        is SocketOpenResult.Rejected -> return@withContext ConnectResult.RejectedByUser
                        is SocketOpenResult.Failed -> {
                            lastFailure = selectFailureToReport(lastFailure, opened.failure)
                            val shouldStop = pinnedFingerprint != null ||
                                !transportPolicy.shouldFallbackWithinAttempt(opened.failure.reason)
                            if (shouldStop) break
                        }
                    }
                }
                val failure = lastFailure ?: ConnectResult.Failed(ConnectResult.FailureReason.NETWORK)
                logE("All transports failed for $ip: ${failure.reason} ${failure.detail}")
                failure
            }
        }

    private sealed class SocketOpenResult {
        data class Ok(val socket: Socket, val transport: ServerTransport, val fingerprint: String? = null) : SocketOpenResult()
        data object Rejected : SocketOpenResult()
        data class Failed(val failure: ConnectResult.Failed) : SocketOpenResult()
    }

    private suspend fun openSocket(transport: ServerTransport): SocketOpenResult = try {
        when (transport) {
            ServerTransport.TLS -> openTlsSocket()
            ServerTransport.PLAIN -> try {
                SocketOpenResult.Ok(openPlainSocket(), ServerTransport.PLAIN)
            } catch (e: Exception) {
                logW("Plain open failed for $ip: ${e.message}")
                SocketOpenResult.Failed(
                    networkFailure(e),
                )
            }
        }
    } catch (e: Exception) {
        SocketOpenResult.Failed(
            networkFailure(e),
        )
    }

    private fun rememberVerifiedAddress(opened: SocketOpenResult.Ok) {
        if (CachedServerAddress.isHostname(ip) && opened.transport == ServerTransport.TLS) {
            val address = opened.socket.inetAddress.hostAddress ?: return
            val fingerprint = opened.fingerprint ?: return
            verifiedServerAddress = CachedServerAddress.verified(address, fingerprint)
        }
    }

    private suspend fun openTlsSocket(
        resolver: (String) -> Array<InetAddress> = resolveAddresses,
        cachedAttempt: Boolean = false,
    ): SocketOpenResult {
        var openedSocket: SSLSocket? = null
        val connectTimeout = if (
            cachedAttempt || transportPolicy == ConnectionTransportPolicy.AUTO &&
            preferredTransport == ServerTransport.TLS
        ) {
            TLS_CONNECT_TIMEOUT_CACHED_MS
        } else {
            TLS_CONNECT_TIMEOUT_MS
        }
        return try {
            var capturedCert: X509Certificate? = null
            // When a trusted routing hint exists, pin during TLS for both the cached and
            // rediscovered endpoint. A reassigned cafe IP must never show a re-trust prompt.
            val strictPin = trustedCachedAddress != null
            val sslContext = if (strictPin) {
                TlsFingerprintManager.buildPinningSSLContext(checkNotNull(pinnedFingerprint), clientCertificate)
            } else {
                TlsFingerprintManager.buildCapturingSSLContext(
                    clientCertificate = clientCertificate,
                    onCertificate = { cert -> capturedCert = cert },
                )
            }
            val sslSock = ServerSocketConnector.connect(ip, port, connectTimeout, resolver) {
                sslContext.socketFactory.createSocket() as SSLSocket
            }
            openedSocket = sslSock
            logD("TCP connected to $ip at ${sslSock.inetAddress.hostAddress}:$port")
            // TCP fallback ends here. A failed cached endpoint can trigger hostname
            // rediscovery, but that attempt must retain the same pin and TLS transport.
            sslSock.soTimeout = if (cachedAttempt) TLS_HANDSHAKE_TIMEOUT_MS else tlsHandshakeTimeoutMs()
            sslSock.startHandshake()
            if (strictPin) capturedCert = sslSock.session.peerCertificates.firstOrNull() as? X509Certificate
            sslSock.soTimeout = HANDSHAKE_READ_TIMEOUT_MS
            val cert = capturedCert ?: run {
                sslSock.close()
                openedSocket = null
                return SocketOpenResult.Failed(
                    ConnectResult.Failed(
                        ConnectResult.FailureReason.NETWORK,
                        "No certificate captured",
                    ),
                )
            }
            val fingerprint = TlsFingerprintManager.fingerprintOf(cert)
            val alreadyTrusted = pinnedFingerprint?.let(TlsFingerprintManager::normalizeFingerprint) == fingerprint
            if (!alreadyTrusted && !onCertificate(cert)) {
                sslSock.close()
                openedSocket = null
                return SocketOpenResult.Rejected
            }
            SocketOpenResult.Ok(sslSock, ServerTransport.TLS, fingerprint)
        } catch (e: Exception) {
            runCatching { openedSocket?.close() }
            if (clientCertificate == null && isClientCertificateRequired(e)) {
                logW("Deskflow requires a client certificate")
                SocketOpenResult.Failed(
                    ConnectResult.Failed(
                        ConnectResult.FailureReason.CLIENT_CERT_REQUIRED,
                        e.message,
                    ),
                )
            } else if (isCertificateMismatch(e)) {
                logW("TLS certificate changed for $ip")
                SocketOpenResult.Failed(
                    ConnectResult.Failed(
                        ConnectResult.FailureReason.CERTIFICATE_MISMATCH,
                        e.message,
                    ),
                )
            } else if (isPlainServerTlsError(e)) {
                logD("TLS required, but $ip speaks plain Deskflow")
                SocketOpenResult.Failed(
                    ConnectResult.Failed(
                        ConnectResult.FailureReason.TLS_AGAINST_PLAIN_SERVER,
                        e.message,
                    ),
                )
            } else {
                logW("TLS open failed for $ip: ${e.javaClass.simpleName}: ${e.message}")
                SocketOpenResult.Failed(
                    networkFailure(e),
                )
            }
        }
    }

    private fun tlsHandshakeTimeoutMs(): Int =
        if (clientCertificate != null) {
            // Deskflow blocks the TLS handshake until the user trusts this phone's
            // certificate. Aborting at 1.5s drops that dialog and Auto then waits
            // 15s on a doomed plaintext attempt.
            TLS_CLIENT_AUTH_HANDSHAKE_TIMEOUT_MS
        } else {
            TLS_HANDSHAKE_TIMEOUT_MS
        }

    private fun openPlainSocket(): Socket {
        val connectTimeout = if (
            transportPolicy == ConnectionTransportPolicy.AUTO &&
            preferredTransport == ServerTransport.PLAIN
        ) {
            PLAIN_CONNECT_TIMEOUT_CACHED_MS
        } else {
            PLAIN_CONNECT_TIMEOUT_MS
        }
        val socket = ServerSocketConnector.connect(ip, port, connectTimeout, resolveAddresses) { Socket() }
        socket.tcpNoDelay = true
        socket.soTimeout = HANDSHAKE_READ_TIMEOUT_MS
        return socket
    }

    /**
     * Run the Input Leap handshake synchronously before returning.
     * Matches schengen client: server hello → client hello → QINF → DINF → CIAK → CROP/DSOP.
     */
    private fun runHandshake(
        rawSocket: Socket,
        transport: ServerTransport,
        screenName: String,
        screenWidth: Int,
        screenHeight: Int,
    ): ConnectResult {
        rawSocket.tcpNoDelay = true
        socket = rawSocket
        writer = ProtocolWriter(rawSocket.outputStream)
        val din = DataInputStream(rawSocket.inputStream)
        sharedDin = din
        val parser = ProtocolParser(din)
        sharedParser = parser

        var helloSent = false
        var dinfSent = false
        var optionsReceived = false
        var bannerMajor = ProtocolConstants.PROTOCOL_MAJOR
        var bannerMinor = ProtocolConstants.PROTOCOL_MINOR

        try {
            repeat(32) {
                val event = parser.readNext()
                logD("Handshake recv: $event")
                when (event) {
                    is InputLeapEvent.Hello -> {
                        bannerMajor = event.majorVersion
                        bannerMinor = event.minorVersion
                        if (event.majorVersion != ProtocolConstants.PROTOCOL_MAJOR) {
                            close()
                            return ConnectResult.Failed(
                                ConnectResult.FailureReason.INCOMPATIBLE,
                                "Unsupported server protocol ${event.majorVersion}.${event.minorVersion}",
                            )
                        }
                        if (!helloSent) {
                            val negotiatedProtocol = event.protocol
                            val negotiatedMinor =
                                ProtocolConstants.negotiateMinor(event.minorVersion)
                            writer?.writeHelloBack(
                                screenName = screenName,
                                major = ProtocolConstants.PROTOCOL_MAJOR,
                                minor = negotiatedMinor,
                                protocol = negotiatedProtocol,
                            )
                            helloSent = true
                            logD(
                                "Handshake sent ${negotiatedProtocol.magic} client hello " +
                                    "as $screenName using 1.$negotiatedMinor",
                            )
                        }
                    }
                    is InputLeapEvent.QueryInfo -> {
                        writer?.writeDataInfo(screenWidth, screenHeight, 0, 0, 0, 0)
                        dinfSent = true
                        logD("Handshake sent DINF ${screenWidth}x$screenHeight")
                    }
                    is InputLeapEvent.KeepAlive -> {
                        writer?.writeKeepAlive()
                    }
                    is InputLeapEvent.Unhandled -> {
                        when (event.tag) {
                            // CIAK only acknowledges dimensions. Synergy can still send
                            // EUNK after it; DSOP is the server's session acceptance.
                            "DSOP" -> if (dinfSent) optionsReceived = true
                        }
                    }
                    is InputLeapEvent.Incompatible -> {
                        logE("Server rejected handshake: $event")
                        close()
                        return ConnectResult.Failed(
                            ConnectResult.FailureReason.INCOMPATIBLE,
                            "Server requires ${event.major}.${event.minor}",
                        )
                    }
                    is InputLeapEvent.Busy -> {
                        logE("Server rejected handshake: busy")
                        close()
                        return ConnectResult.Failed(ConnectResult.FailureReason.BUSY)
                    }
                    is InputLeapEvent.Unknown -> {
                        close()
                        return ConnectResult.Failed(ConnectResult.FailureReason.UNKNOWN_SCREEN)
                    }
                    is InputLeapEvent.BadMessage -> {
                        close()
                        return ConnectResult.Failed(ConnectResult.FailureReason.PROTOCOL_ERROR)
                    }
                    else -> Unit
                }
                if (helloSent && dinfSent && optionsReceived) {
                    rawSocket.soTimeout = 0
                    readJob = readerScope.launch { readLoop(parser) }
                    logD("Handshake complete via $transport")
                    return ConnectResult.Ok(ServerBanner(bannerMajor, bannerMinor), transport)
                }
            }
        } catch (e: Exception) {
            logE("Handshake error: ${e.javaClass.simpleName}: ${e.message}")
            close()
            return ConnectResult.Failed(ConnectResult.FailureReason.HANDSHAKE, e.message)
        }

        logE("Handshake incomplete hello=$helloSent dinf=$dinfSent options=$optionsReceived")
        close()
        return ConnectResult.Failed(
            ConnectResult.FailureReason.HANDSHAKE,
            "Incomplete handshake: hello=$helloSent, deviceInfo=$dinfSent",
        )
    }

    private suspend fun readLoop(parser: ProtocolParser) {
        try {
            while (true) {
                val event = parser.readNext()
                if (event !is InputLeapEvent.MouseMoveAbs &&
                    event !is InputLeapEvent.MouseMoveRel &&
                    event !is InputLeapEvent.KeepAlive
                ) {
                    logD("Read event: $event")
                }
                val mapped = when {
                    event is InputLeapEvent.Unhandled && event.tag == "CIAK" ->
                        InputLeapEvent.InfoAck()
                    else -> event
                }
                _events.emit(mapped)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logE("Read loop ended: ${e.javaClass.simpleName}: ${e.message}")
            _events.emit(InputLeapEvent.Unhandled("__DISCONNECTED__"))
        }
    }

    fun clearHandshakeTimeout() {
        runCatching { socket?.soTimeout = 0 }
    }

    fun sendDataInfo(w: Int, h: Int, mx: Int, my: Int) =
        writer?.writeDataInfo(w, h, 0, 0, mx, my)
    fun sendKeepAlive() = writer?.writeKeepAlive()
    fun sendInfoAck() = writer?.writeInfoAck()

    fun close() {
        readJob?.cancel()
        readJob = null
        runCatching { socket?.close() }
        socket = null
        writer = null
        sharedDin = null
        sharedParser = null
    }

    companion object {
        internal fun networkFailure(error: Exception): ConnectResult.Failed {
            val reason = if (generateSequence<Throwable>(error) { it.cause }.any { it is UnknownHostException }) {
                ConnectResult.FailureReason.NAME_RESOLUTION
            } else {
                ConnectResult.FailureReason.NETWORK
            }
            return ConnectResult.Failed(reason, error.message)
        }

        internal fun selectFailureToReport(
            current: ConnectResult.Failed?,
            candidate: ConnectResult.Failed,
        ): ConnectResult.Failed {
            val candidatePriority = failurePriority(candidate.reason)
            val currentPriority = current?.let { failurePriority(it.reason) }
            return if (currentPriority == null || candidatePriority > currentPriority) {
                candidate
            } else {
                current
            }
        }

        private fun failurePriority(reason: ConnectResult.FailureReason): Int = when (reason) {
            ConnectResult.FailureReason.CERTIFICATE_MISMATCH,
            ConnectResult.FailureReason.CLIENT_CERT_REQUIRED,
            ConnectResult.FailureReason.INCOMPATIBLE,
            ConnectResult.FailureReason.UNKNOWN_SCREEN,
            ConnectResult.FailureReason.PROTOCOL_ERROR,
            ConnectResult.FailureReason.BUSY -> 4
            ConnectResult.FailureReason.NETWORK,
            ConnectResult.FailureReason.NAME_RESOLUTION -> 3
            ConnectResult.FailureReason.HANDSHAKE -> 2
            ConnectResult.FailureReason.TLS_AGAINST_PLAIN_SERVER -> 1
        }

        internal fun isCertificateMismatch(error: Exception): Boolean =
            generateSequence<Throwable>(error) { it.cause }
                .any { "certificate fingerprint mismatch" in it.message.orEmpty().lowercase() }

        internal fun isClientCertificateRequired(error: Exception): Boolean {
            val message = generateSequence<Throwable>(error) { it.cause }
                .joinToString(" ") { it.message.orEmpty() }
                .lowercase()
            return "certificate required" in message ||
                "certificate_required" in message ||
                "bad certificate" in message ||
                "bad_certificate" in message
        }

        internal fun isPlainServerTlsError(error: Exception): Boolean {
            val causes = generateSequence<Throwable>(error) { it.cause }.toList()
            val message = causes.joinToString(" ") { it.message.orEmpty() }.lowercase()
            return causes.any { it is SSLException } && (
                "unable to parse tls packet header" in message ||
                    "not an sslv2 hello" in message ||
                    "unsupported or unrecognized ssl message" in message
            )
        }
    }
}
