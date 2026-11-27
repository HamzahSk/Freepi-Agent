package rofil.movie.app

import android.app.Application
import rofil.movie.app.data.MovieRepository
import rofil.movie.app.data.local.AppDatabase
import rofil.movie.app.data.remote.Lk21Client
import rofil.movie.app.data.remote.Lk21Parser
import rofil.movie.app.data.scrape.CatalogDataSource
import rofil.movie.app.data.scrape.HttpCatalogDataSource
import rofil.movie.app.data.scrape.ScrapeScriptLoader
import rofil.movie.app.data.scrape.StreamProvider
import rofil.movie.app.data.scrape.WebViewCatalogDataSource
import rofil.movie.app.data.scrape.WebViewScrapeEngine
import rofil.movie.app.data.scrape.WebViewStreamResolver
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

    val sourceScripts: ScrapeScriptLoader by lazy { ScrapeScriptLoader(application) }

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
            catalog = httpCatalog,
            fallbackCatalog = webCatalog,
            streamProvider = streamProvider,
            client = client,
            database = database,
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
