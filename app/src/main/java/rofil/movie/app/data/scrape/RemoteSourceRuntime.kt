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
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Runs trusted repository modules in an isolated document; website HTML is parsed as inert DOM. */
class RemoteSourceRuntime(private val context: Context, val store: SourceModuleStore) {
    private val mutex = Mutex()
    private val http = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()
    private var webView: WebView? = null
    private var installed = ""
    private var pending: CompletableDeferred<JSONObject>? = null
    private var requestId = ""

    private inner class Bridge {
        @JavascriptInterface fun request(raw: String): String = try {
            val value = JSONObject(raw)
            val url = value.getString("url").toHttpUrl()
            require(url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty())
            val builder = Request.Builder().url(url)
            value.optJSONObject("headers")?.let { h -> for (key in h.keys()) builder.header(key, h.getString(key)) }
            val method = value.optString("method", "GET")
            builder.method(method, if (method == "GET" || method == "HEAD") null else value.optString("body", "").toRequestBody("application/json".toMediaType()))
            http.newCall(builder.build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                require(body.length <= 8 * 1024 * 1024) { "Source response is too large" }
                JSONObject().put("status", r.code).put("url", r.request.url.toString()).put("body", body).toString()
            }
        } catch (e: Exception) { JSONObject().put("error", e.message ?: "Source request failed").toString() }
        @JavascriptInterface fun result(id: String, raw: String) {
            if (id == requestId) runCatching { JSONObject(raw) }.fold({ pending?.complete(it) }, { pending?.completeExceptionally(it) })
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun execute(sourceId: String, operation: String, args: JSONObject = JSONObject()): JSONObject = mutex.withLock {
        val manifest = withContext(Dispatchers.IO) { store.refresh() }
        val sources = manifest.getJSONArray("sources")
        val source = (0 until sources.length()).map { sources.getJSONObject(it) }.firstOrNull { it.getString("id") == sourceId }
            ?: throw IllegalArgumentException("Source is unavailable: $sourceId")
        val operations = source.getJSONArray("operations")
        require((0 until operations.length()).any { operations.getString(it) == operation }) { "Unsupported source operation: $operation" }
        val runtime = source.getJSONObject("runtime")
        val module = runtime.getString("module")
        val code = withContext(Dispatchers.IO) { store.scripts(manifest, module) }
        val identity = manifest.getJSONObject("modules").toString()
        withTimeout(60_000) {
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
                        view.addJavascriptInterface(Bridge(), "MovieSourceHttp")
                        webView = view
                    }
                    val invocation = """
                        Promise.resolve().then(function(){return MovieSources[${JSONObject.quote(runtime.getString("export"))}][${JSONObject.quote(operation)}](${args});})
                        .then(function(v){MovieSourceHttp.result(${JSONObject.quote(id)},JSON.stringify(v));})
                        .catch(function(e){MovieSourceHttp.result(${JSONObject.quote(id)},JSON.stringify({schema:'freepi.error.v1',ok:false,error:{code:e.code||'JS_ERROR',message:String(e.message||e)}}));});
                    """.trimIndent()
                    if (installed != identity) {
                        val activeView = view
                        view.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(v: WebView, url: String) {
                                v.evaluateJavascript(code + "\n" + invocation, null)
                            }
                            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest) = true
                        }
                        installed = identity
                        activeView.loadDataWithBaseURL("https://movie-source-runtime.invalid/", "<html><body></body></html>", "text/html", "UTF-8", null)
                    } else view.evaluateJavascript(invocation, null)
                }
                result.await()
            } finally { pending = null; requestId = "" }
        }
    }
}
