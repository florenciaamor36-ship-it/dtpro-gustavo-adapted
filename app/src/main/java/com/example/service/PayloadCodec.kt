package com.example.service

import java.nio.charset.StandardCharsets

/**
 * PayloadCodec handles expanding payload templates into raw byte streams and parsing HTTP responses.
 * Preserves exact bytes, line breaks, spaces, header casing, and split boundaries.
 */
object PayloadCodec {
    private const val DEFAULT_UA = "Mozilla/5.0 (Android; Mobile; rv:120.0) Gecko/120.0 Firefox/120.0"

    /**
     * Expands payload tokens without altering literal characters, header casing, or spacing.
     * Tokens resolved: [crlf], [lf], [cr], [split], [ua], [host], [port], [host_port], [method], [protocol]
     */
    fun expand(
        template: String,
        host: String,
        port: Int,
        userAgent: String = DEFAULT_UA,
        defaultMethod: String = "GET"
    ): ByteArray {
        val blocks = expandBlocks(template, host, port, userAgent, defaultMethod)
        val totalSize = blocks.sumOf { it.size }
        val result = ByteArray(totalSize)
        var offset = 0
        for (block in blocks) {
            System.arraycopy(block, 0, result, offset, block.size)
            offset += block.size
        }
        return result
    }

    /**
     * Splits payload template by [split] and resolves tokens for each block separately.
     */
    fun expandBlocks(
        template: String,
        host: String,
        port: Int,
        userAgent: String = DEFAULT_UA,
        defaultMethod: String = "GET"
    ): List<ByteArray> {
        val protocol = if (port == 443) "HTTPS" else "HTTP/1.1"
        val effectiveUa = userAgent.ifBlank { DEFAULT_UA }
        val replacements = mapOf(
            "[host]" to host,
            "[host_port]" to "$host:$port",
            "[port]" to port.toString(),
            "[method]" to defaultMethod,
            "[protocol]" to protocol,
            "[ua]" to effectiveUa
        )

        // Split by [split] boundary case-insensitively
        val rawBlocks = template.split(Regex("\\[split\\]", RegexOption.IGNORE_CASE))
        return rawBlocks.map { rawBlock ->
            var value = rawBlock
                .replace("[crlf]", "\r\n", ignoreCase = true)
                .replace("[lf]", "\n", ignoreCase = true)
                .replace("[cr]", "\r", ignoreCase = true)
            replacements.forEach { (key, replacement) ->
                value = value.replace(key, replacement, ignoreCase = true)
            }
            value.toByteArray(StandardCharsets.ISO_8859_1)
        }
    }

    /**
     * Parses an HTTP status response byte array.
     * Strict rules:
     * - 200, 101, 20x -> Success
     * - 403, 407, 4xx, 5xx -> Rejected
     * - Incomplete / malformed -> Incomplete or Invalid
     */
    fun parseStatus(response: ByteArray): HttpStatus {
        val headerEnd = response.indexOfHeaderEnd()
        if (headerEnd < 0) return HttpStatus.Incomplete
        val header = response.copyOfRange(0, headerEnd).toString(StandardCharsets.ISO_8859_1)
        val firstLine = header.lineSequence().firstOrNull()?.trim() ?: return HttpStatus.Invalid
        val match = Regex("^HTTP/\\d(?:\\.\\d)?\\s+(\\d{3})(?:\\s+(.*))?$", RegexOption.IGNORE_CASE).find(firstLine)
            ?: return HttpStatus.Invalid

        val code = match.groupValues[1].toInt()
        val reason = match.groupValues[2].ifBlank { "Status $code" }

        return when {
            code == 101 || code == 200 || code in 201..299 -> HttpStatus.Success(code, firstLine)
            code == 403 || code == 407 || code in 400..599 -> HttpStatus.Rejected(code, firstLine)
            else -> HttpStatus.Other(code, firstLine)
        }
    }

    private fun ByteArray.indexOfHeaderEnd(): Int {
        for (i in 0 until size - 3) {
            if (this[i] == '\r'.code.toByte() && this[i + 1] == '\n'.code.toByte() &&
                this[i + 2] == '\r'.code.toByte() && this[i + 3] == '\n'.code.toByte()) return i + 4
        }
        for (i in 0 until size - 1) {
            if (this[i] == '\n'.code.toByte() && this[i + 1] == '\n'.code.toByte()) return i + 2
        }
        return -1
    }
}

sealed interface HttpStatus {
    data object Incomplete : HttpStatus
    data object Invalid : HttpStatus
    data class Success(val code: Int, val statusLine: String) : HttpStatus
    data class Rejected(val code: Int, val statusLine: String) : HttpStatus
    data class Other(val code: Int, val statusLine: String) : HttpStatus
}
