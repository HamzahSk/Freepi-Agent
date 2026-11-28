package rofil.movie.app.data.scrape

import rofil.movie.app.data.SourceSelection
import rofil.movie.app.data.model.MovieDetail
import rofil.movie.app.data.model.ResolvedStream
import rofil.movie.app.data.model.StreamSource

/** Prefer repository JavaScript; LK21 explicitly delegates its browser playback to the host. */
class ExtensionStreamProvider(
    private val runtime: RemoteSourceRuntime,
    private val legacyLk21: StreamProvider,
) : StreamProvider {
    override suspend fun resolve(detail: MovieDetail, source: StreamSource): ResolvedStream {
        val extension = RemoteCatalogDataSource(runtime, { source.sourceId })
        if (source.sourceId != SourceSelection.LK21) return extension.resolve(detail, source)
        val shared = object : StreamProvider {
            override suspend fun resolve(detail: MovieDetail, source: StreamSource) = extension.resolveShared(detail, source)
        }
        return CompositeStreamProvider(shared, legacyLk21).resolve(detail, source)
    }
}
