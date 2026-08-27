package com.example.service

import android.content.Context
import com.example.data.model.ConnectionStatus
import com.example.data.model.LogEntry
import com.example.data.model.LogLevel
import com.example.data.model.TunnelConfig
import com.example.data.model.TunnelMode
import com.example.data.model.TunnelState
import com.example.util.BatteryManagerHelper
import com.example.util.HapticFeedbackHelper
import com.example.util.NetworkDiagnostics
import com.example.util.SoundEffectHelper
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TunnelEngine private constructor() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    private val _tunnelState = MutableStateFlow(TunnelState())
    val tunnelState: StateFlow<TunnelState> = _tunnelState.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private var minaEngine: MinaSshEngine? = null
    private var jschSession: Session? = null
    private var wsTransport: WebSocketTransport? = null
    private var localProxy: LocalProxyServer? = null

    private var statsJob: Job? = null
    private var pingerJob: Job? = null
    private var isUserInitiatedStop = false
    private var reconnectAttempts = 0
    private var lastContext: Context? = null

    companion object {
        val instance: TunnelEngine by lazy { TunnelEngine() }
    }

    fun log(message: String, level: LogLevel = LogLevel.INFO) {
        val maskedMsg = maskSecrets(message)
        val entry = LogEntry(message = maskedMsg, level = level)
        _logs.value = _logs.value + entry
    }

    private fun maskSecrets(text: String): String {
        var clean = text
        _tunnelState.value.currentConfig?.let { cfg ->
            if (cfg.password.isNotBlank()) clean = clean.replace(cfg.password, "******")
            if (cfg.lockPassword.isNotBlank()) clean = clean.replace(cfg.lockPassword, "******")
        }
        return clean
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun startTunnel(context: Context?, config: TunnelConfig) {
        lastContext = context?.applicationContext
        isUserInitiatedStop = false
        _tunnelState.value = _tunnelState.value.copy(
            status = ConnectionStatus.Connecting,
            currentConfig = config,
            bytesIn = 0,
            bytesOut = 0,
            pingMs = -1
        )

        log("Iniciando servicio de túnel SSH para perfil '${config.name}'...", LogLevel.INFO)
        context?.let { BatteryManagerHelper.acquireWakeLock(it) }

        scope.launch {
            try {
                // The VPN service is started immediately after this method. Give
                // Android time to create the service instance before protect().
                delay(500)
                when (config.mode) {
                    // HTTP Custom uses a raw HTTP Upgrade over TCP.  Do not use
                    // OkHttp's framed WebSocket client here: after the 101 response
                    // the SSH byte stream must remain on the same socket.
                    TunnelMode.SSH_WEBSOCKET -> {
                        log("Modo WebSocket HTTP directo: usando payload raw + respuesta 101.", LogLevel.INFO)
                        startMinaSshTunnel(config.copy(mode = TunnelMode.SSH_PAYLOAD))
                    }
                    TunnelMode.SSH_WEBSOCKET_SSL -> {
                        // Keep WSS as a separate mode; it is not interchangeable with
                        // the direct HTTP/WebSocket transport used by the target setup.
                        startWebSocketTunnel(config)
                    }
                    else -> {
                        startMinaSshTunnel(config)
                    }
                }
            } catch (e: Exception) {
                val readableError = maskSecrets(e.localizedMessage ?: e.message ?: "Error desconocido")
                log("⛔ Error en túnel SSH: $readableError", LogLevel.ERROR)
                lastContext?.getSharedPreferences("gtunel_diagnostics", Context.MODE_PRIVATE)
                    ?.edit()?.putString("last_error", readableError)?.apply()
                handleConnectionFailure(config, readableError)
            }
        }
    }

    private suspend fun startMinaSshTunnel(config: TunnelConfig) {
        withContext(Dispatchers.IO) {
            log("Configurando transporte para modo ${config.mode.title}...", LogLevel.INFO)
            _tunnelState.value = _tunnelState.value.copy(status = ConnectionStatus.Authenticating)

            val socketFactory = CustomSshSocketFactory(config, scope) { msg ->
                log(msg, LogLevel.DEBUG)
            }

            val engine = MinaSshEngine(
                hostKeyApprovalCallback = { host, port, fp ->
                    log("Aprobando huella de servidor SSH $host:$port ($fp)...", LogLevel.INFO)
                    true
                },
                logger = { msg -> log(msg, LogLevel.DEBUG) }
            )
            minaEngine = engine

            try {
                val rawSocket = socketFactory.createSocket(config.serverHost, config.serverPort)

                log("Autenticando SSH con Apache MINA SSHD (usuario: '${config.username}')...", LogLevel.INFO)
                engine.connect(
                    host = config.serverHost,
                    port = config.serverPort,
                    username = config.username,
                    password = config.password,
                    existingSocket = rawSocket,
                    timeoutMillis = 20_000
                )

                log("✓ Sesión SSH autenticada correctamente.", LogLevel.SUCCESS)

                val socksPort = 1080
                try {
                    engine.startDynamicPortForwarding(socksPort)
                    log("✓ Dynamic Port Forwarding (SOCKS5) activo en 127.0.0.1:$socksPort", LogLevel.SUCCESS)
                } catch (e: Exception) {
                    log("Aviso de Port Forwarding MINA: ${e.message}", LogLevel.WARNING)
                }

                startLocalProxy(socksPort)
                reconnectAttempts = 0
                _tunnelState.value = _tunnelState.value.copy(
                    status = ConnectionStatus.Connected,
                    connectedSinceTimestamp = System.currentTimeMillis()
                )
                log("✓ Conexión establecida con éxito. Enrutando tráfico a través del túnel SSH.", LogLevel.SUCCESS)

                lastContext?.let { ctx ->
                    HapticFeedbackHelper.vibrateSuccess(ctx)
                    SoundEffectHelper.playConnectSound(ctx)
                }
                startStatsMonitoring()
                startKeepAlivePinger()
                refreshPublicIp()

            } catch (minaErr: Exception) {
                // Payload/HTTP transport must not fall back to a new direct SSH
                // connection: that would incorrectly target the frontal host and
                // hide the real transport error (often as UnknownHostException).
                if (config.mode == TunnelMode.SSH_PAYLOAD) {
                    log("⛔ SSH sobre payload no pudo iniciar: ${minaErr.message}", LogLevel.ERROR)
                    throw minaErr
                }
                log("Aviso: MINA SSHD falló: ${minaErr.message}. Probando fallback JSch...", LogLevel.WARNING)
                connectJSchFallback(config, socketFactory)
            }
        }
    }

    private suspend fun connectJSchFallback(config: TunnelConfig, socketFactory: CustomSshSocketFactory) {
        withContext(Dispatchers.IO) {
            val jsch = JSch()
            val session = jsch.getSession(config.username, config.serverHost, config.serverPort)
            session.setPassword(config.password)
            session.setSocketFactory(socketFactory)
            session.setConfig("StrictHostKeyChecking", "ask")
            session.setConfig("PreferredAuthentications", "password,keyboard-interactive")

            session.connect(20000)
            log("✓ Sesión JSch autenticada con éxito.", LogLevel.SUCCESS)

            val socksPort = 1080
            try {
                val dMethod = session.javaClass.methods.firstOrNull {
                    it.name == "setPortForwardingD" && it.parameterTypes.size == 1
                }
                if (dMethod != null) {
                    dMethod.invoke(session, socksPort)
                    log("✓ Dynamic Port Forwarding (SOCKS5 JSch) activo en 127.0.0.1:$socksPort", LogLevel.SUCCESS)
                }
            } catch (e: Exception) {
                log("Aviso de Port Forwarding JSch: ${e.message}", LogLevel.WARNING)
            }

            jschSession = session
            startLocalProxy(socksPort)
            reconnectAttempts = 0
            _tunnelState.value = _tunnelState.value.copy(
                status = ConnectionStatus.Connected,
                connectedSinceTimestamp = System.currentTimeMillis()
            )
            log("✓ Conexión establecida con éxito. Enrutando tráfico.", LogLevel.SUCCESS)
            startStatsMonitoring()
            startKeepAlivePinger()
            refreshPublicIp()
        }
    }

    private fun startWebSocketTunnel(config: TunnelConfig) {
        log("Iniciando túnel SSH sobre WebSocket...", LogLevel.INFO)
        val socketFactory = CustomSshSocketFactory(config, scope) { msg ->
            log(msg, LogLevel.DEBUG)
        }

        val wsScheme = if (config.mode == TunnelMode.SSH_WEBSOCKET_SSL) "wss" else "ws"
        val wsUrl = "$wsScheme://${config.serverHost}:${config.serverPort}/"

        val transport = WebSocketTransport(
            url = wsUrl,
            customHeaders = mapOf("User-Agent" to "LaClaveArgentina/1.0"),
            sniHost = config.sniHost.ifBlank { null },
            onOpenCallback = { ws ->
                log("✓ Conexión WebSocket abierta. Vinculando canal SSH...", LogLevel.SUCCESS)
                socketFactory.bindWebSocket(ws)
                scope.launch(Dispatchers.IO) {
                    try {
                        startMinaSshTunnel(config)
                    } catch (e: Exception) {
                        handleConnectionFailure(config, "Fallo SSH sobre WS: ${e.message}")
                    }
                }
            },
            onBinaryMessage = { bytes ->
                socketFactory.getVirtualSocket()?.onIncomingBytes(bytes)
            },
            onFailureCallback = { err, _ ->
                log("Fallo en transporte WebSocket: ${err.message}", LogLevel.ERROR)
                scope.launch {
                    handleConnectionFailure(config, "Fallo en WebSocket: ${err.localizedMessage}")
                }
            },
            onClosedCallback = { code, reason ->
                log("WebSocket cerrado: $code - $reason", LogLevel.WARNING)
            }
        )
        wsTransport = transport
        transport.connect()
    }

    private fun startLocalProxy(remoteSocksPort: Int) {
        localProxy?.stop()
        localProxy = LocalProxyServer(
            scope = scope,
            localPort = 8080,
            remoteSocksPort = remoteSocksPort,
            onBytesTransferred = { inBytes, outBytes ->
                val current = _tunnelState.value
                _tunnelState.value = current.copy(
                    bytesIn = current.bytesIn + inBytes,
                    bytesOut = current.bytesOut + outBytes
                )
            },
            logCallback = { msg ->
                log(msg, LogLevel.DEBUG)
            }
        )
        localProxy?.start()
    }

    private fun startKeepAlivePinger() {
        pingerJob?.cancel()
        pingerJob = scope.launch {
            while (isActive) {
                delay(30000)
                if (_tunnelState.value.status is ConnectionStatus.Connected) {
                    try {
                        val pingMs = NetworkDiagnostics.checkRealPing("8.8.8.8", 53, 2000)
                        if (pingMs > 0) {
                            _tunnelState.value = _tunnelState.value.copy(pingMs = pingMs)
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun startStatsMonitoring() {
        statsJob?.cancel()
        statsJob = scope.launch {
            var prevIn = 0L
            var prevOut = 0L
            while (isActive) {
                delay(1000)
                val curIn = _tunnelState.value.bytesIn
                val curOut = _tunnelState.value.bytesOut
                val downSpeed = (curIn - prevIn).coerceAtLeast(0)
                val upSpeed = (curOut - prevOut).coerceAtLeast(0)
                prevIn = curIn
                prevOut = curOut

                _tunnelState.value = _tunnelState.value.copy(
                    downloadSpeedBps = downSpeed,
                    uploadSpeedBps = upSpeed
                )
            }
        }
    }

    private fun refreshPublicIp() {
        scope.launch {
            try {
                val ipInfo = NetworkDiagnostics.fetchPublicIpInfo()
                if (ipInfo.ip.isNotBlank() && ipInfo.ip != "---") {
                    _tunnelState.value = _tunnelState.value.copy(
                        publicIp = ipInfo.ip,
                        ipLocation = ipInfo.region
                    )
                    log("IP Pública Asignada: ${ipInfo.ip} (${ipInfo.region})", LogLevel.INFO)
                }
            } catch (_: Exception) {}
        }
    }

    private suspend fun handleConnectionFailure(config: TunnelConfig, reason: String) {
        cleanup()
        lastContext?.let { SoundEffectHelper.playErrorSound(it) }
        // Stop after the first failure while diagnosing startup crashes. Automatic
        // retries can recreate the VPN/socket loop and hide the original exception.
        if (false && config.autoReconnect && !isUserInitiatedStop && reconnectAttempts < 5) {
            reconnectAttempts++
            val delaySec = reconnectAttempts * 3
            log("Reintentando conexión automática en $delaySec s (Intento $reconnectAttempts/5)...", LogLevel.WARNING)
            _tunnelState.value = _tunnelState.value.copy(status = ConnectionStatus.Reconnecting)
            delay(delaySec * 1000L)
            startTunnel(lastContext, config)
        } else {
            _tunnelState.value = _tunnelState.value.copy(
                status = ConnectionStatus.Error(reason)
            )
        }
    }

    fun stopTunnel() {
        isUserInitiatedStop = true
        reconnectAttempts = 0
        log("Deteniendo túnel y liberando recursos...", LogLevel.INFO)
        cleanup()
        _tunnelState.value = _tunnelState.value.copy(
            status = ConnectionStatus.Disconnected,
            downloadSpeedBps = 0L,
            uploadSpeedBps = 0L
        )
        lastContext?.let {
            HapticFeedbackHelper.vibrateDisconnect(it)
            SoundEffectHelper.playDisconnectSound(it)
        }
        log("Túnel desconectado.", LogLevel.INFO)
    }

    fun toggleHotshare(enable: Boolean, listenPort: Int = 8080) {
        log("Hotshare deshabilitado en esta versión enfocada únicamente en SSH.", LogLevel.INFO)
    }

    fun isHotshareActive(): Boolean = false

    private fun cleanup() {
        pingerJob?.cancel()
        pingerJob = null
        statsJob?.cancel()
        statsJob = null

        try {
            minaEngine?.close()
        } catch (_: Exception) {}
        minaEngine = null

        try {
            jschSession?.disconnect()
        } catch (_: Exception) {}
        jschSession = null

        try {
            wsTransport?.close()
        } catch (_: Exception) {}
        wsTransport = null

        try {
            localProxy?.stop()
        } catch (_: Exception) {}
        localProxy = null

        BatteryManagerHelper.releaseWakeLock()
    }
}
