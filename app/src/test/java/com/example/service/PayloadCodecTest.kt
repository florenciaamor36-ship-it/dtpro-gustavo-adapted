package com.example.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadCodecTest {
    @Test
    fun expandsTokensAndPreservesHttpBytes() {
        val actual = PayloadCodec.expand(
            "GET /x[crlf]Host: [host][crlf][crlf][split]",
            "front.example.test", 80, "DtproTest/1.0"
        )
        assertArrayEquals(
            "GET /x\r\nHost: front.example.test\r\n\r\n".toByteArray(Charsets.ISO_8859_1), actual
        )
    }

    @Test
    fun parsesHttpStatusesCorrectly() {
        val status101 = PayloadCodec.parseStatus("HTTP/1.1 101 Switching Protocols\r\n\r\n".toByteArray())
        assertTrue(status101 is HttpStatus.Success)
        assertEquals(101, (status101 as HttpStatus.Success).code)

        val status403 = PayloadCodec.parseStatus("HTTP/1.1 403 Forbidden\r\n\r\n".toByteArray())
        assertTrue(status403 is HttpStatus.Rejected)
        assertEquals(403, (status403 as HttpStatus.Rejected).code)

        val incomplete = PayloadCodec.parseStatus("HTTP/1.1 101 Switching".toByteArray())
        assertEquals(HttpStatus.Incomplete, incomplete)
    }
}
