package com.freepi.lk21.data.scrape

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.freepi.lk21.data.remote.AccessBlockedException
import com.freepi.lk21.data.remote.NetworkUnavailableException
import com.freepi.lk21.data.remote.ParseException
import com.freepi.lk21.data.remote.ScrapeTimeoutException
import com.freepi.lk21.data.remote.SourceException
import com.freepi.lk21.data.remote.WebViewUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Centralised hidden-WebView scraping engine.
 *
 * Architecture:
 *   website -> hidden WebView (Chromium) -> injected DOM JavaScript ->
 *   JSON bridge -> Kotlin validation/mapping -> repositories -> native Compose UI.
 *
 * The WebView is never attached to a visible view hierarchy and is destroyed
 * after every task. Tasks are serialised with a [Mutex], so concurrent screens
 * (home + browse + detail) can never observe each other's page state.
 */
class WebViewScrapeEngine(
    context: Context,
    private val scripts: ScrapeScriptLoader = ScrapeScriptLoader(context),
) {

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private const val TAG = "FreepiScrape"
        private const val RETRY_DELAY_MS = 700L
        private const val INJECTION_SETTLE_MS = 350L
    }

    private val appContext = context.applicationContext
    private val mutex = Mutex()

    @Volatile
    private var closed = false

    /**
     * Scrape one page, validate the JSON envelope and return it. Retries only
     * transient failures (timeouts/navigation), never an anti-bot challenge.
     */
    suspend fun scrape(request: ScrapeRequest): ScrapeEnvelope {
        if (closed) {
            throw WebViewUnavailableException("The scraping engine has already been closed.")
        }
        var attempt = 0
        var last: Throwable? = null
        val maxAttempts = request.retries.coerceIn(0, 2) + 1

        while (attempt < maxAttempts) {
            attempt++
            try {
                val payload = mutex.withLock { execute(request, attempt) }
                return ScrapeValidator.parseEnvelope(payload, request.kind)
            } catch (e: AccessBlockedException) {
                // The site asked for human verification. Never attempt to bypass
                // it - surface a retryable error to the UI instead.
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: TimeoutCancellationException) {
                last = e
            } catch (e: WebViewUnavailableException) {
                throw e
            } catch (e: Throwable) {
                last = e
                ScrapeLogger.failure(
                    requestId = "retry",
                    kind = request.kind,
                    stage = "attempt_$attempt",
                    error = e,
                    url = request.url,
                )
            }

            if (attempt < maxAttempts) {
                ScrapeLogger.warn(
                    requestId = "retry",
                    kind = request.kind,
                    stage = "retrying",
                    detail = "attempt ${attempt + 1} of $maxAttempts after " +
                        ((last as? SourceException)?.message ?: last?.message.orEmpty()),
                    url = request.url,
                )
                delay(RETRY_DELAY_MS * attempt)
            }
        }

        throw when (last) {
            is SourceException -> last as SourceException
            null -> SourceException("The scraping engine failed without an error.")
            else -> SourceException(
                last?.message ?: "Could not read the source page.",
                last,
            )
        }
    }

    fun close() {
        closed = true
    }

    // ------------------------------------------------------------------ task

    private suspend fun execute(request: ScrapeRequest, attempt: Int): JSONObject =
        withContext(Dispatchers.Main.immediate) {
            ensureWebViewAvailable()

            val requestId = UUID.randomUUID().toString()
            val started = SystemClock.elapsedRealtime()
            val deferred = CompletableDeferred<JSONObject>()
            val session = Session(requestId, request, deferred)

            ScrapeLogger.stage(
                requestId = requestId,
                kind = request.kind,
                stage = "start",
                url = request.url,
                detail = "attempt=$attempt timeoutMs=${request.timeoutMs}",
            )

            try {
                val webView = try {
                    WebView(appContext)
                } catch (t: Throwable) {
                    throw WebViewUnavailableException(
                        "Android System WebView is missing or broken on this device.",
                        t,
                    )
                }
                session.webView = webView
                configure(webView, request)
                webView.addJavascriptInterface(session.bridge, ScrapeSchemas.BRIDGE_NAME)
                webView.webViewClient = session.client
                webView.webChromeClient = session.chromeClient

                val headers = buildMap {
                    put("User-Agent", USER_AGENT)
                    put(
                        "Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9," +
                            "image/avif,image/webp,*/*;q=0.8",
                    )
                    put("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                    put("Upgrade-Insecure-Requests", "1")
                    request.referer?.let { put("Referer", it) }
                    putAll(request.extraHeaders)
                }

                webView.loadUrl(request.url, headers)
                withTimeout(request.timeoutMs) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                ScrapeLogger.failure(
                    requestId = requestId,
                    kind = request.kind,
                    stage = "timeout",
                    error = e,
                    url = request.url,
                    elapsedMs = SystemClock.elapsedRealtime() - started,
                )
                throw ScrapeTimeoutException(
                    "The source page did not finish loading in time. Try again.",
                    e,
                )
            } finally {
                session.handler.removeCallbacksAndMessages(null)
                destroy(session)
                ScrapeLogger.stage(
                    requestId = requestId,
                    kind = request.kind,
                    stage = "cleanup",
                    url = request.url,
                    elapsedMs = SystemClock.elapsedRealtime() - started,
                )
            }
        }

    private fun ensureWebViewAvailable() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val packageName = runCatching { WebView.getCurrentWebViewPackage()?.packageName }
                .getOrNull()
            if (packageName.isNullOrBlank()) {
                throw WebViewUnavailableException(
                    "Android System WebView is not installed or disabled on this device."
                )
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(webView: WebView, request: ScrapeRequest) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            loadsImagesAutomatically = false
            blockNetworkImage = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = USER_AGENT
            setGeolocationEnabled(false)
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
        }
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.setBackgroundColor(Color.TRANSPARENT)
        runCatching {
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }
        }
    }

    private fun inject(webView: WebView, session: Session) {
        if (session.deferred.isCompleted) return
        if (!session.injected.compareAndSet(false, true)) return
        val script = try {
            scripts.script(session.request, session.requestId)
        } catch (t: Throwable) {
            session.fail(t)
            return
        }
        try {
            webView.evaluateJavascript(script) { result ->
                ScrapeLogger.stage(
                    requestId = session.requestId,
                    kind = session.request.kind,
                    stage = "js_injected",
                    url = session.currentUrl,
                    detail = "callback=${result?.take(80)}",
                )
            }
        } catch (t: Throwable) {
            session.fail(ParseException("Could not execute the extraction script.", t))
        }
    }

    private fun destroy(session: Session) {
        val webView = session.webView
        session.webView = null
        if (webView == null) return
        runCatching { webView.stopLoading() }
        runCatching { webView.removeJavascriptInterface(ScrapeSchemas.BRIDGE_NAME) }
        runCatching { webView.webChromeClient = null }
        runCatching { webView.webViewClient = WebViewClient() }
        runCatching { webView.loadUrl("about:blank") }
        runCatching { webView.clearHistory() }
        runCatching { webView.removeAllViews() }
        runCatching { webView.destroy() }
        Log.d(TAG, "WebView destroyed id=${session.requestId.take(8)}")
    }

    // --------------------------------------------------------------- session

    private inner class Session(
        val requestId: String,
        val request: ScrapeRequest,
        val deferred: CompletableDeferred<JSONObject>,
    ) {
        val handler = Handler(Looper.getMainLooper())
        val delivered = AtomicBoolean(false)
        val injected = AtomicBoolean(false)
        val bridge = ScrapeBridge(this)
        var webView: WebView? = null
        var currentUrl: String? = null
        var pageGeneration = 0

        fun fail(error: Throwable) {
            if (deferred.isActive) deferred.completeExceptionally(error)
        }

        val client = object : WebViewClient() {

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                currentUrl = url
                ScrapeLogger.stage(
                    requestId = requestId,
                    kind = request.kind,
                    stage = "page_started",
                    url = url,
                )
            }

            override fun onPageFinished(view: WebView, url: String?) {
                pageGeneration++
                val generation = pageGeneration
                currentUrl = url
                ScrapeLogger.stage(
                    requestId = requestId,
                    kind = request.kind,
                    stage = "page_finished",
                    url = url,
                )
                handler.postDelayed({
                    if (deferred.isActive &&
                        generation == pageGeneration &&
                        view.url == url
                    ) {
                        inject(view, this@Session)
                    }
                }, INJECTION_SETTLE_MS)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val scheme = runCatching {
                    Uri.parse(request.url.toString()).scheme?.lowercase()
                }.getOrNull()
                // Allow the page's own http(s) redirects; never let the scraper
                // navigate into intent://, market:// or app deep links.
                return scheme != "http" && scheme != "https"
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (!request.isForMainFrame || deferred.isCompleted) return
                val description = error.description?.toString().orEmpty()
                ScrapeLogger.failure(
                    requestId = requestId,
                    kind = this@Session.request.kind,
                    stage = "navigation_error",
                    error = NetworkUnavailableException(description),
                    url = request.url.toString(),
                )
                fail(
                    NetworkUnavailableException(
                        "Could not load the source page" +
                            if (description.isBlank()) "." else " ($description).",
                    )
                )
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                if (!request.isForMainFrame || deferred.isCompleted) return
                val code = errorResponse.statusCode
                ScrapeLogger.failure(
                    requestId = requestId,
                    kind = this@Session.request.kind,
                    stage = "http_error_$code",
                    error = SourceException("HTTP $code"),
                    url = request.url.toString(),
                )
                when {
                    code == 403 || code == 429 -> fail(AccessBlockedException())
                    code >= 500 -> fail(
                        NetworkUnavailableException(
                            "The data source returned a server error (HTTP $code). Try again."
                        )
                    )
                    code >= 400 -> fail(
                        NetworkUnavailableException(
                            "The source page could not be found (HTTP $code)."
                        )
                    )
                }
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: android.net.http.SslError,
            ) {
                handler.cancel()
                fail(
                    SourceException(
                        "The source page has an invalid security certificate."
                    )
                )
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail,
            ): Boolean {
                fail(
                    WebViewUnavailableException(
                        "The Android WebView renderer crashed while scraping. Try again."
                    )
                )
                return true
            }
        }

        val chromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                ScrapeLogger.stage(
                    requestId = requestId,
                    kind = request.kind,
                    stage = "js_console",
                    detail = "${message.messageLevel()} ${message.message()}",
                )
                return true
            }
        }
    }

    /**
     * Bridge exposed to the page. Only the active request id is accepted and
     * only the first payload wins, so a delayed callback from a previous page
     * can never overwrite a newer result.
     */
    private class ScrapeBridge(private val session: Session) {

        @JavascriptInterface
        fun postMessage(id: String?, payload: String?) {
            if (id != session.requestId) return
            if (!session.delivered.compareAndSet(false, true)) return

            ScrapeLogger.stage(
                requestId = session.requestId,
                kind = session.request.kind,
                stage = "payload_received",
                url = session.currentUrl,
                detail = "bytes=${payload?.length ?: 0}",
            )
            try {
                if (payload.isNullOrBlank()) {
                    throw ParseException("The extraction script returned an empty payload.")
                }
                val json = JSONObject(payload)
                session.deferred.complete(json)
            } catch (t: Throwable) {
                session.fail(
                    ParseException("The extraction script returned malformed JSON.", t)
                )
            }
        }

        @JavascriptInterface
        fun logStage(stage: String?, message: String?) {
            ScrapeLogger.stage(
                requestId = session.requestId,
                kind = session.request.kind,
                stage = "js_" + (stage ?: "log"),
                url = session.currentUrl,
                detail = message,
            )
        }
    }
}
