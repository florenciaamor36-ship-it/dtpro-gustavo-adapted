package com.example.service

import com.example.data.model.TunnelConfig
import com.example.data.model.TunnelMode
import com.jcraft.jsch.SocketFactory
import kotlinx.coroutines.CoroutineScope
import okhttp3.WebSocket
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class VirtualWebSocketSocket(
    private val webSocket: WebSocket,
    private val scope: CoroutineScope
) : Socket() {
    private val inPipe = PipedInputStream(65536)
    private val outPipe = PipedOutputStream(inPipe)

    private val virtualOutputStream = object : OutputStream() {
        override fun write(b: Int) {
            val arr = byteArrayOf(b.toByte())
            webSocket.send(ByteString.of(*arr))
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len > 0) {
                val copy = b.copyOfRange(off, off + len)
                webSocket.send(ByteString.of(*copy))
            }
        }
    }

    fun onIncomingBytes(bytes: ByteString) {
        try {
            outPipe.write(bytes.toByteArray())
            outPipe.flush()
        } catch (_: Exception) {}
    }

    override fun getInputStream(): InputStream = inPipe
    override fun getOutputStream(): OutputStream = virtualOutputStream
    override fun isConnected(): Boolean = true
    override fun isClosed(): Boolean = false
    override fun close() {
        try { inPipe.close() } catch (_: Exception) {}
        try { outPipe.close() } catch (_: Exception) {}
        try { webSocket.close(1000, "Closed") } catch (_: Exception) {}
    }
}

class CustomSshSocketFactory(
    private val config: TunnelConfig,
    private val scope: CoroutineScope,
    private val logCallback: (String) -> Unit
) : SocketFactory {

    private var virtualWsSocket: VirtualWebSocketSocket? = null
    private var lastCreatedSocket: Socket? = null

    fun getVirtualSocket(): VirtualWebSocketSocket? = virtualWsSocket

    fun bindWebSocket(ws: WebSocket): VirtualWebSocketSocket {
        val socket = VirtualWebSocketSocket(ws, scope)
        this.virtualWsSocket = socket
        return socket
    }

    override fun createSocket(host: String?, port: Int): Socket {
        val targetHost = host?.ifBlank { null } ?: config.serverHost
        val targetPort = if (port > 0) port else config.serverPort

        when (config.mode) {
            TunnelMode.SSH_DIRECT -> {
                require(targetHost.isNotBlank()) { "El host del servidor SSH no puede estar vacío." }
                logCallback("Conectando TCP Directo a $targetHost:$targetPort...")
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.soTimeout = 20000
                socket.connect(InetSocketAddress(targetHost, targetPort), 15000)
                lastCreatedSocket = socket
                return socket
            }

            TunnelMode.SSH_SSL -> {
                require(targetHost.isNotBlank()) { "El host del servidor SSH no puede estar vacío." }
                val effectiveSni = config.sniHost.trim().ifBlank { targetHost }
                logCallback("Iniciando conexión SSL/TLS con SNI '$effectiveSni' a $targetHost:$targetPort...")

                val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val sslSocket = sslFactory.createSocket(targetHost, targetPort) as SSLSocket

                if (effectiveSni.isNotBlank()) {
                    val sslParams = SSLParameters()
                    sslParams.serverNames = listOf(SNIHostName(effectiveSni))
                    sslSocket.sslParameters = sslParams
                }

                sslSocket.soTimeout = 20000
                sslSocket.startHandshake()
                logCallback("✓ Handshake SSL/TLS completado con éxito con validación de certificados.")
                lastCreatedSocket = sslSocket
                return sslSocket
            }

            TunnelMode.SSH_PAYLOAD -> {
                val configuredFrontHost = config.proxyHost.trim()
                val frontHost = configuredFrontHost.ifBlank { targetHost }
                // With no proxy configured, HTTP Custom connects directly to the
                // SSH host on the payload port (normally 80), never to the model
                // default 8080.
                val frontPort = if (configuredFrontHost.isBlank()) 80 else if (config.proxyPort > 0) config.proxyPort else 80

                require(frontHost.isNotBlank()) { "El Host frontal o Proxy no puede estar vacío." }
                require(targetHost.isNotBlank()) { "El Host real del servidor SSH no puede estar vacío." }

                logCallback("Conectando socket TCP al Host Frontal/Proxy: $frontHost:$frontPort...")
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.soTimeout = 20000
                socket.connect(InetSocketAddress(frontHost, frontPort), 15000)
                logCallback("✓ Socket TCP conectado a $frontHost:$frontPort")

                if (config.customPayload.isNotBlank()) {
                    val blocks = PayloadCodec.expandBlocks(
                        template = config.customPayload,
                        host = targetHost,
                        port = targetPort,
                        userAgent = "LaClaveArgentina/1.0"
                    )

                    val output = socket.getOutputStream()
                    if (blocks.size > 1) {
                        logCallback("Iniciando Inyección Partida ([split] en ${blocks.size} bloques)...")
                    }

                    for ((index, block) in blocks.withIndex()) {
                        logCallback("Enviando bloque HTTP ${index + 1}/${blocks.size} (${block.size} bytes)...")
                        output.write(block)
                        output.flush()
                    }
                    logCallback("✓ Payload completado y enviado. Validando respuesta del servidor...")
                }

                val pushbackIn = PushbackInputStream(socket.getInputStream(), 8192)
                validateAndConsumeHttpResponse(pushbackIn, socket)

                val wrappedSocket = PayloadSocketWrapper(socket, pushbackIn)
                lastCreatedSocket = wrappedSocket
                return wrappedSocket
            }

            TunnelMode.SSH_WEBSOCKET, TunnelMode.SSH_WEBSOCKET_SSL -> {
                return virtualWsSocket ?: throw IllegalStateException("Transporte WebSocket no inicializado.")
            }

            else -> {
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.soTimeout = 20000
                socket.connect(InetSocketAddress(targetHost, targetPort), 15000)
                lastCreatedSocket = socket
                return socket
            }
        }
    }

    private fun validateAndConsumeHttpResponse(pushbackIn: PushbackInputStream, socket: Socket) {
        val headerBuffer = ByteArrayOutputStream()
        val checkBuf = ByteArray(1)

        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < 15000) {
            val r = pushbackIn.read(checkBuf, 0, 1)
            if (r <= 0) break
            val b = checkBuf[0]
            headerBuffer.write(b.toInt())

            if (b == '\r'.code.toByte() || b == '\n'.code.toByte()) {
                if (headerBuffer.size() >= 4) {
                    val bytes = headerBuffer.toByteArray()
                    val len = bytes.size
                    if ((bytes[len - 4] == '\r'.code.toByte() && bytes[len - 3] == '\n'.code.toByte() &&
                                bytes[len - 2] == '\r'.code.toByte() && bytes[len - 1] == '\n'.code.toByte()) ||
                        (bytes[len - 2] == '\n'.code.toByte() && bytes[len - 1] == '\n'.code.toByte())) {
                        break
                    }
                }
            }
        }

        val respBytes = headerBuffer.toByteArray()
        if (respBytes.isEmpty()) {
            socket.close()
            throw IOException("El servidor cerró la conexión prematuramente sin responder cabeceras HTTP.")
        }

        val status = PayloadCodec.parseStatus(respBytes)
        when (status) {
            is HttpStatus.Success -> {
                logCallback("✓ Respuesta HTTP válida recibida: ${status.statusLine}")
            }
            is HttpStatus.Rejected -> {
                socket.close()
                logCallback("⛔ Respuesta HTTP rechazada (${status.code}): ${status.statusLine}")
                throw IOException("Transporte HTTP rechazado con estado ${status.code}: ${status.statusLine}")
            }
            is HttpStatus.Incomplete -> {
                socket.close()
                logCallback("⛔ Respuesta HTTP incompleta o timeout.")
                throw IOException("Respuesta HTTP incompleta o timeout del servidor.")
            }
            is HttpStatus.Invalid -> {
                val prefix = String(respBytes, 0, minOf(respBytes.size, 7), Charsets.ISO_8859_1)
                if (prefix.startsWith("SSH-", ignoreCase = true)) {
                    pushbackIn.unread(respBytes)
                    logCallback("Banner SSH directo detectado sin encapsulado HTTP.")
                    return
                }
                socket.close()
                logCallback("⛔ Respuesta de transporte inválida.")
                throw IOException("Respuesta de transporte HTTP inválida.")
            }
            is HttpStatus.Other -> {
                logCallback("Aviso: Respuesta HTTP no estándar: ${status.statusLine}")
            }
        }
    }

    override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
    override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
}

/**
 * Socket wrapper holding PushbackInputStream for transparent byte reading after HTTP header consumption.
 */
class PayloadSocketWrapper(
    private val rawSocket: Socket,
    private val pushbackInputStream: PushbackInputStream
) : Socket() {
    override fun getInputStream(): InputStream = pushbackInputStream
    override fun getOutputStream(): OutputStream = rawSocket.getOutputStream()
    override fun isConnected(): Boolean = rawSocket.isConnected
    override fun isClosed(): Boolean = rawSocket.isClosed
    override fun close() = rawSocket.close()
    override fun getInetAddress(): InetAddress = rawSocket.inetAddress
    override fun getPort(): Int = rawSocket.port
    override fun getLocalPort(): Int = rawSocket.localPort
}
