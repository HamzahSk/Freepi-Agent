package rofil.movie.app.data.scrape

import android.content.Context
import rofil.movie.app.data.remote.NetworkUnavailableException
import rofil.movie.app.data.remote.ParseException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Fetches the public script registry and keeps the last valid manifest and scripts on disk. */
class ScrapeScriptLoader(context: Context) {
    private val cacheDir = File(context.applicationContext.filesDir, "movie-source").apply { mkdirs() }
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    @Synchronized
    fun script(request: ScrapeRequest, requestId: String): String {
        val kind = request.kind.name.lowercase()
        val manifest = loadManifest()
        val source = manifest.getJSONArray("sources").let { sources ->
            (0 until sources.length()).mapNotNull { sources.optJSONObject(it) }
                .firstOrNull { it.optString("id") == "lk21" }
        } ?: throw ParseException("The source registry has no LK21 source. Retry after the registry is updated.")
        val path = source.optJSONObject("scripts")?.optString(kind)
            ?.takeIf { it.isNotBlank() }
            ?: throw ParseException("The source registry has no LK21 '$kind' script. Retry after the registry is updated.")
        val commonPath = source.optJSONObject("scripts")?.optString("common")
            ?.takeIf { it.isNotBlank() }
            ?: throw ParseException("The source registry has no LK21 common script. Retry after the registry is updated.")
        val common = loadScript(commonPath)
        val page = loadScript(path)
        return buildString {
            append("window.__freepiRequestId=").append(JSONObject.quote(requestId)).append(";\n")
            append("window.__freepiRequestUrl=").append(JSONObject.quote(request.url)).append(";\n")
            append("window.__freepiProviderUrl=").append(JSONObject.quote(request.streamProviderUrl ?: request.url)).append(";\n")
            append("window.__freepiMaxDepth=").append(request.streamMaxDepth.coerceIn(0, 32)).append(";\n")
            append("window.__freepiTimeoutMs=").append((request.timeoutMs - 1_000).coerceAtLeast(1)).append(";\n")
            append("window.__freepiDeadline=").append(System.currentTimeMillis() + request.timeoutMs - 1_000).append(";\n")
            append(common).append('\n').append(page)
        }
    }

    @Synchronized
    fun extractorScript(id: String): String {
        require(id.matches(Regex("[a-z0-9-]+"))) { "Invalid extractor id" }
        val entries = loadManifest().optJSONArray("extractors")
            ?: throw ParseException("The source registry has no extractors. Retry after the registry is updated.")
        val extractor = (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }
            .firstOrNull { it.optString("id") == id }
            ?: throw ParseException("The source registry has no '$id' extractor. Retry after the registry is updated.")
        return loadScript(extractor.optString("entry"))
    }

    private fun loadManifest(): JSONObject {
        val cacheFile = File(cacheDir, "manifest.json")
        val raw = try {
            fetch("manifest.json")
        } catch (e: IOException) {
            cacheFile.takeIf(File::isFile)?.readText()
                ?: throw NetworkUnavailableException("Cannot reach movie-source and no cached script registry is available. Check your connection and retry.", e)
        }
        return try {
            JSONObject(raw).also { manifest ->
                if (manifest.optInt("schemaVersion") != 1 || manifest.optJSONArray("sources") == null) {
                    throw IllegalArgumentException("unsupported registry schema")
                }
                if (manifest.optJSONArray("extractors") == null) throw IllegalArgumentException("missing extractors")
                writeAtomically(cacheFile, raw)
            }
        } catch (e: Exception) {
            throw ParseException("The movie-source registry is invalid. Retry after the repository is corrected.", e)
        }
    }

    private fun loadScript(path: String): String {
        validatePath(path)
        val file = File(cacheDir, path)
        return try {
            fetch(path).also { writeAtomically(file, it) }
        } catch (e: IOException) {
            file.takeIf(File::isFile)?.readText()
                ?: throw NetworkUnavailableException("Cannot download '$path' and no cached copy is available. Check your connection and retry.", e)
        }
    }

    private fun fetch(path: String): String {
        val request = Request.Builder().url("$BASE_URL$path").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("movie-source returned HTTP ${response.code} for $path")
            return response.body?.string()?.takeIf(String::isNotBlank)
                ?: throw IOException("movie-source returned an empty file for $path")
        }
    }

    private fun writeAtomically(file: File, value: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(value)
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    private fun validatePath(path: String) {
        if (path.startsWith('/') || path.contains('\\') || path.split('/').any { it == ".." || it.isBlank() }) {
            throw ParseException("The movie-source registry contains an invalid script path. Retry after the registry is corrected.")
        }
    }

    companion object {
        private const val BASE_URL = "https://raw.githubusercontent.com/HamzahSk/movie-source/main/"
    }
}
