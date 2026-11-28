package rofil.movie.app.data.scrape

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Hash-addressed, transactional registry snapshots. Unverified updates never replace active.json. */
class SourceModuleStore(
    private val directory: File,
    private val http: OkHttpClient,
    private val baseUrl: String = "https://raw.githubusercontent.com/HamzahSk/movie-source/main/",
) {
    @Volatile private var current: JSONObject? = null
    @Volatile var lastRefreshError: Exception? = null
        private set
    private var checkedAt = 0L

    @Synchronized fun clear() {
        directory.deleteRecursively()
        current = null
        checkedAt = 0L
    }

    @Synchronized fun refresh(force: Boolean = false): JSONObject {
        if (!force && System.currentTimeMillis() - checkedAt < 300_000) current?.let { return JSONObject(it.toString()) }
        val old = current ?: runCatching { JSONObject(File(directory, "active.json").readText()).also(::validate) }.getOrNull()
        val result = try {
            val candidate = JSONObject(download("manifest.json"))
            validate(candidate)
            val modules = candidate.getJSONObject("modules")
            // Cache every advertised module, including extractors not currently referenced by a source.
            for (id in moduleOrder(candidate, modules.keys().asSequence().toList())) {
                val module = modules.getJSONObject(id)
                val hash = module.getString("sha256")
                val file = moduleFile(hash)
                if (!file.isFile || digest(file.readText()) != hash) {
                    val code = download(module.getString("path"), candidate.getString("baseUrl"))
                    require(digest(code) == hash) { "Module hash mismatch: $id" }
                    write(file, code)
                }
            }
            write(File(directory, "active.json"), candidate.toString())
            lastRefreshError = null
            candidate
        } catch (e: Exception) {
            lastRefreshError = e
            if (old == null) throw IOException("Cannot load a compatible movie-source registry", e)
            val modules = old.getJSONObject("modules")
            for (id in modules.keys()) {
                val hash = modules.getJSONObject(id).getString("sha256")
                require(moduleFile(hash).isFile && digest(moduleFile(hash).readText()) == hash) { "Cached source module is damaged" }
            }
            old
        }
        checkedAt = System.currentTimeMillis()
        current = result
        return JSONObject(result.toString())
    }

    @Synchronized fun scripts(manifest: JSONObject, moduleId: String): String = scripts(manifest, listOf(moduleId))

    @Synchronized fun scripts(manifest: JSONObject, roots: List<String>): String =
        moduleOrder(manifest, roots).joinToString("\n") { id ->
            val hash = manifest.getJSONObject("modules").getJSONObject(id).getString("sha256")
            moduleFile(hash).readText().also { require(digest(it) == hash) { "Cached source module is damaged: $id" } }
        }

    fun source(manifest: JSONObject, id: String): JSONObject {
        val sources = manifest.getJSONArray("sources")
        return (0 until sources.length()).map { sources.getJSONObject(it) }.firstOrNull { it.getString("id") == id }
            ?: throw IllegalArgumentException("Source is unavailable: $id")
    }

    fun sourceRoots(source: JSONObject): List<String> = strings(source, "dependencies") + source.getJSONObject("runtime").getString("module")

    internal fun moduleOrder(manifest: JSONObject, roots: List<String>): List<String> {
        val modules = manifest.getJSONObject("modules")
        val done = hashSetOf<String>()
        val visiting = hashSetOf<String>()
        val ordered = arrayListOf<String>()
        fun visit(id: String) {
            if (id in done) return
            require(visiting.add(id)) { "Cyclic module dependency: $id" }
            require(modules.has(id)) { "Unknown module dependency: $id" }
            strings(modules.getJSONObject(id), "dependencies").forEach(::visit)
            visiting.remove(id)
            done.add(id)
            ordered.add(id)
        }
        roots.forEach(::visit)
        return ordered
    }

    private fun validate(m: JSONObject) {
        require(m.getInt("schemaVersion") == 1 && m.getInt("runtimeVersion") == 1) { "Incompatible source runtime" }
        require(m.getString("version").isNotBlank())
        val root = m.getString("baseUrl").toHttpUrl()
        require(root.username.isEmpty() && root.password.isEmpty() && root.query == null && root.fragment == null && root.encodedPath.endsWith('/'))
        val ids = hashSetOf<String>()
        val modules = m.getJSONObject("modules")
        for (id in modules.keys()) {
            val module = modules.getJSONObject(id)
            require(module.getString("sha256").matches(Regex("[a-f0-9]{64}")))
            path(module.getString("path"))
            require(module.getString("version").isNotBlank())
        }
        moduleOrder(m, modules.keys().asSequence().toList())
        val sources = m.getJSONArray("sources")
        for (i in 0 until sources.length()) {
            val s = sources.getJSONObject(i)
            require(s.getString("id").matches(Regex("[a-z0-9-]+")) && ids.add(s.getString("id")))
            require(s.getString("name").isNotBlank() && s.getString("version").isNotBlank())
            val runtime = s.getJSONObject("runtime")
            require(runtime.getInt("version") == 1 && modules.has(runtime.getString("module")))
            require(runtime.getString("export").matches(Regex("[a-z0-9-]+")))
            require(s.getString("entry") == modules.getJSONObject(runtime.getString("module")).getString("path"))
            require(strings(s, "operations").all { it in setOf("home", "search", "list", "detail", "episodes", "stream") })
            s.getJSONArray("operations")
            moduleOrder(m, sourceRoots(s))
        }
        val extractors = m.optJSONArray("extractors")
        for (i in 0 until (extractors?.length() ?: 0)) {
            val e = extractors!!.getJSONObject(i)
            require(e.getString("entry") == modules.getJSONObject(e.getString("module")).getString("path"))
            moduleOrder(m, strings(e, "dependencies") + e.getString("module"))
        }
    }

    private fun strings(value: JSONObject, key: String): List<String> {
        val array = value.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).map(array::getString)
    }
    private fun path(value: String) {
        require(!value.contains(':') && !value.contains('?') && !value.contains('#') && !value.startsWith('/') && !value.contains('\\') && value.split('/').none { it == ".." || it == "." || it.isBlank() })
    }
    private fun download(value: String, origin: String = baseUrl): String {
        path(value)
        http.newCall(Request.Builder().url(origin + value).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Source download HTTP ${r.code}")
            return r.body?.string()?.takeIf(String::isNotBlank) ?: throw IOException("Empty source module")
        }
    }
    private fun moduleFile(hash: String) = File(directory, "modules/$hash.js")
    private fun write(file: File, text: String) {
        file.parentFile!!.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(text)
        check(temporary.renameTo(file)) { "Cannot commit source snapshot" }
    }
    private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
