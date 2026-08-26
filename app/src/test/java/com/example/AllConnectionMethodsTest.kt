package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.TunnelConfig
import com.example.data.model.TunnelMode
import com.example.service.CustomSshSocketFactory
import com.example.service.LocalProxyServer
import com.example.service.VirtualWebSocketSocket
import com.example.util.AppFilterManager
import com.example.util.SoundEffectHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AllConnectionMethodsTest {

    private val testScope = CoroutineScope(Dispatchers.IO)
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun testMethod_SshDirect_SocketConnection() = runBlocking {
        val mockSshServer = ServerSocket(0)
        val serverPort = mockSshServer.localPort

        testScope.launch {
            val client = mockSshServer.accept()
            val writer = PrintWriter(OutputStreamWriter(client.getOutputStream()), true)
            writer.print("SSH-2.0-OpenSSH_8.9p1 Ubuntu-3ubuntu0.1\r\n")
            writer.flush()
            delay(100)
            client.close()
            mockSshServer.close()
        }

        val config = TunnelConfig(
            name = "Direct SSH Test",
            mode = TunnelMode.SSH_DIRECT,
            serverHost = "127.0.0.1",
            serverPort = serverPort
        )

        val logs = mutableListOf<String>()
        val factory = CustomSshSocketFactory(config, testScope) { logs.add(it) }
        val socket = factory.createSocket("127.0.0.1", serverPort)

        assertNotNull(socket)
        assertTrue(socket.isConnected)

        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val banner = reader.readLine()
        assertTrue("Debe recibir el banner SSH de bienvenida", banner.startsWith("SSH-2.0"))

        socket.close()
    }

    @Test
    fun testMethod_SshPayload_InjectionAndHeadersConsuming() = runBlocking {
        val mockProxyServer = ServerSocket(0)
        val proxyPort = mockProxyServer.localPort
        val receivedPayload = StringBuilder()
        val latch = CountDownLatch(1)

        testScope.launch {
            val client = mockProxyServer.accept()
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val writer = PrintWriter(OutputStreamWriter(client.getOutputStream()), true)

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                receivedPayload.append(line).append("\n")
                if (line.isNullOrBlank()) break
            }

            writer.print("HTTP/1.1 200 Connection Established\r\n\r\n")
            writer.flush()

            writer.print("SSH-2.0-OpenSSH_8.9p1 Ubuntu\r\n")
            writer.flush()

            latch.countDown()
            client.close()
            mockProxyServer.close()
        }

        val config = TunnelConfig(
            name = "Payload Tunnel Test",
            mode = TunnelMode.SSH_PAYLOAD,
            serverHost = "ssh.myserver.com",
            serverPort = 22,
            proxyHost = "127.0.0.1",
            proxyPort = proxyPort,
            customPayload = "CONNECT [host_port] HTTP/1.1[crlf]Host: [host][crlf]X-Online-Host: [host][crlf]Connection: Keep-Alive[crlf][crlf]"
        )

        val logs = mutableListOf<String>()
        val factory = CustomSshSocketFactory(config, testScope) { logs.add(it) }
        val socket = factory.createSocket("ssh.myserver.com", 22)

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertNotNull(socket)
        assertTrue(socket.isConnected)

        val payloadStr = receivedPayload.toString()
        assertTrue("Debe contener CONNECT ssh.myserver.com:22", payloadStr.contains("CONNECT ssh.myserver.com:22"))
        assertTrue("Debe contener Host: ssh.myserver.com", payloadStr.contains("Host: ssh.myserver.com"))
        assertTrue("Debe contener X-Online-Host", payloadStr.contains("X-Online-Host"))

        socket.close()
    }

    @Test
    fun testMethod_SshWebSocket_VirtualSocketPiping() = runBlocking {
        val bytesSent = mutableListOf<ByteString>()

        val fakeWs = object : WebSocket {
            override fun request(): Request = Request.Builder().url("http://localhost").build()
            override fun queueSize(): Long = 0
            override fun send(text: String): Boolean = true
            override fun send(bytes: ByteString): Boolean {
                bytesSent.add(bytes)
                return true
            }
            override fun close(code: Int, reason: String?): Boolean = true
            override fun cancel() {}
        }

        val virtualSocket = VirtualWebSocketSocket(fakeWs, testScope)
        assertTrue(virtualSocket.isConnected)
        assertFalse(virtualSocket.isClosed)

        val testData = "SSH-2.0-ClientTest\r\n".toByteArray()
        virtualSocket.outputStream.write(testData)
        virtualSocket.outputStream.flush()

        assertEquals(1, bytesSent.size)
        assertEquals("SSH-2.0-ClientTest\r\n", bytesSent[0].utf8())

        val responseBytes = "SSH-2.0-ServerResponse\r\n".encodeUtf8()
        virtualSocket.onIncomingBytes(responseBytes)

        val buffer = ByteArray(24)
        val readBytes = virtualSocket.inputStream.read(buffer)
        assertEquals(24, readBytes)
        assertEquals("SSH-2.0-ServerResponse\r\n", String(buffer, 0, readBytes))

        virtualSocket.close()
    }

    @Test
    fun testLocalProxyServer_BytesAccounting() = runBlocking {
        val mockTargetServer = ServerSocket(0)
        val targetPort = mockTargetServer.localPort

        testScope.launch {
            val sock = mockTargetServer.accept()
            val inS = sock.getInputStream()
            val outS = sock.getOutputStream()
            val buf = ByteArray(1024)
            val read = inS.read(buf)
            if (read > 0) {
                outS.write(buf, 0, read)
                outS.flush()
            }
            delay(50)
            sock.close()
            mockTargetServer.close()
        }

        var inBytesRecorded = 0L
        var outBytesRecorded = 0L

        val proxyServer = LocalProxyServer(
            scope = testScope,
            localPort = 18880,
            remoteSocksPort = targetPort,
            onBytesTransferred = { inB, outB ->
                inBytesRecorded += inB
                outBytesRecorded += outB
            }
        ) {}

        proxyServer.start()
        delay(200)

        val client = Socket()
        client.connect(InetSocketAddress("127.0.0.1", 18880), 2000)
        client.outputStream.write("HOLA MUNDO PROXY".toByteArray())
        client.outputStream.flush()

        val respBuf = ByteArray(64)
        val read = client.inputStream.read(respBuf)
        client.close()
        delay(200)

        assertTrue(read > 0)
        assertEquals("HOLA MUNDO PROXY", String(respBuf, 0, read))
        assertTrue("Debe registrar bytes de subida", proxyServer.totalBytesOut.get() > 0)
        assertTrue("Debe registrar bytes de bajada", proxyServer.totalBytesIn.get() > 0)

        proxyServer.stop()
    }

    @Test
    fun testSoundEffects_PreferencesAndExecution() {
        SoundEffectHelper.setSoundEnabled(context, true)
        assertTrue(SoundEffectHelper.isSoundEnabled(context))

        SoundEffectHelper.playConnectSound(context)
        SoundEffectHelper.playDisconnectSound(context)
        SoundEffectHelper.playErrorSound(context)

        SoundEffectHelper.setSoundEnabled(context, false)
        assertFalse(SoundEffectHelper.isSoundEnabled(context))
    }

    @Test
    fun testAppFilterManager_ModesAndSelection() {
        AppFilterManager.setFilterEnabled(context, true)
        assertTrue(AppFilterManager.isFilterEnabled(context))

        AppFilterManager.setFilterMode(context, "EXCLUDE")
        assertEquals("EXCLUDE", AppFilterManager.getFilterMode(context))

        val testApps = setOf("com.whatsapp", "com.instagram.android")
        AppFilterManager.setSelectedApps(context, testApps)
        val selected = AppFilterManager.getSelectedApps(context)
        assertEquals(2, selected.size)
        assertTrue(selected.contains("com.whatsapp"))
    }
}
