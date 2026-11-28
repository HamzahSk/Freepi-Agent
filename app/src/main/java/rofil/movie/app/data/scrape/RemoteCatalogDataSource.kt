package rofil.movie.app.data.scrape

import org.json.JSONObject
import rofil.movie.app.data.model.*
import rofil.movie.app.data.remote.StreamResolutionException

/** Generic normalized-envelope adapter. Routes and source operations belong to remote modules. */
class RemoteCatalogDataSource(
    private val runtime: RemoteSourceRuntime,
    private val selectedSource: () -> String,
    private val mapper: ScrapeMapper = ScrapeMapper(),
) : CatalogDataSource, StreamProvider {
    private suspend fun call(operation: String, kind: ScrapeKind, args: JSONObject): ScrapeEnvelope {
        val raw = runtime.execute(selectedSource(), operation, args)
        return ScrapeValidator.parseEnvelope(raw, kind)
    }
    override suspend fun home(forceRefresh: Boolean): HomeData {
        if (forceRefresh) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runtime.store.refresh(true) }
        return mapper.home(ScrapeValidator.requireHome(call("home", ScrapeKind.HOME, JSONObject())))
    }
    override suspend fun list(pathOrUrl: String, forceRefresh: Boolean): ListPage {
        val envelope = call("list", ScrapeKind.LIST, JSONObject().put("url", pathOrUrl))
        return mapper.list(ScrapeValidator.requireList(envelope, false), envelope.source.finalUrl)
    }
    override suspend fun search(query: String, forceRefresh: Boolean): ListPage {
        val envelope = call("search", ScrapeKind.LIST, JSONObject().put("query", query))
        return mapper.list(ScrapeValidator.requireList(envelope, true), envelope.source.finalUrl)
    }
    override suspend fun detail(slugOrUrl: String, forceRefresh: Boolean): MovieDetail {
        val envelope = call("detail", ScrapeKind.DETAIL, JSONObject().put("url", slugOrUrl))
        return mapper.detail(ScrapeValidator.requireDetail(envelope), envelope.data.optString("url").ifBlank { slugOrUrl })
    }
    override suspend fun resolve(detail: MovieDetail, source: StreamSource): ResolvedStream {
        val envelope = call("stream", ScrapeKind.STREAM, JSONObject().put("url", detail.slug).put("source", JSONObject()
            .put("embedUrl", source.embedUrl).put("decodedUrl", source.decodedUrl).put("provider", source.provider)).put("pageUrl", detail.url))
        val data = ScrapeValidator.requireStream(envelope)
        val item = data.optJSONArray("media")?.optJSONObject(0) ?: throw StreamResolutionException("Source returned no playable stream")
        val url = item.getString("url")
        require(url.startsWith("https://") || url.startsWith("http://"))
        val headers = item.optJSONObject("headers")
        val subtitleArray = data.optJSONArray("subtitles")
        val subtitles = (0 until (subtitleArray?.length() ?: 0)).mapNotNull { index ->
            val track = subtitleArray!!.optJSONObject(index) ?: return@mapNotNull null
            track.optString("url").takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let {
                SubtitleTrack(label = track.optString("label", "Subtitle"), language = track.optString("language"), url = it)
            }
        }
        return ResolvedStream(source.provider, url, item.optString("mimeType").takeIf(String::isNotBlank),
            headers?.keys()?.asSequence()?.associateWith { headers.getString(it) }.orEmpty(), subtitles, source.label)
    }
}
