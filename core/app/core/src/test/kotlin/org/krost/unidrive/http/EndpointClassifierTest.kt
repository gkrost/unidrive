package org.krost.unidrive.http

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EndpointClassifierTest {
    private val classifier =
        EndpointClassifier(
            setOf("graph.example.test"),
            setOf("v1.0", "me", "drive", "root", "items", "children", "content"),
        )

    @Test
    fun `ids become a placeholder and the vocabulary stays`() {
        assertEquals(
            "GET /v1.0/me/drive/items/{id}/children",
            classifier.classify("get", "graph.example.test", "/v1.0/me/drive/items/01ABCDEF!123/children"),
        )
    }

    @Test
    fun `a path address is collapsed whatever it holds`() {
        assertEquals(
            "PUT /v1.0/me/drive/root:{path}:/content",
            classifier.classify("PUT", "graph.example.test", "/v1.0/me/drive/root:/Taxes%202025/children/secret.pdf:/content"),
        )
        assertEquals(
            "GET /v1.0/me/drive/root:{path}",
            classifier.classify("GET", "graph.example.test", "/v1.0/me/drive/root:/Taxes/secret.pdf"),
        )
    }

    @Test
    fun `a foreign host is not templated at all`() {
        assertEquals(
            "PUT external",
            classifier.classify("PUT", "contoso-my.sharepoint.test", "/personal/alice_contoso_test/_api/v2.0/drives/b!XYZ/items/children"),
        )
    }

    @Test
    fun `the default classifier hides every path`() {
        assertEquals("GET external", EndpointClassifier.OPAQUE.classify("GET", "graph.example.test", "/v1.0/me/drive"))
    }

    // Property style: random identifiers, names and tokens in every position, and the class may not contain them.
    @Test
    fun `no identifier name or token ever appears in the class`() {
        val random = Random(42)
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_!%."
        fun secret(): String = "S" + (1..random.nextInt(20) + 6).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")

        repeat(500) {
            val id = secret()
            val name = secret() + " " + secret()
            val encodedName = name.replace(" ", "%20")
            val paths =
                listOf(
                    "/v1.0/me/drive/items/$id/children",
                    "/v1.0/me/drive/items/$id",
                    "/v1.0/me/drive/root:/$encodedName/$id:/content",
                    "/v1.0/me/drive/root:/$encodedName",
                    "/v1.0/me/drive/$id/$encodedName/content",
                    "/$id/$encodedName",
                )
            for (path in paths) {
                for (suffix in listOf("", "?token=$id", "?\$skiptoken=$id&name=$encodedName")) {
                    // The caller passes the path only; a query never reaches the classifier, and a path with one still holds nothing.
                    val result = classifier.classify("DELETE", "graph.example.test", path + suffix)
                    assertFalse(id in result, "id leaked: $result")
                    assertFalse(encodedName in result || name in result, "name leaked: $result")
                    assertFalse('?' in result, "query leaked: $result")
                    assertTrue(Regex("""^DELETE /[A-Za-z0-9_.{}:/ -]*$""").matches(result), "unexpected characters: $result")
                }
            }
        }
    }
}
