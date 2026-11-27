package com.freepi.lk21.data.remote

import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.util.Base64
import java.util.regex.Pattern

/**
 * Rhino-based structured-JS extraction for the HTTP parse path.
 *
 * Scope: Rhino is a headless JS engine - it has no DOM and no browser. It is
 * used here only to execute the *well-scoped* JS the captured site ships
 * inside its HTML (JSON blocks such as `#season-data`/`#watch-history-data`,
 * `application/ld+json` blocks, `atob(...)` payloads, small inline
 * initialisers), plus the classic "p.a.c.k.e.r" deobfuscation that provider
 * pages rely on.
 *
 * The full DOM extraction continues to run in the hidden WebView (Chromium);
 * Rhino never renders or browses. The engine is initialised once and reused,
 * because creating a [Context] + standard scope per call is expensive on
 * Android, and every call is serialised behind a lock.
 */
object JsExtractor {

    private const val MAX_INPUT = 300_000
    private const val MAX_OUTPUT = 2_000_000

    private val SEASON_BLOCK = Pattern.compile(
        """<script\s+id=["']season-data["'][^>]*type=["']application/json["'][^>]*>(.*?)</script>""",
        Pattern.CASE_INSENSITIVE or Pattern.DOTALL
    )
    private val WATCH_BLOCK = Pattern.compile(
        """<script\s+id=["']watch-history-data["'][^>]*type=["']application/json["'][^>]*>(.*?)</script>""",
        Pattern.CASE_INSENSITIVE or Pattern.DOTALL
    )
    private val LDJSON_BLOCK = Pattern.compile(
        """<script\s+type=["']application/ld\+json["'][^>]*>(.*?)</script>""",
        Pattern.CASE_INSENSITIVE or Pattern.DOTALL
    )
    private val PACKED_BLOCK = Regex(
        """eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*[dr]\s*\)\s*\{[\s\S]*?\}\s*\(\s*'((?:\\.|[^'])*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:\\.|[^'])*)'\s*\.\s*split\s*\(\s*'\|'\s*\)\s*,\s*\d+\s*,\s*\{[^}]*\}\s*\)\s*\)"""
    )

    @Volatile
    private var engineScope: Scriptable? = null

    // ----------------------------------------------------------------- engine

    private fun <T> inContext(block: (Context, Scriptable) -> T): T? {
        val ctx = try {
            Context.enter()
        } catch (t: Throwable) {
            return null
        }
        return try {
            ctx.optimizationLevel = -1 // interpretive mode: no bytecode generation
            ctx.languageVersion = Context.VERSION_ES6
            val scope = synchronized(this) {
                engineScope ?: run {
                    val fresh = ctx.initSafeStandardObjects()
                    engineScope = fresh
                    fresh
                }
            }
            synchronized(this) {
                block(ctx, scope)
            }
        } catch (t: Throwable) {
            null
        } finally {
            Context.exit()
        }
    }

    // ---------------------------------------------------------------- JSON blocks

    /**
     * Parses [json] with Rhino's own JSON parser and converts the result into
     * org.json values. Returns null on blank/malformed/oversized input.
     */
    fun parseJson(json: String?): Any? {
        if (json.isNullOrBlank() || json.length > MAX_INPUT) return null
        return inContext { ctx, scope ->
            runCatching {
                val reviverExpr = "(function(k,v){return v;})"
                val reviver = ctx.evaluateString(scope, reviverExpr, "freepi-reviver", 1, null)
                val callable = reviver as? org.mozilla.javascript.Callable
                    ?: return@inContext null
                val parsed = org.mozilla.javascript.NativeJSON.parse(
                    ctx, scope, json, callable
                )
                if (parsed == null || parsed is Undefined) null
                else toOrgJson(ctx, scope, parsed)
            }.getOrNull()
        }
    }

    /** Captured `#season-data` JSON block -> JSONObject (or null). */
    fun seasonData(html: String?): JSONObject? {
        val raw = html ?: return null
        if (raw.isEmpty()) return null
        val m = SEASON_BLOCK.matcher(takeInput(raw))
        return if (m.find()) parseJson(cleanJsonText(m.group(1))) as? JSONObject else null
    }

    /** Captured `#watch-history-data` JSON block -> JSONObject (or null). */
    fun watchHistory(html: String?): JSONObject? {
        val raw = html ?: return null
        if (raw.isEmpty()) return null
        val m = WATCH_BLOCK.matcher(takeInput(raw))
        return if (m.find()) parseJson(cleanJsonText(m.group(1))) as? JSONObject else null
    }

    /** All `application/ld+json` blocks found in the page. */
    fun jsonLd(html: String?): List<JSONObject> {
        val raw = html ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        val matcher = LDJSON_BLOCK.matcher(takeInput(raw))
        val out = ArrayList<JSONObject>()
        while (matcher.find() && out.size < 12) {
            when (val parsed = parseJson(matcher.group(1))) {
                is JSONObject -> out.add(parsed)
                is JSONArray -> for (i in 0 until parsed.length()) {
                    parsed.optJSONObject(i)?.let { out.add(it) }
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Executes a small, self-contained JS expression (e.g. `atob("...")`, a
     * JSON literal, a one-line initialiser) inside Rhino and returns the result
     * as an org.json value.
     */
    fun evalExpression(expression: String?): Any? {
        val expr = expression?.takeIf { it.isNotBlank() && it.length < 8_000 } ?: return null
        return inContext { ctx, scope ->
            runCatching {
                val result = ctx.evaluateString(scope, expr, "freepi-extract", 1, null)
                if (result == null || result is Undefined) null else toOrgJson(ctx, scope, result)
            }.getOrNull()
        }
    }

    /** Decodes `atob("...")` / plain base64 payloads found in the HTML. */
    fun decodeAtob(html: String?): List<String> {
        val raw = html ?: return emptyList()
        val out = ArrayList<String>()
        val re = Regex("""atob\(\s*["']([A-Za-z0-9+/=\s]+)["']\s*\)""")
        for (match in re.findAll(raw).take(40)) {
            runCatching {
                val decoded = String(Base64.getDecoder().decode(match.groupValues[1].trim()))
                if (decoded.isNotBlank()) out.add(decoded)
            }
        }
        return out
    }

    /**
     * Classic Dean Edwards p.a.c.k.e.r deobfuscation executed by Rhino itself
     * (the exact captured `eval(function(p,a,c,k,e,d){...})` wrapper minus the
     * `eval`, whose result is the unpacked source). Returns one entry per
     * packed block.
     */
    fun unpackPacker(html: String?): List<String> {
        val raw = html ?: return emptyList()
        val input = raw.take(MAX_INPUT)
        val out = ArrayList<String>()
        for (m in PACKED_BLOCK.findAll(input).take(5)) {
            val full = m.value
            if (!full.startsWith("eval(") || !full.endsWith(")")) continue
            // "eval(function(...))" -> "(function(...))" and evaluate.
            val expression = "(" + full.substring("eval(".length, full.length - 1) + ")"
            val result = inContext { ctx, scope ->
                runCatching {
                    val value = ctx.evaluateString(scope, expression, "freepi-packer", 1, null)
                    if (value == null || value is Undefined) null
                    else Context.toString(value)
                }.getOrNull()
            }?.takeIf { it.isNotBlank() && it.length <= MAX_OUTPUT }
            if (result != null) out.add(result)
        }
        return out
    }

    // --------------------------------------------------------------- conversion

    /**
     * Converts a Rhino value into org.json. The value is round-tripped through
     * Rhino's own JSON.stringify because direct property access on NativeObject
     * misbehaves for numeric-looking keys (the site's season maps are keyed by
     * "1", "2", ...) - stringify produces plain JSON text that org.json parses
     * reliably.
     */
    private fun toOrgJson(ctx: Context, scope: Scriptable, value: Any?): Any? {
        if (value == null || value is Undefined) return null
        return runCatching {
            when {
                value is String -> value
                value is Number || value is Boolean -> {
                    val jsonText = org.mozilla.javascript.NativeJSON.stringify(ctx, scope, value, null, null)
                    if (jsonText == null) null else parseText(Context.toString(jsonText))
                }
                value is Scriptable -> {
                    val jsonText = org.mozilla.javascript.NativeJSON.stringify(ctx, scope, value, null, null)
                    if (jsonText == null) null else parseText(Context.toString(jsonText))
                }
                else -> Context.toString(value)
            }
        }.getOrNull()
    }

    private fun parseText(jsonText: String): Any? {
        val t = jsonText.trim()
        return when {
            t.startsWith("{") -> JSONObject(t)
            t.startsWith("[") -> JSONArray(t)
            t == "true" || t == "false" -> t.toBoolean()
            t == "null" -> null
            t.matches(Regex("-?\\d+(\\.\\d+)?")) ->
                if (t.contains('.')) t.toDouble() else t.toLong()
            else -> {
                // Rhino stringifies bare strings with quotes; also handle already
                // unquoted text (e.g. an expression result).
                if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) t.substring(1, t.length - 1) else t
            }
        }
    }

    private fun cleanJsonText(raw: String): String {
        var t = raw.trim().removePrefix("\uFEFF")
        if (t.startsWith("//")) {
            t = t.substring(t.indexOf('\n') + 1).trim()
        }
        return t
    }

    private fun takeInput(raw: String): String =
        if (raw.length <= MAX_INPUT) raw else raw.substring(0, MAX_INPUT)
}