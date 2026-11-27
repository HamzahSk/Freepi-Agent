package rofil.movie.app.data.scrape

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import rofil.movie.app.data.model.MovieDetail
import rofil.movie.app.data.model.ResolvedStream
import rofil.movie.app.data.model.StreamSource
import rofil.movie.app.data.model.SubtitleTrack
import rofil.movie.app.data.remote.MovieBoxClient
import rofil.movie.app.data.remote.PlayerMetadata
import rofil.movie.app.data.remote.PlayerResponse
import rofil.movie.app.data.remote.SourceException
import rofil.movie.app.data.remote.StreamResolutionException
import java.net.URI
import java.util.concurrent.TimeUnit

class MovieBoxStreamProvider(
    private val api: MovieBoxClient,
    private val http: OkHttpClient,
) : StreamProvider {
    override suspend fun resolve(detail: MovieDetail, source: StreamSource): ResolvedStream {
        val subjectId = source.externalId?.takeIf(String::isNotBlank)
            ?: throw StreamResolutionException("PLAYER_METADATA: MovieBox playback is missing its subject ID.")
        val candidates = api.streamCandidates(subjectId, source.season, source.episode, source.detailPath.orEmpty())
            .sortedByDescending { candidate ->
                val resolutions = candidate.json.optString("resolutions")
                listOf(2160, 1440, 1080, 720, 480, 360, 240).firstOrNull { resolutions.contains(it.toString()) } ?: 0
            }
        if (candidates.isEmpty()) throw StreamResolutionException("PLAYER_METADATA: MovieBox returned no playable sources.")
        var last: Exception? = null
        for (candidate in candidates) {
            val url = candidate.json.optString("url")
            val result = validate(url, candidate.headers)
            if (result != null) {
                val captions = runCatching {
                    api.captions(subjectId, candidate.json.opt("id")?.toString(), source.season, source.episode)
                }.getOrNull()
                val subtitles = (0 until (captions?.length() ?: 0)).mapNotNull { index ->
                    val item = captions?.optJSONObject(index) ?: return@mapNotNull null
                    val subtitleUrl = item.optString("url").takeIf { it.startsWith("https://") || it.startsWith("http://") }
                        ?: return@mapNotNull null
                    SubtitleTrack(
                        label = item.optString("lanName").ifBlank { item.optString("language") }.ifBlank { "Subtitle" },
                        url = subtitleUrl,
                        language = item.optString("lan").takeIf(String::isNotBlank),
                    )
                }.distinctBy { it.url }
                return result.copy(provider = candidate.source, label = detail.sources.firstOrNull()?.label ?: "MovieBox", subtitles = subtitles)
            }
            last = StreamResolutionException("STREAM_VALIDATION: MovieBox candidate was not a playable media response.")
        }
        throw last ?: SourceException("STREAM_VALIDATION: MovieBox returned no valid media response.")
    }

    private suspend fun validate(url: String, headers: Map<String, String>): ResolvedStream? = withContext(Dispatchers.IO) {
        ScrapeLogger.stage("moviebox", ScrapeKind.STREAM, "PLAYER_METADATA", url, detail = "candidate received")
        try {
            val request = Request.Builder().url(url)
                .header("Range", "bytes=0-524287")
                .apply { headers.forEach { (key, value) -> header(key, value) } }
                .build()
            val response = http.newCall(request).execute()
            response.use {
                if (!it.isSuccessful) {
                    ScrapeLogger.stage("moviebox", ScrapeKind.STREAM, "STREAM_VALIDATION", url,
                        detail = "rejected HTTP ${it.code}")
                    return@withContext null
                }
                val body = it.peekBody(524_289).bytes()
                val contentType = it.header("Content-Type").orEmpty()
                val parsed = PlayerResponse(
                    url = it.request.url.toString(),
                    body = String(body, if (contentType.startsWith("video/") || contentType.startsWith("application/octet-stream")) Charsets.ISO_8859_1 else Charsets.UTF_8),
                    contentType = contentType,
                    status = it.code,
                    truncated = body.size >= 524_289,
                )
                val mime = PlayerMetadata.validatedMime(parsed)
                if (mime == null) {
                    ScrapeLogger.stage("moviebox", ScrapeKind.STREAM, "STREAM_VALIDATION", url,
                        detail = "rejected non-media response")
                    return@withContext null
                }
                if ((mime == "application/dash+xml" && parsed.body.contains("ContentProtection", true)) ||
                    (mime == "application/x-mpegURL" && Regex("METHOD=SAMPLE-AES", RegexOption.IGNORE_CASE).containsMatchIn(parsed.body))) {
                    throw StreamResolutionException("STREAM_VALIDATION: protected media requires an authorized DRM playback integration.")
                }
                val origin = URI(parsed.url).let { "${it.scheme}://${it.authority}" }
                ScrapeLogger.stage("moviebox", ScrapeKind.STREAM, "STREAM_VALIDATION", parsed.url, detail = "success mime=$mime")
                ResolvedStream("MovieBox", parsed.url, mime, headers + mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 Chrome/154.0.0.0 Mobile Safari/537.36",
                    "Referer" to origin,
                ), emptyList(), "MovieBox")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: StreamResolutionException) {
            throw e
        } catch (e: Exception) {
            ScrapeLogger.stage("moviebox", ScrapeKind.STREAM, "STREAM_VALIDATION", url, detail = "request failed ${e.javaClass.simpleName}")
            null
        }
    }
}
