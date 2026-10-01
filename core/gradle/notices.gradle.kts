// Third-party notices for the shaded CLI jar.
//
// The shadow jar bundles every module on :app:cli's runtimeClasspath, but the shadow
// plugin keeps only the first META-INF/LICENSE* / NOTICE* of any name, so the licence
// and NOTICE texts that Apache-2.0, MIT, EPL and LGPL require us to carry along were
// silently dropped. This script derives the notices from the same runtimeClasspath that
// is shaded, so the file cannot drift from what is actually shipped:
//
//   generateThirdPartyNotices  -> build/notices/THIRD-PARTY-NOTICES.txt
//   shadowJar                  -> embeds it as META-INF/THIRD-PARTY-NOTICES.txt, plus the
//                                 project LICENSE / NOTICE as META-INF/*-unidrive.txt
//   copyThirdPartyNotices      -> writes THIRD-PARTY-NOTICES.txt next to the jar
//   verifyThirdPartyNotices    -> part of `check`; fails when a bundled module (or its
//                                 licence text) is missing from the file inside the jar
//
// Licence names and URLs come from each module's POM (parent POMs are followed). Full
// texts live in core/gradle/notices/licenses/<id>.txt; Apache-2.0 is the repo's own
// LICENSE. A module whose POM declares no recognisable licence fails the build until it
// is classified in `licenseOverrides` below.

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import org.w3c.dom.Element

val noticesRepoRoot: File = rootProject.projectDir.parentFile
val noticesLicenseDir: File = rootProject.file("gradle/notices/licenses")
val noticesOutputDir = layout.buildDirectory.dir("notices")
val noticesOutputFile = noticesOutputDir.map { it.file("THIRD-PARTY-NOTICES.txt") }
val noticesRuntimeClasspath = configurations.getByName("runtimeClasspath")
val noticesDependencies = dependencies

// Licence ids whose full text is not a file under gradle/notices/licenses.
val noticesLicenseTextFiles: Map<String, File> = mapOf("Apache-2.0" to File(noticesRepoRoot, "LICENSE"))

// Modules whose POM licence metadata is missing or wrong, keyed "group:artifact".
// Value: "id|display name|url" entries.
val licenseOverrides: Map<String, List<String>> =
    mapOf(
        // Versions from 20231013 on are public domain; see
        // https://github.com/stleary/JSON-java/blob/master/LICENSE
        "org.json:json" to
            listOf("Public-Domain|Public Domain|https://github.com/stleary/JSON-java/blob/master/LICENSE"),
    )

// Extra statement printed for specific modules, keyed "group:artifact" prefix.
val noticesRemarks: Map<String, String> =
    mapOf(
        "ch.qos.logback:" to
            "Dual-licensed (EPL-2.0 or LGPL-2.1-only); UniDrive uses it under the EPL-2.0. " +
            "Unmodified binaries; source: https://github.com/qos-ch/logback",
        "org.json:json" to
            "Public domain from version 20231013 on. Source: https://github.com/stleary/JSON-java",
        "org.xerial:sqlite-jdbc" to
            "Bundles native SQLite builds, which are in the public domain (https://sqlite.org/copyright.html).",
    )

class NoticeLicense(
    val id: String,
    val name: String,
    val url: String,
)

class NoticeModule(
    val group: String,
    val name: String,
    val version: String,
    val file: File,
) {
    val gav get() = "$group:$name:$version"
    val ga get() = "$group:$name"
}

fun normaliseLicenseId(
    name: String,
    url: String,
): String? {
    val n = name.lowercase()
    val u = url.lowercase()
    return when {
        "apache" in n || "apache.org/licenses/license-2.0" in u -> "Apache-2.0"
        n.trim() == "mit" || "mit license" in n || "opensource.org/licenses/mit" in u || "mit-license" in u -> "MIT"
        "epl-2.0" in n || "eclipse public license 2.0" in n || "eclipse public license, version 2.0" in n || "epl-v20" in u -> "EPL-2.0"
        "epl-1.0" in n || "eclipse public license 1.0" in n || "eclipse public license - v 1.0" in n || "epl-v10" in u -> "EPL-1.0"
        "lgpl-2.1" in n || ("lesser general public license" in n && "2.1" in n) || "lgpl-2.1" in u || "lgpl-2.1.html" in u -> "LGPL-2.1"
        "public domain" in n -> "Public-Domain"
        else -> null
    }
}

fun readXml(file: File): Element =
    DocumentBuilderFactory
        .newInstance()
        .newDocumentBuilder()
        .parse(file)
        .documentElement

fun Element.childElements(tag: String): List<Element> =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

fun Element.childText(tag: String): String? =
    childElements(tag)
        .firstOrNull()
        ?.textContent
        ?.trim()

fun bundledModules(): List<NoticeModule> =
    noticesRuntimeClasspath.incoming
        .artifactView { componentFilter { it is ModuleComponentIdentifier } }
        .artifacts.artifacts
        .map { a ->
            val id = a.id.componentIdentifier as ModuleComponentIdentifier
            NoticeModule(id.group, id.module, id.version, a.file)
        }.distinctBy { it.gav }
        .sortedBy { it.gav }

// POM files for the given modules, plus every parent POM they (transitively) point at.
fun resolvePoms(modules: Collection<NoticeModule>): Map<String, File> {
    val poms = LinkedHashMap<String, File>()
    var pending: List<Triple<String, String, String>> = modules.map { Triple(it.group, it.name, it.version) }
    while (pending.isNotEmpty()) {
        var query = noticesDependencies.createArtifactResolutionQuery()
        pending.forEach { (g, a, v) -> query = query.forModule(g, a, v) }
        val result = query.withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java).execute()
        val next = ArrayList<Triple<String, String, String>>()
        for (component in result.resolvedComponents) {
            val id = component.id as ModuleComponentIdentifier
            val key = "${id.group}:${id.module}:${id.version}"
            val pom =
                component
                    .getArtifacts(MavenPomArtifact::class.java)
                    .filterIsInstance<ResolvedArtifactResult>()
                    .firstOrNull()
                    ?.file ?: throw GradleException("No POM resolvable for $key; cannot determine its licence")
            poms[key] = pom
            val parent = readXml(pom).childElements("parent").firstOrNull() ?: continue
            val pg = parent.childText("groupId") ?: continue
            val pa = parent.childText("artifactId") ?: continue
            var pv = parent.childText("version") ?: continue
            if ('$' in pv) pv = id.version
            if ("$pg:$pa:$pv" !in poms) next.add(Triple(pg, pa, pv))
        }
        val unresolved = pending.filter { (g, a, v) -> "$g:$a:$v" !in poms && next.none { it == Triple(g, a, v) } }
        if (unresolved.isNotEmpty()) {
            throw GradleException("POM not found for ${unresolved.joinToString { "${it.first}:${it.second}:${it.third}" }}")
        }
        pending = next.distinct()
    }
    return poms
}

// First licenses block found on the module's own POM or, failing that, its parent chain.
fun pomLicenses(
    module: NoticeModule,
    poms: Map<String, File>,
): List<NoticeLicense> {
    var key: String? = module.gav
    val seen = HashSet<String>()
    while (key != null && seen.add(key)) {
        val root = readXml(poms[key] ?: break)
        val found =
            root
                .childElements("licenses")
                .flatMap { it.childElements("license") }
                .map { l ->
                    val name = l.childText("name") ?: ""
                    val url = l.childText("url") ?: ""
                    val id =
                        normaliseLicenseId(name, url)
                            ?: throw GradleException(
                                "${module.gav}: unrecognised licence '$name' ($url). " +
                                    "Add it to normaliseLicenseId / licenseOverrides in core/gradle/notices.gradle.kts " +
                                    "and provide gradle/notices/licenses/<id>.txt",
                            )
                    NoticeLicense(id, name.ifBlank { id }, url)
                }
        if (found.isNotEmpty()) return found
        val parent = root.childElements("parent").firstOrNull()
        key =
            parent?.let {
                val pv = it.childText("version")?.takeIf { v -> '$' !in v } ?: module.version
                "${it.childText("groupId")}:${it.childText("artifactId")}:$pv"
            }
    }
    return emptyList()
}

fun moduleLicenses(
    module: NoticeModule,
    poms: Map<String, File>,
): List<NoticeLicense> {
    licenseOverrides[module.ga]?.let { entries ->
        return entries.map { e ->
            val (id, name, url) = e.split('|')
            NoticeLicense(id, name, url)
        }
    }
    return pomLicenses(module, poms).distinctBy { it.id }
}

fun pomProjectUrl(
    module: NoticeModule,
    poms: Map<String, File>,
): String? {
    var key: String? = module.gav
    val seen = HashSet<String>()
    while (key != null && seen.add(key)) {
        val root = readXml(poms[key] ?: break)
        root.childText("url")?.takeIf { it.isNotBlank() && '$' !in it }?.let { return it }
        val parent = root.childElements("parent").firstOrNull()
        key =
            parent?.let {
                val pv = it.childText("version")?.takeIf { v -> '$' !in v } ?: module.version
                "${it.childText("groupId")}:${it.childText("artifactId")}:$pv"
            }
    }
    return null
}

fun licenseTextFile(id: String): File = noticesLicenseTextFiles[id] ?: File(noticesLicenseDir, "$id.txt")

fun normalisedText(file: File): String = file.readText(Charsets.UTF_8).replace("\r\n", "\n").trimEnd() + "\n"

val licenseEntryName = Regex("(?i)^(licen[sc]e|notice|copying|copyright)([-_.].*)?$")

// LICENSE / NOTICE style files a dependency jar carries: at the root, under META-INF/,
// under META-INF/licenses/ or under META-INF/maven/<group>/<artifact>/.
fun licenseEntries(jar: File): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    ZipFile(jar).use { zip ->
        for (e in zip.entries().asSequence().sortedBy { it.name }) {
            if (e.isDirectory || e.size > 400_000) continue
            val path = e.name
            val parts = path.split('/')
            val base = parts.last()
            val inScope =
                when {
                    parts.size == 1 -> true
                    parts[0] == "META-INF" && parts.size == 2 -> true
                    parts[0] == "META-INF" && parts[1] == "licenses" -> true
                    parts[0] == "META-INF" && parts[1] == "maven" && parts.size == 5 -> true
                    else -> false
                }
            if (!inScope || !licenseEntryName.matches(base)) continue
            if (base.endsWith(".class") || base.endsWith(".jar")) continue
            val text = zip.getInputStream(e).readBytes().toString(Charsets.UTF_8).replace("\r\n", "\n").trim()
            if (text.isNotEmpty()) out.add(path to text)
        }
    }
    return out
}

fun collapsed(text: String): String = text.replace(Regex("\\s+"), " ").trim()

fun sha256(text: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(collapsed(text).toByteArray())
        .joinToString("") { "%02x".format(it) }

val rule = "=".repeat(78)

val generateThirdPartyNotices =
    tasks.register("generateThirdPartyNotices") {
        group = "documentation"
        description = "Generates THIRD-PARTY-NOTICES.txt for every module shaded into the CLI jar"
        inputs.property("modules", providers.provider { bundledModules().map { it.gav } })
        inputs.dir(noticesLicenseDir)
        inputs.file(File(noticesRepoRoot, "LICENSE"))
        inputs.property("overrides", licenseOverrides.toString() + noticesRemarks.toString())
        outputs.file(noticesOutputFile)

        doLast {
            val modules = bundledModules()
            val poms = resolvePoms(modules)
            val licensesOf = modules.associateWith { moduleLicenses(it, poms) }
            val missing = modules.filter { licensesOf.getValue(it).isEmpty() }
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "No licence found for ${missing.joinToString { it.gav }}. " +
                        "Classify them in licenseOverrides in core/gradle/notices.gradle.kts",
                )
            }
            val licenseIds = licensesOf.values.flatten().map { it.id }.toSortedSet()
            licenseIds.forEach { id ->
                if (!licenseTextFile(id).isFile) {
                    throw GradleException("Licence text for $id missing: ${licenseTextFile(id)}")
                }
            }

            val sb = StringBuilder()
            sb.appendLine("THIRD-PARTY NOTICES FOR UNIDRIVE")
            sb.appendLine()
            sb.appendLine("The UniDrive command-line jar (unidrive-<version>.jar) bundles the open-source")
            sb.appendLine("components listed below. UniDrive itself is licensed under the Apache License,")
            sb.appendLine("Version 2.0 (see LICENSE-unidrive.txt and NOTICE-unidrive.txt). The components")
            sb.appendLine("are distributed under their own licences, reproduced in full in part 3.")
            sb.appendLine("This file is generated at build time from the jar's runtime classpath.")
            sb.appendLine()
            sb.appendLine(rule)
            sb.appendLine("PART 1: COMPONENTS (${modules.size})")
            sb.appendLine(rule)
            for (m in modules) {
                val licenses = licensesOf.getValue(m)
                sb.appendLine()
                sb.appendLine("* ${m.gav}")
                pomProjectUrl(m, poms)?.let { sb.appendLine("    Project:    $it") }
                sb.appendLine("    License-Id: ${licenses.joinToString(", ") { it.id }}")
                licenses.forEach { sb.appendLine("    License:    ${it.name}${if (it.url.isNotBlank()) " <${it.url}>" else ""}") }
                noticesRemarks.entries
                    .firstOrNull { m.ga.startsWith(it.key) }
                    ?.let { sb.appendLine("    Note:       ${it.value}") }
            }

            // Part 2: licence / NOTICE files the dependency jars themselves carry.
            val canonical = licenseIds.associateWith { sha256(normalisedText(licenseTextFile(it))) }.values.toSet()
            val byContent = LinkedHashMap<String, Pair<String, MutableList<String>>>()
            for (m in modules) {
                for ((path, text) in licenseEntries(m.file)) {
                    val hash = sha256(text)
                    if (hash in canonical) continue
                    byContent.getOrPut(hash) { text to ArrayList() }.second.add("${m.gav} (${path})")
                }
            }
            sb.appendLine()
            sb.appendLine(rule)
            sb.appendLine("PART 2: LICENSE AND NOTICE FILES SHIPPED INSIDE THE COMPONENT JARS")
            sb.appendLine(rule)
            for ((text, sources) in byContent.values) {
                sb.appendLine()
                sb.appendLine("--- From:")
                sources.forEach { sb.appendLine("    $it") }
                sb.appendLine("---")
                sb.appendLine(text)
            }

            sb.appendLine()
            sb.appendLine(rule)
            sb.appendLine("PART 3: LICENSE TEXTS")
            sb.appendLine(rule)
            for (id in licenseIds) {
                val users = modules.filter { m -> licensesOf.getValue(m).any { it.id == id } }
                sb.appendLine()
                sb.appendLine("--- LICENSE TEXT: $id (applies to ${users.size} component${if (users.size == 1) "" else "s"})")
                sb.appendLine(normalisedText(licenseTextFile(id)))
            }

            val out = noticesOutputFile.get().asFile
            out.parentFile.mkdirs()
            out.writeText(sb.toString().replace("\r\n", "\n"), Charsets.UTF_8)
            println(
                "Third-party notices: ${modules.size} components, licences " +
                    licensesOf.values
                        .flatten()
                        .groupingBy { it.id }
                        .eachCount()
                        .toSortedMap()
                        .entries
                        .joinToString { "${it.key}=${it.value}" },
            )
        }
    }

pluginManager.withPlugin("com.gradleup.shadow") {
    val shadow = tasks.named<Jar>("shadowJar")
    shadow.configure {
        from(generateThirdPartyNotices) { into("META-INF") }
        from(File(noticesRepoRoot, "LICENSE")) {
            into("META-INF")
            rename { "LICENSE-unidrive.txt" }
        }
        from(File(noticesRepoRoot, "NOTICE")) {
            into("META-INF")
            rename { "NOTICE-unidrive.txt" }
        }
    }

    // Declares only the single output file (not the libs directory, which other modules'
    // classpaths read from) so Gradle sees no overlap with the jar tasks.
    val copyThirdPartyNotices =
        tasks.register("copyThirdPartyNotices") {
            group = "documentation"
            description = "Places THIRD-PARTY-NOTICES.txt next to the shadow jar so a packager can ship it"
            val source = noticesOutputFile
            val target = shadow.flatMap { it.destinationDirectory.file("THIRD-PARTY-NOTICES.txt") }
            dependsOn(generateThirdPartyNotices)
            inputs.file(source)
            outputs.file(target)
            doLast { source.get().asFile.copyTo(target.get().asFile, overwrite = true) }
        }
    shadow.configure { finalizedBy(copyThirdPartyNotices) }

    val verifyThirdPartyNotices =
        tasks.register("verifyThirdPartyNotices") {
            group = "verification"
            description = "Fails when a module shaded into the CLI jar is missing from the notices embedded in it"
            val jar = shadow.flatMap { it.archiveFile }
            dependsOn(shadow)
            inputs.file(jar)
            inputs.property("modules", providers.provider { bundledModules().map { it.gav } })
            inputs.dir(noticesLicenseDir)
            inputs.file(File(noticesRepoRoot, "LICENSE"))
            mustRunAfter(copyThirdPartyNotices)
            doLast {
                val modules = bundledModules()
                val problems = ArrayList<String>()
                ZipFile(jar.get().asFile).use { zip ->
                    fun entryText(name: String): String? =
                        zip.getEntry(name)?.let { zip.getInputStream(it).readBytes().toString(Charsets.UTF_8) }

                    for (name in listOf("META-INF/LICENSE-unidrive.txt", "META-INF/NOTICE-unidrive.txt")) {
                        if (entryText(name).isNullOrBlank()) problems.add("jar lacks $name")
                    }
                    val notices = entryText("META-INF/THIRD-PARTY-NOTICES.txt")
                    if (notices.isNullOrBlank()) {
                        problems.add("jar lacks META-INF/THIRD-PARTY-NOTICES.txt")
                    } else {
                        val listed = notices.lines().map { it.trim() }.toSet()
                        for (m in modules) {
                            if ("* ${m.gav}" !in listed) problems.add("bundled module not in notices: ${m.gav}")
                        }
                        val ids =
                            notices.lines()
                                .filter { it.trim().startsWith("License-Id:") }
                                .flatMap { it.substringAfter(':').split(',').map { s -> s.trim() } }
                                .toSet()
                        if (ids.isEmpty()) problems.add("notices name no licences")
                        for (id in ids) {
                            val header = notices.indexOf("--- LICENSE TEXT: $id ")
                            if (header < 0) problems.add("licence text missing for $id")
                        }
                        // Every module needs a licence line, and every referenced text must be substantial.
                        for (id in ids) {
                            val expected = collapsed(normalisedText(licenseTextFile(id)))
                            if (!collapsed(notices).contains(expected)) problems.add("licence text for $id is incomplete")
                        }
                    }
                }
                if (problems.isNotEmpty()) {
                    throw GradleException(
                        "Third-party notices are out of date with the bundled dependencies:\n" +
                            problems.joinToString("\n") { "  - $it" } +
                            "\nRun ./gradlew :app:cli:shadowJar to regenerate; classify new licences in " +
                            "core/gradle/notices.gradle.kts.",
                    )
                }
                println("Third-party notices cover all ${modules.size} bundled modules.")
            }
        }
    tasks.named("check") { dependsOn(verifyThirdPartyNotices) }
}
