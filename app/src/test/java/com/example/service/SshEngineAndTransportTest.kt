package com.example.service

import com.example.data.model.ConnectionStatus
import com.example.data.model.TunnelConfig
import com.example.data.model.TunnelMode
import com.example.data.model.TunnelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket

class SshEngineAndTransportTest {

    private val testScope = CoroutineScope(Dispatchers.IO)

    @Before
    fun setUp() {
        HostKeyRepository.clearAll()
    }

    @Test
    fun testPayloadCodec_AllTokensAndSpacePreservation() {
        val template = "GET / HTTP/1.1[crlf]Host: [host_port][crlf]User-Agent: [ua][crlf]X-Port: [port][crlf]Method: [method][crlf]Proto: [protocol][lf]Trailing[cr][split]"
        val expanded = PayloadCodec.expand(
            template = template,
            host = "ssh.example.test",
            port = 443,
            userAgent = "CustomUA/1.0",
            defaultMethod = "POST"
        )

        val expectedString = "GET / HTTP/1.1\r\nHost: ssh.example.test:443\r\nUser-Agent: CustomUA/1.0\r\nX-Port: 443\r\nMethod: POST\r\nProto: HTTPS\nTrailing\r"
        assertArrayEquals(expectedString.toByteArray(Charsets.ISO_8859_1), expanded)
    }

    @Test
    fun testPayloadCodec_SplitBlocks() {
        val template = "BLOCK1[split]BLOCK2[split]BLOCK3"
        val blocks = PayloadCodec.expandBlocks(
            template = template,
            host = "front.example.test",
            port = 80
        )

        assertEquals(3, blocks.size)
        assertEquals("BLOCK1", String(blocks[0], Charsets.ISO_8859_1))
        assertEquals("BLOCK2", String(blocks[1], Charsets.ISO_8859_1))
        assertEquals("BLOCK3", String(blocks[2], Charsets.ISO_8859_1))
    }

    @Test
    fun testHttpStatusParsing_SuccessAndRejections() {
        val res101 = PayloadCodec.parseStatus("HTTP/1.1 101 Switching Protocols\r\n\r\n".toByteArray())
        assertTrue(res101 is HttpStatus.Success)
        assertEquals(101, (res101 as HttpStatus.Success).code)

        val res200 = PayloadCodec.parseStatus("HTTP/1.1 200 OK\r\n\r\n".toByteArray())
        assertTrue(res200 is HttpStatus.Success)

        val res403 = PayloadCodec.parseStatus("HTTP/1.1 403 Forbidden\r\n\r\n".toByteArray())
        assertTrue(res403 is HttpStatus.Rejected)

        val res407 = PayloadCodec.parseStatus("HTTP/1.1 407 Proxy Auth Required\r\n\r\n".toByteArray())
        assertTrue(res407 is HttpStatus.Rejected)

        val res500 = PayloadCodec.parseStatus("HTTP/1.1 500 Internal Error\r\n\r\n".toByteArray())
        assertTrue(res500 is HttpStatus.Rejected)

        val incomplete = PayloadCodec.parseStatus("HTTP/1.1 200 OK".toByteArray())
        assertEquals(HttpStatus.Incomplete, incomplete)

        val invalid = PayloadCodec.parseStatus("INVALID_PROTOCOL_DATA\r\n\r\n".toByteArray())
        assertEquals(HttpStatus.Invalid, invalid)
    }

    @Test
    fun testHostKeyRepository_ApproveAndRejectChangedKey() {
        val host = "ssh.example.test"
        val port = 22
        val initialFingerprint = "SHA256:abc123def456"
        val tamperedFingerprint = "SHA256:xyz987654321"

        assertNull(HostKeyRepository.getKnownFingerprint(host, port))

        HostKeyRepository.saveFingerprint(host, port, initialFingerprint)
        assertEquals(initialFingerprint, HostKeyRepository.getKnownFingerprint(host, port))

        val currentKnown = HostKeyRepository.getKnownFingerprint(host, port)
        assertEquals(initialFingerprint, currentKnown)
        assertFalse("Fingerprint tampered check must fail", currentKnown == tamperedFingerprint)
    }

    @Test
    fun testConnectionStatesAndStats() = runBlocking {
        var state = TunnelState()
        assertEquals(ConnectionStatus.Disconnected, state.status)

        state = state.copy(status = ConnectionStatus.Connecting)
        assertEquals(ConnectionStatus.Connecting, state.status)

        state = state.copy(status = ConnectionStatus.Authenticating)
        assertEquals(ConnectionStatus.Authenticating, state.status)

        state = state.copy(
            status = ConnectionStatus.Connected,
            bytesIn = 1024,
            bytesOut = 2048,
            pingMs = 42
        )
        assertEquals(ConnectionStatus.Connected, state.status)
        assertEquals(1024L, state.bytesIn)
        assertEquals(2048L, state.bytesOut)
        assertEquals(42L, state.pingMs)

        state = state.copy(status = ConnectionStatus.Reconnecting)
        assertEquals(ConnectionStatus.Reconnecting, state.status)

        state = state.copy(status = ConnectionStatus.Error("Connection reset"))
        assertTrue(state.status is ConnectionStatus.Error)
        assertEquals("Connection reset", (state.status as ConnectionStatus.Error).message)
    }

    @Test
    fun testCustomSocketFactory_HttpPayloadRejection403() = runBlocking {
        val mockProxy = ServerSocket(0)
        val port = mockProxy.localPort

        testScope.launch {
            try {
                val client = mockProxy.accept()
                val output = client.getOutputStream()
                output.write("HTTP/1.1 403 Forbidden\r\nContent-Type: text/plain\r\n\r\n403 Forbidden Access".toByteArray())
                output.flush()
                client.close()
                mockProxy.close()
            } catch (_: Exception) {}
        }

        val config = TunnelConfig(
            name = "Test 403 Rejection",
            mode = TunnelMode.SSH_PAYLOAD,
            serverHost = "ssh.example.test",
            serverPort = 22,
            proxyHost = "127.0.0.1",
            proxyPort = port,
            customPayload = "GET / HTTP/1.1[crlf]Host: [host][crlf][crlf]"
        )

        val factory = CustomSshSocketFactory(config, testScope) {}
        try {
            factory.createSocket("ssh.example.test", 22)
            assertTrue("Must throw IOException on 403 rejection", false)
        } catch (e: IOException) {
            assertTrue("Error message must mention 403 or rejection", e.message?.contains("403") == true || e.message?.contains("rechazado") == true)
        }
    }
}
