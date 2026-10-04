package org.krost.unidrive.internxt

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * The TLS side of the loopback tests. The certificate is generated here, once per JVM, and never leaves it: no keystore is
 * committed. The client trusts every certificate; the server's is for 127.0.0.1 only because the client checks the name.
 */
internal object LoopbackTls {
    private val password = "changeit".toCharArray()

    val serverSocketFactory: SSLServerSocketFactory by lazy {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val store =
            KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry("loopback", keyPair.private, password, arrayOf(selfSigned(keyPair)))
            }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }.serverSocketFactory
    }

    val trustAll: X509TrustManager =
        object : X509TrustManager {
            override fun checkClientTrusted(
                chain: Array<X509Certificate>?,
                authType: String?,
            ) {}

            override fun checkServerTrusted(
                chain: Array<X509Certificate>?,
                authType: String?,
            ) {}

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

    // A self-signed X.509 v3 certificate for 127.0.0.1, valid for a day around now, written as DER by hand: the JDK can parse a
    // certificate but only its internal classes can build one, and a test is no reason to open them.
    private fun selfSigned(keyPair: KeyPair): X509Certificate {
        val signatureAlgorithm = der(0x30, der(0x06, 0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x0B), byteArrayOf(0x05, 0x00)) // sha256WithRSA
        val name = der(0x30, der(0x31, der(0x30, der(0x06, 0x55, 0x04, 0x03), der(0x0C, "localhost".toByteArray())))) // CN=localhost
        val utc = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").withZone(ZoneOffset.UTC)
        val now = Instant.now()
        val validity =
            der(
                0x30,
                der(0x17, utc.format(now - Duration.ofDays(1)).toByteArray()),
                der(0x17, utc.format(now + Duration.ofDays(1)).toByteArray()),
            )
        val altNames = der(0x30, der(0x87, 127, 0, 0, 1)) // iPAddress 127.0.0.1
        val extensions = der(0xA3, der(0x30, der(0x30, der(0x06, 0x55, 0x1D, 0x11), der(0x04, altNames)))) // subjectAltName
        val tbs =
            der(
                0x30,
                der(0xA0, der(0x02, 2)), // version 3
                der(0x02, BigInteger(64, SecureRandom()).add(BigInteger.ONE).toByteArray()),
                signatureAlgorithm,
                name,
                validity,
                name,
                keyPair.public.encoded,
                extensions,
            )
        val signature =
            Signature.getInstance("SHA256withRSA").run {
                initSign(keyPair.private)
                update(tbs)
                sign()
            }
        val certificate = der(0x30, tbs, signatureAlgorithm, der(0x03, byteArrayOf(0) + signature))
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certificate)) as X509Certificate
    }

    private fun der(
        tag: Int,
        vararg content: Int,
    ): ByteArray = der(tag, ByteArray(content.size) { content[it].toByte() })

    private fun der(
        tag: Int,
        vararg parts: ByteArray,
    ): ByteArray {
        val body = parts.fold(ByteArray(0)) { all, part -> all + part }
        val length =
            when {
                body.size < 0x80 -> byteArrayOf(body.size.toByte())
                body.size < 0x100 -> byteArrayOf(0x81.toByte(), body.size.toByte())
                else -> byteArrayOf(0x82.toByte(), (body.size shr 8).toByte(), body.size.toByte())
            }
        return byteArrayOf(tag.toByte()) + length + body
    }
}
