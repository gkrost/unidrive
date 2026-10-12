package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.client.engine.mock.respond
import java.net.URLDecoder

/**
 * A fake Graph drive with OneDrive's name rule: names are stored exactly, but uniqueness among siblings is
 * judged on a key, the name with the [ignored] code points removed, compared case-insensitively. Two
 * siblings with the same key but different names are "similar"; the second create is refused with the two
 * 409 shapes measured against the live service:
 *  - a folder create (`POST .../children`, no conflict behaviour) answers `nameAlreadyExists` /
 *    "Name already exists", for a true duplicate and a similar name alike;
 *  - a file PUT or upload-session create answers `nameAlreadyExists` / "The specified item name is
 *    incompatible with a similar name on an existing item." for a similar name, whatever the conflict
 *    behaviour (the `rename` keep-both is refused as well), and "Name already exists" for a true duplicate
 *    under `fail` (under `rename` a true duplicate lands as "name 1.ext", under `replace` it is replaced).
 *
 * [pathLookupByKey] makes `GET /root:/<path>` resolve by the same key, so a lookup of one name can return
 * its similar sibling: the worst case for code that trusts a path lookup.
 */
class SimilarNameGraph(
    private val ignored: Set<Int> = DEFAULT_IGNORED,
    private val pathLookupByKey: Boolean = true,
) {
    class Node(
        val id: String,
        var name: String,
        val isFolder: Boolean,
        var parent: Node?,
        var bytes: ByteArray = ByteArray(0),
    ) {
        val children = linkedMapOf<String, Node>()
    }

    val root = Node("root", "", true, null)
    private val byId = linkedMapOf<String, Node>("root" to root)
    private var counter = 0

    /** Every request the fake saw, as `METHOD path?query` (decoded). */
    val requests = mutableListOf<String>()

    fun requestsOf(method: String, contains: String = ""): List<String> =
        requests.filter { it.startsWith("$method ") && it.contains(contains) }

    fun key(name: String): String =
        buildString { name.codePoints().filter { it !in ignored }.forEach { appendCodePoint(it) } }.lowercase()

    private fun newId(): String = "id-${++counter}"

    private fun pathOf(node: Node): String {
        val parts = mutableListOf<String>()
        var n: Node? = node
        while (n != null && n !== root) {
            parts += n.name
            n = n.parent
        }
        return "/" + parts.reversed().joinToString("/")
    }

    /** Seeds a folder or file, the way a prior session or another device left it. */
    fun seed(path: String, isFolder: Boolean = false, bytes: ByteArray = ByteArray(0)): Node {
        val parent = resolveParent(path) ?: error("no parent for $path")
        val name = path.substringAfterLast('/')
        val node = Node(newId(), name, isFolder, parent, bytes)
        parent.children[name] = node
        byId[node.id] = node
        return node
    }

    fun find(path: String): Node? {
        var cur: Node = root
        for (seg in path.split('/').filter { it.isNotEmpty() }) cur = cur.children[seg] ?: return null
        return cur
    }

    fun exists(path: String): Boolean = find(path) != null

    private fun resolveParent(path: String): Node? {
        var cur: Node = root
        val segs = path.split('/').filter { it.isNotEmpty() }
        for (seg in segs.dropLast(1)) cur = lookupChild(cur, seg) ?: return null
        return cur
    }

    private fun lookupChild(parent: Node, name: String): Node? =
        parent.children[name]
            ?: if (pathLookupByKey) parent.children.values.firstOrNull { key(it.name) == key(name) } else null

    private fun exact(parent: Node, name: String): Node? = parent.children.values.firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun similar(parent: Node, name: String): Node? =
        parent.children.values.firstOrNull { it.name != name && key(it.name) == key(name) }

    private fun itemJson(node: Node): String {
        val parentPath = node.parent?.let { if (it === root) "/drive/root:" else "/drive/root:" + pathOf(it) } ?: ""
        val facet = if (node.isFolder) """"folder":{"childCount":${node.children.size}}""" else """"file":{"mimeType":"application/octet-stream"}"""
        return """{"id":"${node.id}","name":${quote(node.name)},"size":${node.bytes.size},$facet,"parentReference":{"path":"$parentPath"}}"""
    }

    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun MockRequestHandleScope.error(status: HttpStatusCode, code: String, message: String): HttpResponseData =
        json("""{"error":{"code":"$code","message":"$message"}}""", status)

    private fun MockRequestHandleScope.itemNotFound() = error(HttpStatusCode.NotFound, "itemNotFound", "The resource could not be found.")

    private fun MockRequestHandleScope.nameExists() = error(HttpStatusCode.Conflict, "nameAlreadyExists", "Name already exists")

    private fun MockRequestHandleScope.similarName() =
        error(HttpStatusCode.Conflict, "nameAlreadyExists", "The specified item name is incompatible with a similar name on an existing item.")

    private val drivePrefix = "/v1.0/me/drive/"

    /** The path part of a `root:/a/b:` segment, decoded, or null when the URL is not path-addressed. */
    private fun rootPath(encodedPath: String): String? {
        val rest = encodedPath.removePrefix("/v1.0/me/drive/root:")
        if (rest == encodedPath) return null
        return URLDecoder.decode(rest.substringBefore(":/").removeSuffix(":"), "UTF-8")
    }

    private fun suffixAfterPath(encodedPath: String): String {
        val rest = encodedPath.removePrefix("/v1.0/me/drive/root:")
        return if (rest.contains(":/")) rest.substringAfter(":/") else ""
    }

    private var sessionCounter = 0
    private val sessions = mutableMapOf<String, Pair<String, String>>() // url -> (path, name)

    fun engine(): MockEngine = MockEngine { request -> handle(request) }

    /** A [HttpClient] over this fake, for injection into a [GraphApiService]. */
    fun client(): HttpClient = HttpClient(engine())

    private fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        val encoded = request.url.encodedPath
        val decodedPath = URLDecoder.decode(encoded, "UTF-8")
        requests += "${request.method.value} $decodedPath" +
            (request.url.parameters["@microsoft.graph.conflictBehavior"]?.let { "?conflictBehavior=$it" } ?: "")
        if (request.url.host == "upload.example") return handleSession(request)

        val method = request.method
        val path = rootPath(encoded)
        val suffix = suffixAfterPath(encoded)
        return when {
            // content PUT: path-addressed
            method == HttpMethod.Put && suffix == "content" && path != null -> putContent(request, path)
            // upload session create
            method == HttpMethod.Post && suffix == "createUploadSession" && path != null -> createSession(request, path)
            // folder create under a path or the root
            method == HttpMethod.Post && suffix == "children" && path != null -> createFolder(request, path)
            method == HttpMethod.Post && encoded == "/v1.0/me/drive/root/children" -> createFolder(request, "")
            // the delta feed: every item, with its path (the first page is the whole drive)
            method == HttpMethod.Get && encoded == "/v1.0/me/drive/root/delta" -> delta()
            // children listing
            method == HttpMethod.Get && suffix == "children" && path != null -> listChildren(path)
            method == HttpMethod.Get && encoded == "/v1.0/me/drive/root/children" -> listChildren("")
            // item by path
            method == HttpMethod.Get && path != null && suffix.isEmpty() -> getByPath(path)
            // by id
            encoded.startsWith(drivePrefix + "items/") -> byIdRequest(request, encoded.removePrefix(drivePrefix + "items/"))
            else -> error(HttpStatusCode.BadRequest, "invalidRequest", "unhandled ${request.method} $decodedPath")
        }
    }

    private fun MockRequestHandleScope.delta(): HttpResponseData {
        val all = mutableListOf<Node>()
        fun walk(n: Node) {
            for (c in n.children.values) {
                all += c
                walk(c)
            }
        }
        walk(root)
        return json("""{"value":[${all.joinToString(",") { itemJson(it) }}],"@odata.deltaLink":"https://graph.microsoft.com/v1.0/me/drive/root/delta?token=t1"}""")
    }

    private fun MockRequestHandleScope.getByPath(path: String): HttpResponseData {
        if (path.isEmpty()) return json(itemJson(root))
        val parent = resolveParent("/$path") ?: return itemNotFound()
        val node = lookupChild(parent, path.substringAfterLast('/')) ?: return itemNotFound()
        return json(itemJson(node))
    }

    private fun MockRequestHandleScope.listChildren(path: String): HttpResponseData {
        val node = if (path.isEmpty()) root else (resolveParent("/$path")?.let { lookupChild(it, path.substringAfterLast('/')) } ?: return itemNotFound())
        return json("""{"value":[${node.children.values.joinToString(",") { itemJson(it) }}]}""")
    }

    private fun MockRequestHandleScope.createFolder(request: HttpRequestData, parentPath: String): HttpResponseData {
        val parent = if (parentPath.isEmpty()) root else (resolveParent("/$parentPath")?.let { lookupChild(it, parentPath.substringAfterLast('/')) } ?: return itemNotFound())
        val body = (request.body as TextContent).text
        val name = Regex("\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)!!.groupValues[1].let { unescape(it) }
        if (exact(parent, name) != null || similar(parent, name) != null) return nameExists()
        val node = Node(newId(), name, true, parent)
        parent.children[name] = node
        byId[node.id] = node
        return json(itemJson(node), HttpStatusCode.Created)
    }

    private fun unescape(s: String): String = s.replace("\\\"", "\"").replace("\\\\", "\\")

    /** Outcome of the name rule for a file create under [behavior], or the node to use. */
    private sealed interface Place {
        data class Create(val name: String) : Place
        data class Replace(val node: Node) : Place
        data class Refuse(val similar: Boolean) : Place
    }

    private fun place(parent: Node, name: String, behavior: String): Place {
        val dup = exact(parent, name)
        if (dup != null) {
            return when (behavior) {
                "replace" -> Place.Replace(dup)
                "rename" -> {
                    val dot = name.lastIndexOf('.')
                    val base = if (dot > 0) name.substring(0, dot) else name
                    val ext = if (dot > 0) name.substring(dot) else ""
                    Place.Create("$base 1$ext")
                }
                else -> Place.Refuse(similar = false)
            }
        }
        if (similar(parent, name) != null) return Place.Refuse(similar = true)
        return Place.Create(name)
    }

    private fun MockRequestHandleScope.putContent(request: HttpRequestData, path: String): HttpResponseData {
        val parent = resolveParent("/$path") ?: return itemNotFound()
        val name = path.substringAfterLast('/')
        val behavior = request.url.parameters["@microsoft.graph.conflictBehavior"] ?: "replace"
        val bytes = (request.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes() ?: ByteArray(0)
        return when (val p = place(parent, name, behavior)) {
            is Place.Refuse -> if (p.similar) similarName() else nameExists()
            is Place.Replace -> {
                p.node.bytes = bytes
                json(itemJson(p.node))
            }
            is Place.Create -> {
                val node = Node(newId(), p.name, false, parent, bytes)
                parent.children[p.name] = node
                byId[node.id] = node
                json(itemJson(node), HttpStatusCode.Created)
            }
        }
    }

    private fun MockRequestHandleScope.createSession(request: HttpRequestData, path: String): HttpResponseData {
        val parent = resolveParent("/$path") ?: return itemNotFound()
        val name = path.substringAfterLast('/')
        val body = (request.body as TextContent).text
        val behavior = Regex("conflictBehavior\"\\s*:\\s*\"(\\w+)\"").find(body)?.groupValues?.get(1) ?: "replace"
        return when (val p = place(parent, name, behavior)) {
            is Place.Refuse -> if (p.similar) similarName() else nameExists()
            else -> {
                val url = "https://upload.example/session-${++sessionCounter}"
                sessions[url] = "/$path" to behavior
                json("""{"uploadUrl":"$url","expirationDateTime":"2099-01-01T00:00:00Z"}""")
            }
        }
    }

    private fun MockRequestHandleScope.handleSession(request: HttpRequestData): HttpResponseData {
        val url = request.url.toString().substringBefore('?')
        val (path, behavior) = sessions[url] ?: return itemNotFound()
        val parent = resolveParent(path) ?: return itemNotFound()
        val name = path.substringAfterLast('/')
        val total = request.headers[HttpHeaders.ContentRange]?.substringAfter('/')?.toIntOrNull() ?: 0
        return when (val p = place(parent, name, behavior)) {
            is Place.Refuse -> if (p.similar) similarName() else nameExists()
            is Place.Replace -> json(itemJson(p.node).also { p.node.bytes = ByteArray(total) }, HttpStatusCode.Created)
            is Place.Create -> {
                val node = Node(newId(), p.name, false, parent, ByteArray(total))
                parent.children[p.name] = node
                byId[node.id] = node
                json(itemJson(node), HttpStatusCode.Created)
            }
        }
    }

    private fun MockRequestHandleScope.byIdRequest(request: HttpRequestData, id: String): HttpResponseData {
        val node = byId[id] ?: return itemNotFound()
        return when (request.method) {
            HttpMethod.Delete -> {
                node.parent?.children?.remove(node.name)
                removeTree(node)
                respond("", HttpStatusCode.NoContent)
            }
            HttpMethod.Get -> json(itemJson(node))
            HttpMethod.Patch -> {
                val body = (request.body as TextContent).text
                val newName = Regex("^\\{\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)?.groupValues?.get(1)?.let { unescape(it) }
                if (newName != null && newName != node.name) {
                    val parent = node.parent!!
                    if (similar(parent, newName) != null || exact(parent, newName) != null) return similarName()
                    parent.children.remove(node.name)
                    node.name = newName
                    parent.children[newName] = node
                }
                json(itemJson(node))
            }
            else -> error(HttpStatusCode.BadRequest, "invalidRequest", "unhandled ${request.method}")
        }
    }

    private fun removeTree(node: Node) {
        byId.remove(node.id)
        node.children.values.forEach { removeTree(it) }
    }

    companion object {
        /**
         * Measured against the live service: N'Ko (U+07D2), Tifinagh (U+2D5C), Vai (U+A559), Ethiopic
         * (U+12A0, U+12F0 ...) and Adlam (U+1E900) compare as nothing; Cyrillic and Latin do not.
         */
        val DEFAULT_IGNORED: Set<Int> = buildSet {
            addAll(0x07C0..0x07FF) // N'Ko
            addAll(0x2D30..0x2D7F) // Tifinagh
            addAll(0xA500..0xA63F) // Vai
            addAll(0x1200..0x137F) // Ethiopic
            addAll(0x1E900..0x1E95F) // Adlam
            addAll(0x0700..0x074F) // Syriac
        }
    }
}
