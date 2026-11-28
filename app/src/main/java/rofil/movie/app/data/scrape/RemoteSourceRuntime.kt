package rofil.movie.app.data.scrape

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.NonCancellable
import rofil.movie.app.data.remote.ScrapeTimeoutException
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Call
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Runs trusted repository modules in an isolated document; website HTML is parsed as inert DOM. */
class RemoteSourceRuntime(
    private val context: Context,
    val store: SourceModuleStore,
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build(),
) {
    private val mutex = Mutex()
    private var webView: WebView? = null
    private var installed = ""
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    @Volatile private var pending: CompletableDeferred<JSONObject>? = null
    @Volatile private var requestId = ""

    /** Drop the in-memory WebView bundle after a user-requested cache reset. */
    fun reset() {
        calls.forEach { it.cancel() }
        webView?.destroy()
        webView = null
        installed = ""
        pending?.completeExceptionally(IllegalStateException("Remote runtime reset"))
        pending = null
        requestId = ""
    }

    private inner class Bridge {
        @JavascriptInterface fun request(raw: String): String = try {
            check(requestId.isNotEmpty()) { "Source request cancelled" }
            val value = JSONObject(raw)
            val url = value.getString("url").toHttpUrl()
            require(url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty())
            val builder = Request.Builder().url(url)
            value.optJSONObject("headers")?.let { h -> for (key in h.keys()) builder.header(key, h.getString(key)) }
            val method = value.optString("method", "GET").uppercase()
            require(method in setOf("GET", "POST", "HEAD"))
            val contentType = (builder.build().header("Content-Type") ?: "application/json").toMediaType()
            val body = if (method == "GET" || method == "HEAD") null else value.optString("body", "").toRequestBody(contentType)
            builder.method(method, body)
            val call = http.newCall(builder.build())
            calls.add(call)
            try {
                check(requestId.isNotEmpty()) { "Source request cancelled" }
                call.execute().use { r ->
                    val responseSource = r.body?.source()
                    responseSource?.request(8L * 1024 * 1024 + 1)
                    val bytes = responseSource?.buffer?.readByteArray() ?: byteArrayOf()
                    require(bytes.size <= 8 * 1024 * 1024) { "Source response is too large" }
                    val responseBody = String(bytes, r.body?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
                    JSONObject().put("status", r.code).put("url", r.request.url.toString()).put("body", responseBody).toString()
                }
            } finally { calls.remove(call) }
        } catch (e: Exception) { JSONObject().put("error", e.message ?: "Source request failed").toString() }
        @JavascriptInterface fun result(id: String, raw: String) {
            if (id == requestId) runCatching { JSONObject(raw) }.fold({ pending?.complete(it) }, { pending?.completeExceptionally(it) })
        }
    }

    suspend fun execute(sourceId: String, operation: String, args: JSONObject = JSONObject()): JSONObject = mutex.withLock {
        val manifest = withContext(Dispatchers.IO) { store.refresh() }
        val source = store.source(manifest, sourceId)
        val operations = source.getJSONArray("operations")
        require((0 until operations.length()).any { operations.getString(it) == operation }) { "Unsupported source operation: $operation" }
        val export = JSONObject.quote(source.getJSONObject("runtime").getString("export"))
        run(sourceId, operation, manifest, store.sourceRoots(source), "MovieSources[$export][${JSONObject.quote(operation)}]($args)")
    }

    /** Runs the repository's extractor implementation; no host parser is required. */
    suspend fun extract(extractorId: String, options: JSONObject): JSONObject = mutex.withLock {
        val manifest = withContext(Dispatchers.IO) { store.refresh() }
        val extractors = manifest.getJSONArray("extractors")
        val entries = (0 until extractors.length()).map { extractors.getJSONObject(it) }
        val roots = if (extractorId.isBlank()) entries.map { it.getString("module") }
            else listOf(entries.firstOrNull { it.getString("id") == extractorId }?.getString("module")
                ?: throw IllegalArgumentException("Extractor is unavailable: $extractorId"))
        val target = extractorId.ifBlank { options.optString("url") }
        run("extractor:$extractorId", "stream", manifest, roots,
            "FreepiExtractors.resolve(${JSONObject.quote(target)},$options).then(function(v){return FreepiRuntime.envelope(FreepiRuntime.SCHEMAS.STREAM,v);})")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun run(sourceId: String, operation: String, manifest: JSONObject, roots: List<String>, expression: String): JSONObject {
        val code = withContext(Dispatchers.IO) { store.scripts(manifest, roots) }
        val identity = sourceId + java.security.MessageDigest.getInstance("SHA-256")
            .digest(code.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return withTimeoutOrNull(60_000) {
            val result = CompletableDeferred<JSONObject>()
            val id = java.util.UUID.randomUUID().toString()
            pending = result; requestId = id
            try {
                withContext(Dispatchers.Main) {
                    var view = webView
                    if (view == null) {
                        view = WebView(context.applicationContext)
                        view.settings.javaScriptEnabled = true
                        view.settings.domStorageEnabled = true
                        view.settings.allowFileAccess = false
                        view.settings.allowContentAccess = false
                        view.settings.blockNetworkLoads = true
                        view.webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                // Avoid logging remote text, URLs, headers or tokens.
                                Log.d("FreepiScrape", "runtime console source=$sourceId level=${message.messageLevel()} line=${message.lineNumber()}")
                                return true
                            }
                        }
                        view.addJavascriptInterface(Bridge(), "MovieSourceHttp")
                        webView = view
                    }
                    val initialize = if (installed != identity) "(0,eval)(" + JSONObject.quote(code) + ");" else ""
                    val invocation = """
                        Promise.resolve().then(function(){ $initialize return $expression;})
                        .then(function(v){MovieSourceHttp.result(${JSONObject.quote(id)},JSON.stringify(v));})
                        .catch(function(e){MovieSourceHttp.result(${JSONObject.quote(id)},JSON.stringify({schema:'freepi.error.v1',ok:false,error:{code:e.code||'JS_ERROR',message:String(e.message||e)}}));});
                    """.trimIndent()
                    if (installed != identity) {
                        val activeView = view
                        view.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(v: WebView, url: String) {
                                if (requestId == id) v.evaluateJavascript(invocation, null)
                            }
                            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest) = true
                        }
                        activeView.loadDataWithBaseURL("https://movie-source-runtime.invalid/", "<html><body></body></html>", "text/html", "UTF-8", null)
                    } else view.evaluateJavascript(invocation, null)
                }
                result.await().also {
                    if (it.optJSONObject("error")?.optString("code") != "JS_ERROR") installed = identity
                    Log.d("FreepiScrape", "runtime source=$sourceId operation=$operation ok=${it.optBoolean("ok")}")
                }
            } finally {
                pending = null; requestId = ""
                calls.forEach { it.cancel() }
                // A timed out/cancelled or failed initialization must never contaminate the next call.
                if (!result.isCompleted || result.isCancelled || installed != identity) {
                    withContext(NonCancellable + Dispatchers.Main) { reset() }
                }
            }
        } ?: throw ScrapeTimeoutException("Source operation timed out: $sourceId.$operation")
    }
}
