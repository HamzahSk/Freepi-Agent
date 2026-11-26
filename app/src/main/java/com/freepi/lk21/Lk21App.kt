package com.freepi.lk21

import android.app.Application
import com.freepi.lk21.data.MovieRepository
import com.freepi.lk21.data.local.AppDatabase
import com.freepi.lk21.data.remote.Lk21Client
import com.freepi.lk21.data.remote.Lk21Parser
import com.freepi.lk21.data.remote.StreamResolver
import com.freepi.lk21.data.scrape.CatalogDataSource
import com.freepi.lk21.data.scrape.CompositeStreamProvider
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
 * Data flow: live site -> hidden WebView -> injected JavaScript -> validated
 * JSON -> repositories -> native Compose UI. The HTTP client still serves the
 * stream fallback, HLS subtitle manifests and the WebView-less catalog fallback.
 */
class AppContainer(application: Application) {

    private val cacheDir = File(application.cacheDir, "lk21").apply { mkdirs() }

    val client: Lk21Client by lazy { Lk21Client(cacheDir) }
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
        CompositeStreamProvider(
            primary = WebViewStreamResolver(scrapeEngine, client),
            fallback = StreamResolver(client),
        )
    }

    val database: AppDatabase by lazy { AppDatabase.get(application) }
    val repository: MovieRepository by lazy {
        MovieRepository(
            catalog = webCatalog,
            fallbackCatalog = httpCatalog,
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