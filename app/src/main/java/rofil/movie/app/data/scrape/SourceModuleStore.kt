package rofil.movie.app.data.scrape

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Immutable, hash-addressed module snapshots. A failed update never replaces a working registry. */
class SourceModuleStore(
    private val directory: File,
    private val http: OkHttpClient,
    private val baseUrl: String = "https://raw.githubusercontent.com/HamzahSk/movie-source/main/",
) {
    @Volatile private var current: JSONObject? = null
    private var checkedAt = 0L

    /** Remove the active manifest and hash-addressed modules. The next runtime
     * request will rebuild a verified snapshot from movie-source. */
    @Synchronized fun clear() {
        directory.deleteRecursively()
        directory.mkdirs()
        current = null
        checkedAt = 0L
    }
    @Synchronized fun refresh(force: Boolean = false): JSONObject {
        if (!force && System.currentTimeMillis() - checkedAt < 300_000) current?.let { return it }
        val old = current ?: runCatching { JSONObject(File(directory, "active.json").readText()).also(::validate) }.getOrNull()
        return try {
            val candidate = JSONObject(download("manifest.json"))
            validate(candidate)
            val modules = candidate.getJSONObject("modules")
            val done = hashSetOf<String>()
            fun prepare(id: String, visiting: Set<String>) {
                if (id in done) return
                require(id !in visiting) { "Cyclic module dependency: $id" }
                val module = modules.getJSONObject(id)
                val dependencies = module.optJSONArray("dependencies")
                for (i in 0 until (dependencies?.length() ?: 0)) prepare(dependencies!!.getString(i), visiting + id)
                val hash = module.getString("sha256")
                val file = moduleFile(hash)
                if (!file.isFile || digest(file.readText()) != hash) {
                    val code = download(module.getString("path"))
                    require(digest(code) == hash) { "Module hash mismatch: $id" }
                    write(file, code)
                }
                done += id
            }
            val sources = candidate.getJSONArray("sources")
            for (i in 0 until sources.length()) prepare(sources.getJSONObject(i).getJSONObject("runtime").getString("module"), emptySet())
            write(File(directory, "active.json"), candidate.toString())
            checkedAt = System.currentTimeMillis()
            current = candidate
            candidate
        } catch (e: Exception) {
            if (old == null) throw IOException("Cannot load a compatible movie-source registry", e)
            // Confirm all previously committed module hashes before accepting an offline snapshot.
            val modules = old.getJSONObject("modules")
            for (id in modules.keys()) {
                val hash = modules.getJSONObject(id).getString("sha256")
                require(moduleFile(hash).isFile && digest(moduleFile(hash).readText()) == hash) { "Cached source module is damaged" }
            }
            current = old
            old
        }
    }
    @Synchronized fun scripts(manifest: JSONObject, moduleId: String): String {
        val modules = manifest.getJSONObject("modules")
        val visited = hashSetOf<String>()
        fun visit(id: String): String {
            if (!visited.add(id)) return ""
            val m = modules.getJSONObject(id)
            val dependencies = m.optJSONArray("dependencies")
            val prefix = (0 until (dependencies?.length() ?: 0)).joinToString("\n") { visit(dependencies!!.getString(it)) }
            val hash = m.getString("sha256")
            val text = moduleFile(hash).readText()
            require(digest(text) == hash) { "Cached source module is damaged" }
            return prefix + "\n" + text
        }
        return visit(moduleId)
    }
    private fun validate(m: JSONObject) {
        require(m.getInt("schemaVersion") == 1 && m.getInt("runtimeVersion") == 1) { "Incompatible source runtime" }
        val ids = hashSetOf<String>()
        val modules = m.getJSONObject("modules")
        for (id in modules.keys()) {
            val module = modules.getJSONObject(id)
            require(module.getString("sha256").matches(Regex("[a-f0-9]{64}")))
            path(module.getString("path"))
            require(module.getString("version").isNotBlank())
        }
        val sources = m.getJSONArray("sources")
        for (i in 0 until sources.length()) {
            val s = sources.getJSONObject(i)
            require(s.getString("id").matches(Regex("[a-z0-9-]+")) && ids.add(s.getString("id")))
            val runtime = s.getJSONObject("runtime")
            require(runtime.getInt("version") == 1 && modules.has(runtime.getString("module")))
            require(runtime.getString("export").matches(Regex("[a-z0-9-]+")))
        }
    }
    private fun path(value: String) {
        require(!value.startsWith('/') && !value.contains('\\') && value.split('/').none { it == ".." || it.isBlank() })
    }
    private fun download(value: String): String {
        path(value)
        http.newCall(Request.Builder().url(baseUrl + value).build()).execute().use { r ->
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
