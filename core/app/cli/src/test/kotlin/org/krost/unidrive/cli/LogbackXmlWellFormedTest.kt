package org.krost.unidrive.cli

import org.xml.sax.InputSource
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * Build-time guard that the bundled `logback.xml` is well-formed XML.
 *
 * Logback's config loader is forgiving: a parse error makes it silently fall
 * back to a BasicConfiguration (console-only, INFO), so a malformed
 * `logback.xml` produces NO build failure and NO obvious runtime symptom —
 * logging just quietly degrades. This bit us during the MDC empty-slot fix,
 * where a `--` literal inside an XML comment (forbidden by XML 1.0 §2.5)
 * slipped through `./gradlew check` entirely.
 *
 * This test parses the resource with a plain JDK `DocumentBuilder` and fails
 * loudly on any `SAXParseException`, closing the "CI green, logging silently
 * broken" gap. It validates *well-formedness* only (not logback semantics) —
 * which is exactly the class of breakage logback hides.
 */
class LogbackXmlWellFormedTest {
    @Test
    fun `bundled logback xml is well-formed`() {
        val stream =
            javaClass.classLoader.getResourceAsStream("logback.xml")
                ?: fail("logback.xml not found on the test classpath (expected in :app:cli main resources)")

        // Harden the parser: no external DTD/entity resolution. Keeps the test
        // offline-deterministic and XXE-safe. logback.xml has no doctype, so
        // forbidding DOCTYPE also guards against one being added blindly later.
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isValidating = false
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }

        val doc =
            stream.use { input ->
                try {
                    factory.newDocumentBuilder().parse(InputSource(input))
                } catch (e: org.xml.sax.SAXParseException) {
                    fail(
                        "logback.xml is not well-formed XML at " +
                            "line ${e.lineNumber}, column ${e.columnNumber}: ${e.message}",
                    )
                }
            }

        assertNotNull(doc.documentElement, "parsed logback.xml has no root element")
        assertNotNull(
            doc.documentElement.takeIf { it.tagName == "configuration" },
            "logback.xml root element must be <configuration>, was <${doc.documentElement.tagName}>",
        )
    }
}
