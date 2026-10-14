package org.krost.unidrive.s3

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4 for S3, header and query (presigned) forms. Built from AWS's published algorithm and checked
 * against its published examples. The canonical request takes the path as it is already encoded (S3 does not encode
 * twice) and the query from the same encoded pairs the URL is built from.
 */
class SigV4Signer(
    private val accessKey: String,
    private val secretKey: String,
    private val region: String,
    private val clock: () -> Instant = Instant::now,
) {
    /**
     * The headers to add to a request: `Authorization`, `x-amz-date` and `x-amz-content-sha256`. [headers] are the
     * request's own headers to sign besides `host` (names in any case). [query] is the already encoded pairs.
     */
    fun sign(
        method: String,
        host: String,
        encodedPath: String,
        query: List<Pair<String, String>>,
        headers: Map<String, String>,
        payloadHash: String,
    ): Map<String, String> {
        val now = clock()
        val amzDate = AMZ_DATE.format(now)
        val day = DAY.format(now)
        val signed =
            sortedMapOf<String, String>().apply {
                put("host", host)
                put("x-amz-content-sha256", payloadHash)
                put("x-amz-date", amzDate)
                headers.forEach { (k, v) -> put(k.lowercase(), v.trim().replace(SPACES, " ")) }
            }
        val signedNames = signed.keys.joinToString(";")
        val canonical =
            listOf(
                method.uppercase(),
                encodedPath.ifEmpty { "/" },
                canonicalQuery(query),
                signed.entries.joinToString("") { "${it.key}:${it.value}\n" },
                signedNames,
                payloadHash,
            ).joinToString("\n")
        val scope = "$day/$region/s3/aws4_request"
        val signature = hex(hmac(signingKey(day), stringToSign(amzDate, scope, canonical)))
        return mapOf(
            "Authorization" to "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, SignedHeaders=$signedNames, Signature=$signature",
            "x-amz-date" to amzDate,
            "x-amz-content-sha256" to payloadHash,
        )
    }

    /** The query of a presigned GET valid for [expiresSeconds] (at most seven days), signature last. */
    fun presignQuery(
        host: String,
        encodedPath: String,
        expiresSeconds: Long,
    ): List<Pair<String, String>> {
        val now = clock()
        val amzDate = AMZ_DATE.format(now)
        val day = DAY.format(now)
        val scope = "$day/$region/s3/aws4_request"
        val query =
            listOf(
                "X-Amz-Algorithm" to "AWS4-HMAC-SHA256",
                "X-Amz-Credential" to uriEncode("$accessKey/$scope"),
                "X-Amz-Date" to amzDate,
                "X-Amz-Expires" to expiresSeconds.toString(),
                "X-Amz-SignedHeaders" to "host",
            )
        val canonical = listOf("GET", encodedPath, canonicalQuery(query), "host:$host\n", "host", UNSIGNED_PAYLOAD).joinToString("\n")
        val signature = hex(hmac(signingKey(day), stringToSign(amzDate, scope, canonical)))
        return query + ("X-Amz-Signature" to signature)
    }

    private fun stringToSign(
        amzDate: String,
        scope: String,
        canonical: String,
    ) = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n${sha256Hex(canonical.toByteArray(Charsets.UTF_8))}"

    private fun signingKey(day: String): ByteArray =
        hmac(hmac(hmac(hmac("AWS4$secretKey".toByteArray(Charsets.UTF_8), day), region), "s3"), "aws4_request")

    companion object {
        const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"
        val EMPTY_SHA256: String = sha256Hex(ByteArray(0))

        private val AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        private val DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)
        private val SPACES = Regex(" +")
        private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"

        /** AWS's URI encoding: everything but the unreserved characters as `%XX` of its UTF-8 bytes; `/` kept when [keepSlash]. */
        fun uriEncode(
            value: String,
            keepSlash: Boolean = false,
        ): String {
            val out = StringBuilder(value.length + 16)
            for (b in value.toByteArray(Charsets.UTF_8)) {
                val c = (b.toInt() and 0xFF).toChar()
                if (c in UNRESERVED || (keepSlash && c == '/')) {
                    out.append(c)
                } else {
                    out.append('%').append(HEX_UPPER[(b.toInt() shr 4) and 0xF]).append(HEX_UPPER[b.toInt() and 0xF])
                }
            }
            return out.toString()
        }

        private const val HEX_UPPER = "0123456789ABCDEF"

        private fun canonicalQuery(query: List<Pair<String, String>>): String =
            query.sortedWith(compareBy({ it.first }, { it.second })).joinToString("&") { "${it.first}=${it.second}" }

        fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

        private fun hmac(
            key: ByteArray,
            data: String,
        ): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(data.toByteArray(Charsets.UTF_8))
            }

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}
