package com.example.service

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import java.util.concurrent.TimeUnit

class WebSocketTransport(
    private val url: String,
    private val customHeaders: Map<String, String> = emptyMap(),
    private val sniHost: String? = null,
    private val onOpenCallback: (WebSocket) -> Unit,
    private val onBinaryMessage: (ByteString) -> Unit,
    private val onFailureCallback: (Throwable, Response?) -> Unit,
    private val onClosedCallback: (Int, String) -> Unit
) {
    private var webSocket: WebSocket? = null

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(10, TimeUnit.SECONDS)
            .build()
    }

    fun connect(): WebSocket {
        val requestBuilder = Request.Builder().url(url)
        customHeaders.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }
        if (!sniHost.isNullOrBlank()) {
            requestBuilder.addHeader("Host", sniHost)
        }

        val request = requestBuilder.build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onOpenCallback(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onBinaryMessage(bytes)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                onBinaryMessage(text.encodeUtf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onClosedCallback(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onFailureCallback(t, response)
            }
        })
        return webSocket!!
    }

    fun close() {
        try {
            webSocket?.close(1000, "Normal Closure")
        } catch (_: Exception) {}
        client.dispatcher.executorService.shutdown()
    }
}
