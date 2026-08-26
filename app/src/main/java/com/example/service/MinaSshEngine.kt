package com.example.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.auth.keyboard.UserInteraction
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.digest.BuiltinDigests
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.common.util.security.SecurityUtils
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Main Apache MINA SSHD Engine for La Clave Argentina.
 * Handles SSH client lifecycle, authentication (password, private key, keyboard-interactive),
 * strict server host key verification, TCP port forwarding, and session state.
 */
class MinaSshEngine(
    private val hostKeyApprovalCallback: ((host: String, port: Int, fingerprint: String) -> Boolean)? = null,
    private val logger: (String) -> Unit = {}
) : Closeable {

    private var client: SshClient? = null
    private var session: ClientSession? = null

    suspend fun connect(
        host: String,
        port: Int,
        username: String,
        password: String = "",
        privateKey: String = "",
        passphrase: String = "",
        existingSocket: Socket? = null,
        timeoutMillis: Long = 20_000
    ): ClientSession = withContext(Dispatchers.IO) {
        require(host.isNotBlank()) { "SSH host is blank" }
        require(username.isNotBlank()) { "SSH username is blank" }
        require(port in 1..65535) { "SSH port is invalid" }
        close()

        val ssh = SshClient.setUpDefaultClient()

        // Strict host key verifier
        val verifier = ServerKeyVerifier { clientSession, _, serverKey ->
            val fp = KeyUtils.getFingerPrint(BuiltinDigests.sha256, serverKey) ?: "UNKNOWN"
            val knownFp = HostKeyRepository.getKnownFingerprint(host, port)
            if (knownFp != null) {
                if (knownFp == fp) {
                    logger("✓ Huella del servidor SSH verificada ($fp)")
                    true
                } else {
                    logger("⛔ ALERTA DE SEGURIDAD: La huella del servidor ($fp) ha cambiado respecto a la guardada ($knownFp). Conexión rechazada.")
                    false
                }
            } else {
                val approved = hostKeyApprovalCallback?.invoke(host, port, fp) ?: false
                if (approved) {
                    HostKeyRepository.saveFingerprint(host, port, fp)
                    logger("✓ Huella de servidor aceptada y almacenada: $fp")
                    true
                } else {
                    logger("⛔ Huella de servidor desconocida ($fp). Conexión rechazada por políticas de seguridad.")
                    false
                }
            }
        }
        ssh.serverKeyVerifier = verifier

        // Keyboard-interactive authentication handler
        if (password.isNotEmpty()) {
            ssh.userInteraction = object : UserInteraction {
                override fun welcome(session: ClientSession?, banner: String?, lang: String?) {}
                override fun interactive(
                    session: ClientSession?,
                    name: String?,
                    instruction: String?,
                    lang: String?,
                    prompt: Array<out String>?,
                    echo: BooleanArray?
                ): Array<String> {
                    return Array(prompt?.size ?: 0) { password }
                }

                override fun getUpdatedPassword(session: ClientSession?, prompt: String?, lang: String?): String {
                    return password
                }
            }
        }

        client = ssh
        try {
            ssh.start()
            logger("Iniciando conexión SSH con Apache MINA SSHD a $host:$port...")

            val connected = ssh.connect(username, host, port)
                .verify(timeoutMillis)
                .session

            session = connected

            // Authentications: Private Key and/or Password
            if (privateKey.isNotBlank()) {
                try {
                    val passwordProvider = FilePasswordProvider { _, _, _ -> passphrase }
                    val keyPairs = SecurityUtils.loadKeyPairIdentities(
                        null,
                        null,
                        ByteArrayInputStream(privateKey.toByteArray(StandardCharsets.UTF_8)),
                        passwordProvider
                    )?.toList() ?: emptyList()

                    if (keyPairs.isNotEmpty()) {
                        keyPairs.forEach { kp ->
                            connected.addPublicKeyIdentity(kp)
                        }
                        logger("Llave privada configurada para autenticación SSH.")
                    }
                } catch (e: Exception) {
                    logger("Error cargando llave privada SSH: ${e.message}")
                    throw IllegalArgumentException("Error cargando la llave privada SSH: ${e.message}", e)
                }
            }

            if (password.isNotEmpty()) {
                connected.addPasswordIdentity(password)
            }

            logger("Enviando credenciales de autenticación SSH...")
            connected.auth().verify(timeoutMillis)
            logger("✓ MINA SSHD autenticado correctamente.")
            return@withContext connected
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    fun startDynamicPortForwarding(localPort: Int): SshdSocketAddress? {
        val s = session ?: throw IllegalStateException("Sesión SSH no activa.")
        return s.startDynamicPortForwarding(SshdSocketAddress("127.0.0.1", localPort))
    }

    fun startLocalPortForwarding(localPort: Int, remoteHost: String, remotePort: Int): SshdSocketAddress? {
        val s = session ?: throw IllegalStateException("Sesión SSH no activa.")
        return s.startLocalPortForwarding(
            SshdSocketAddress("127.0.0.1", localPort),
            SshdSocketAddress(remoteHost, remotePort)
        )
    }

    fun isConnected(): Boolean = session?.isOpen == true && session?.isAuthenticated == true

    fun currentSession(): ClientSession? = session

    override fun close() {
        try { session?.close(false) } catch (_: Throwable) {}
        try { client?.stop() } catch (_: Throwable) {}
        session = null
        client = null
    }
}
