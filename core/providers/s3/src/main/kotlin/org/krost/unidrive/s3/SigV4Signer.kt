package org.krost.unidrive.s3

import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4 request signer.
 *
 * Produces the Authorization header and the X-Amz-Date header value for an
 * HTTP request to any S3 or S3-compatible endpoint.
 *
 * Reference: https://docs.aws.amazon.com/IAM/latest/UserGuide/create-signed-request.html
 */
object SigV4Signer {
    private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    private val SHORT_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")

    /**
     * Sign a request and return the headers that must be added.
     *
     * @param method      HTTP method (GET, PUT, DELETE, …)
     * @param url         Full request URL including query string
     * @param region      AWS region (e.g. "eu-central-1" or "auto")
     * @param accessKey   AWS access key ID
     * @param secretKey   AWS secret access key
     * @param bodyHash    Hex-encoded SHA-256 of the request body, or
     *                    "UNSIGNED-PAYLOAD" for streaming / pre-signed URLs.
     *                    Use [sha256Hex] to compute it.
     * @param extraHeaders Additional headers to include in the signature
     *                    (key → value, lowercase keys).
     * @return Map of header names → values to add to the request.
     */
    fun sign(
        method: String,
        url: String,
        region: String,
        accessKey: String,
        secretKey: String,
        bodyHash: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val now = ZonedDateTime.now(ZoneOffset.UTC)
        val amzDate = DATE_FORMAT.format(now)
        val shortDate = SHORT_DATE_FORMAT.format(now)

        val parsedUrl = java.net.URI(url)
        val host = parsedUrl.host + if (parsedUrl.port != -1) ":${parsedUrl.port}" else ""
        val path = parsedUrl.rawPath.ifEmpty { "/" }
        val query = parsedUrl.rawQuery ?: ""

        // Canonical headers: host + x-amz-date + body-hash + extra
        val headers =
            sortedMapOf<String, String>().apply {
                put("host", host)
                put("x-amz-content-sha256", bodyHash)
                put("x-amz-date", amzDate)
                putAll(extraHeaders.map { (k, v) -> k.lowercase() to v })
            }

        val signedHeaders = headers.keys.joinToString(";")
        val canonicalHeaders = headers.entries.joinToString("\n") { "${it.key}:${it.value}" } + "\n"

        val canonicalRequest =
            listOf(
                method.uppercase(),
                path,
                canonicalQuery(query),
                canonicalHeaders,
                signedHeaders,
                bodyHash,
            ).joinToString("\n")

        val scope = "$shortDate/$region/s3/aws4_request"
        val stringToSign =
            listOf(
                "AWS4-HMAC-SHA256",
                amzDate,
                scope,
                sha256Hex(canonicalRequest.toByteArray()),
            ).joinToString("\n")

        val signingKey =
            hmacSha256(
                hmacSha256(
                    hmacSha256(
                        hmacSha256(
                            "AWS4$secretKey".toByteArray(),
                            shortDate,
                        ),
                        region,
                    ),
                    "s3",
                ),
                "aws4_request",
            )
        val signature = toHex(hmacSha256(signingKey, stringToSign))

        val authorization =
            "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, " +
                "SignedHeaders=$signedHeaders, " +
                "Signature=$signature"

        return buildMap {
            put("Authorization", authorization)
            put("X-Amz-Date", amzDate)
            put("X-Amz-Content-Sha256", bodyHash)
            putAll(extraHeaders)
        }
    }

    /** SHA-256 of [input] as lowercase hex string. */
    fun sha256Hex(input: ByteArray): String = toHex(MessageDigest.getInstance("SHA-256").digest(input))

    /** SHA-256 of empty body — reused for GET/DELETE requests. */
    val EMPTY_BODY_HASH: String = sha256Hex(ByteArray(0))

    private fun canonicalQuery(rawQuery: String): String {
        if (rawQuery.isBlank()) return ""
        return rawQuery
            .split("&")
            .map { it.split("=", limit = 2).let { p -> (p[0] to (p.getOrElse(1) { "" })) } }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { "${it.first}=${it.second}" }
    }

    private fun hmacSha256(
        key: ByteArray,
        data: String,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    fun hmacSha256(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun hmacSha256Hex(
        key: ByteArray,
        data: String,
    ): String = toHex(hmacSha256(key, data.toByteArray(Charsets.UTF_8)))

    private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
