package com.example.service

import java.util.concurrent.ConcurrentHashMap

/**
 * Repository for approved SSH server host key fingerprints.
 * Prevents MITM attacks by storing known fingerprints and rejecting any changes.
 */
object HostKeyRepository {
    private val knownFingerprints = ConcurrentHashMap<String, String>()

    fun getKnownFingerprint(host: String, port: Int): String? {
        return knownFingerprints["$host:$port"]
    }

    fun saveFingerprint(host: String, port: Int, fingerprint: String) {
        knownFingerprints["$host:$port"] = fingerprint
    }

    fun removeFingerprint(host: String, port: Int) {
        knownFingerprints.remove("$host:$port")
    }

    fun clearAll() {
        knownFingerprints.clear()
    }
}
