package rofil.movie.app

import android.app.Application
import rofil.movie.app.data.MovieRepository
import rofil.movie.app.data.SourceSelection
import rofil.movie.app.data.local.AppDatabase
import rofil.movie.app.data.remote.Lk21Client
import rofil.movie.app.data.remote.Lk21Parser
import rofil.movie.app.data.remote.MovieBoxClient
import rofil.movie.app.data.scrape.CatalogDataSource
import rofil.movie.app.data.scrape.HttpCatalogDataSource
import rofil.movie.app.data.scrape.MovieBoxCatalogDataSource
import rofil.movie.app.data.scrape.MovieBoxStreamProvider
import rofil.movie.app.data.scrape.ScrapeScriptLoader
import rofil.movie.app.data.scrape.StreamProvider
import rofil.movie.app.data.scrape.WebViewCatalogDataSource
import rofil.movie.app.data.scrape.WebViewScrapeEngine
import rofil.movie.app.data.scrape.WebViewStreamResolver
import rofil.movie.app.data.scrape.SourceModuleStore
import rofil.movie.app.data.scrape.RemoteSourceRuntime
import rofil.movie.app.data.scrape.RemoteCatalogDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Manual dependency container (small app, no DI framework required).
 *
 * Catalog HTML uses the HTTP parser first, with browser execution for unsupported
 * dynamic layouts. Streams share one HTTP/browser extraction pipeline. Native
 * Compose, Media3 and Room remain the UI, playback and persistence layers.
 */
class AppContainer(application: Application) {

    private val cacheDir = File(application.cacheDir, "lk21").apply { mkdirs() }

    val client: Lk21Client by lazy { Lk21Client(cacheDir,
        browserCookies = { android.webkit.CookieManager.getInstance().getCookie(it) },
        receiveBrowserCookie = { url, cookie -> android.webkit.CookieManager.getInstance().setCookie(url, cookie) },
    ) }
    val parser: Lk21Parser by lazy { Lk21Parser() }
    val sourceSelection: SourceSelection by lazy { SourceSelection(application) }
    val movieBoxClient: MovieBoxClient by lazy { MovieBoxClient(okhttp3.OkHttpClient.Builder().build()) }
    val movieBoxCatalog: CatalogDataSource by lazy { MovieBoxCatalogDataSource(movieBoxClient) }
    val movieBoxStreamProvider: StreamProvider by lazy {
        MovieBoxStreamProvider(movieBoxClient, okhttp3.OkHttpClient.Builder()
            .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build())
    }

    val sourceScripts: ScrapeScriptLoader by lazy { ScrapeScriptLoader(application, moduleStore) }
    val moduleStore: SourceModuleStore by lazy { SourceModuleStore(File(application.filesDir, "movie-source"), okhttp3.OkHttpClient()) }
    val remoteRuntime: RemoteSourceRuntime by lazy { RemoteSourceRuntime(application, moduleStore) }
    val remoteCatalog: CatalogDataSource by lazy { RemoteCatalogDataSource(remoteRuntime, { sourceSelection.selectedId }) }
    val remoteStreamProvider: StreamProvider by lazy { RemoteCatalogDataSource(remoteRuntime, { sourceSelection.selectedId }) }

    /** Centralised hidden-WebView scraping engine (all screens share one). */
    val scrapeEngine: WebViewScrapeEngine by lazy { WebViewScrapeEngine(application, sourceScripts) }

    val webCatalog: CatalogDataSource by lazy {
        WebViewCatalogDataSource(scrapeEngine, client)
    }
    val httpCatalog: CatalogDataSource by lazy {
        HttpCatalogDataSource(client, parser)
    }

    val streamProvider: StreamProvider by lazy {
        WebViewStreamResolver(scrapeEngine, client)
    }

    val database: AppDatabase by lazy { AppDatabase.get(application) }
    val repository: MovieRepository by lazy {
        MovieRepository(
            catalog = remoteCatalog,
            fallbackCatalog = httpCatalog,
            streamProvider = rofil.movie.app.data.scrape.ExtensionStreamProvider(remoteRuntime, streamProvider),
            client = client,
            database = database,
            movieBoxCatalog = null,
            movieBoxStreamProvider = null,
            selectedSource = { sourceSelection.selectedId },
            storeSelectedSource = sourceSelection::select,
            sourceCatalog = { id -> RemoteCatalogDataSource(remoteRuntime, { id }) },
        )
    }

    /** Outlives individual screens so final watch-progress writes always land. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

class MovieApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
