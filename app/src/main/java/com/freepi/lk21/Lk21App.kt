package com.freepi.lk21

import android.app.Application
import com.freepi.lk21.data.MovieRepository
import com.freepi.lk21.data.local.AppDatabase
import com.freepi.lk21.data.remote.Lk21Client
import com.freepi.lk21.data.remote.Lk21Parser
import com.freepi.lk21.data.scrape.CatalogDataSource
import com.freepi.lk21.data.scrape.HttpCatalogDataSource
import com.freepi.lk21.data.scrape.StreamProvider
import com.freepi.lk21.data.scrape.WebViewCatalogDataSource
import com.freepi.lk21.data.scrape.WebViewScrapeEngine
import com.freepi.lk21.data.scrape.WebViewStreamResolver
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

    /** Centralised hidden-WebView scraping engine (all screens share one). */
    val scrapeEngine: WebViewScrapeEngine by lazy { WebViewScrapeEngine(application) }

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

class Lk21App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}