/**
 * Freepi shared runtime (WebView-safe, no ES modules, no Node APIs).
 *
 * The Android client concatenates this module with the extension module and
 * evaluates both inside an isolated WebView. Everything an extension needs from
 * the host is exposed here through `globalThis.FreepiRuntime`.
 */
(function (global) {
  'use strict';

  var SCHEMAS = {
    HOME: 'freepi.home.v1',
    LIST: 'freepi.list.v1',
    DETAIL: 'freepi.detail.v1',
    EPISODES: 'freepi.episodes.v1',
    STREAM: 'freepi.stream.v1',
    ERROR: 'freepi.error.v1'
  };

  var ERROR_CODES = {
    ACCESS_BLOCKED: 'ACCESS_BLOCKED',
    NETWORK: 'NETWORK',
    TIMEOUT: 'TIMEOUT',
    MALFORMED: 'MALFORMED',
    EMPTY_PAGE: 'EMPTY_PAGE',
    JS_ERROR: 'JS_ERROR',
    UNSUPPORTED: 'UNSUPPORTED',
    PLAYER_METADATA: 'PLAYER_METADATA',
    API_RESPONSE: 'API_RESPONSE'
  };

  function fail(code, message) {
    var error = new Error(String(message == null ? code : message));
    error.name = 'FreepiError';
    error.code = code || ERROR_CODES.JS_ERROR;
    return error;
  }

  function copyHeaders(headers) {
    var out = {};
    if (!headers) return out;
    if (typeof headers.forEach === 'function' && typeof headers.get === 'function') {
      headers.forEach(function (value, key) { out[String(key)] = String(value); });
      return out;
    }
    if (Array.isArray(headers)) {
      headers.forEach(function (entry) {
        if (entry && entry.length >= 2) out[String(entry[0])] = String(entry[1]);
      });
      return out;
    }
    Object.keys(headers).forEach(function (key) {
      if (headers[key] != null) out[String(key)] = String(headers[key]);
    });
    return out;
  }

  function bridgeFetch(url, init) {
    var method = String((init && init.method) || 'GET').toUpperCase();
    if (method !== 'GET' && method !== 'POST' && method !== 'HEAD') {
      return Promise.reject(fail(ERROR_CODES.UNSUPPORTED, 'Unsupported HTTP method: ' + method));
    }
    var payload = {
      url: String(url),
      method: method,
      headers: copyHeaders(init && init.headers)
    };
    if (method !== 'GET' && method !== 'HEAD' && init && init.body != null) {
      payload.body = String(init.body);
    }
    var raw;
    try {
      raw = global.MovieSourceHttp.request(JSON.stringify(payload));
    } catch (error) {
      return Promise.reject(fail(ERROR_CODES.NETWORK, 'Native source request failed: ' + (error && error.message || error)));
    }
    var parsed;
    try { parsed = JSON.parse(raw); } catch (error) {
      return Promise.reject(fail(ERROR_CODES.MALFORMED, 'Native source bridge returned invalid JSON.'));
    }
    if (parsed.error) {
      var code = /https/i.test(String(parsed.error)) ? ERROR_CODES.UNSUPPORTED : ERROR_CODES.NETWORK;
      return Promise.reject(fail(code, String(parsed.error)));
    }
    var body = String(parsed.body == null ? '' : parsed.body);
    var status = Number(parsed.status) || 0;
    return Promise.resolve({
      ok: status >= 200 && status < 300,
      status: status,
      url: String(parsed.url || url),
      headers: {
        get: function (name) { return null; }
      },
      text: function () { return Promise.resolve(body); },
      json: function () {
        try { return Promise.resolve(JSON.parse(body)); }
        catch (error) { return Promise.reject(fail(ERROR_CODES.MALFORMED, 'Response is not valid JSON.')); }
      }
    });
  }

  function fetchImpl(url, init) {
    if (global.MovieSourceHttp && typeof global.MovieSourceHttp.request === 'function') {
      return bridgeFetch(url, init || {});
    }
    if (typeof global.fetch === 'function') {
      return global.fetch(url, init || {});
    }
    return Promise.reject(fail(ERROR_CODES.NETWORK, 'No HTTP transport is available.'));
  }

  function request(url, init) {
    var target = String(url);
    var options = init || {};
    return fetchImpl(target, options).then(function (response) {
      if (response.ok) return response;
      var status = Number(response.status) || 0;
      var code = status === 401 || status === 403 || status === 429 || status === 451
        ? ERROR_CODES.ACCESS_BLOCKED
        : ERROR_CODES.NETWORK;
      var label = (options.label ? options.label + ': ' : '');
      return response.text().catch(function () { return ''; }).then(function (body) {
        if (/just a moment|cf-chl|challenge-platform|attention required/i.test(body)) {
          code = ERROR_CODES.ACCESS_BLOCKED;
        }
        throw fail(code, label + 'upstream returned HTTP ' + status + '.');
      });
    });
  }

  function text(url, init) {
    return request(url, init).then(function (response) { return response.text(); });
  }

  function json(url, init) {
    return request(url, init).then(function (response) {
      return response.text().then(function (body) {
        try { return JSON.parse(body); }
        catch (error) {
          throw fail(ERROR_CODES.MALFORMED, (init && init.label ? init.label + ': ' : '') + 'upstream returned invalid JSON.');
        }
      });
    });
  }

  function postJson(url, value, init) {
    var options = init || {};
    options.method = 'POST';
    options.headers = Object.assign({
      accept: 'application/json',
      'content-type': 'application/json'
    }, options.headers || {});
    options.body = JSON.stringify(value);
    return json(url, options);
  }

  function absolute(value, base) {
    if (value == null) return null;
    var raw = String(value).trim();
    if (!raw) return null;
    if (/^data:|^blob:|^javascript:/i.test(raw)) return null;
    try {
      var url = new URL(raw, base || 'https://movie-source.invalid/');
      if ((url.protocol !== 'http:' && url.protocol !== 'https:') || url.username || url.password) return null;
      return url.href;
    } catch (error) {
      return null;
    }
  }

  function mediaUrl(value, base) {
    var url = absolute(value, base);
    if (!url) return null;
    return url;
  }

  function cleanText(value) {
    return String(value == null ? '' : value).replace(/\s+/g, ' ').trim();
  }

  function cleanTitle(value) {
    return cleanText(value)
      .replace(/\s+Film Subtitle.*$/i, '')
      .replace(/\s+Sub Indo.*$/i, '')
      .replace(/\s*\|\s*Streaming.*$/i, '')
      .replace(/^Nonton\s+/i, '')
      .trim();
  }

  function number(value) {
    var match = String(value == null ? '' : value).match(/\d+(?:[.,]\d+)?/);
    return match ? match[0].replace(',', '.') : null;
  }

  function integer(value, fallback) {
    var parsed = parseInt(String(value == null ? '' : value), 10);
    return Number.isFinite(parsed) ? parsed : fallback;
  }

  function unique(values) {
    var seen = Object.create(null);
    var out = [];
    (Array.isArray(values) ? values : []).forEach(function (value) {
      var key = typeof value === 'string' ? value : JSON.stringify(value);
      if (key == null || seen[key]) return;
      seen[key] = 1;
      out.push(value);
    });
    return out;
  }

  function asArray(value) {
    if (Array.isArray(value)) return value;
    if (value == null) return [];
    return [value];
  }

  function today() {
    return new Date().toISOString().slice(0, 10);
  }

  function sourceInfo(requestedUrl, finalUrl, title) {
    return {
      requestedUrl: String(requestedUrl || ''),
      finalUrl: String(finalUrl || requestedUrl || ''),
      title: title == null ? '' : String(title),
      fetchedAt: Date.now()
    };
  }

  function envelope(schema, data, source) {
    return {
      schema: schema,
      ok: true,
      source: source || sourceInfo('', ''),
      data: data == null ? {} : data
    };
  }

  function errorEnvelope(code, message, source) {
    return {
      schema: SCHEMAS.ERROR,
      ok: false,
      source: source || sourceInfo('', ''),
      error: { code: String(code || ERROR_CODES.JS_ERROR), message: String(message || 'Unknown source error').slice(0, 500) }
    };
  }

  function guard(operation, source, args) {
    return Promise.resolve()
      .then(function () { return operation(args || {}); })
      .catch(function (error) {
        if (error && /^[A-Z_]+$/.test(String(error.code || ''))) {
          return errorEnvelope(error.code, error.message, source);
        }
        return errorEnvelope(ERROR_CODES.JS_ERROR, error && error.message || String(error), source);
      });
  }

  function mapLimit(items, limit, worker) {
    var list = Array.isArray(items) ? items.slice() : [];
    var results = new Array(list.length);
    var cursor = 0;
    var width = Math.max(1, Math.min(Number(limit) || 1, list.length || 1));
    function run() {
      if (cursor >= list.length) return Promise.resolve();
      var index = cursor++;
      return Promise.resolve()
        .then(function () { return worker(list[index], index); })
        .then(function (value) { results[index] = value; })
        .catch(function () { results[index] = null; })
        .then(run);
    }
    var workers = [];
    for (var i = 0; i < width; i++) workers.push(run());
    return Promise.all(workers).then(function () { return results; });
  }

  function logger(scope) {
    var prefix = '[Freepi:' + scope + ']';
    function emit(level, args) {
      if (typeof global.console === 'undefined') return;
      var fn = global.console[level] || global.console.log;
      if (typeof fn === 'function') fn.apply(global.console, [prefix].concat(Array.prototype.slice.call(args)));
    }
    return {
      debug: function () { emit('debug', arguments); },
      info: function () { emit('info', arguments); },
      warn: function () { emit('warn', arguments); },
      error: function () { emit('error', arguments); }
    };
  }

  global.FreepiRuntime = {
    SCHEMAS: SCHEMAS,
    ERROR_CODES: ERROR_CODES,
    fail: fail,
    fetch: fetchImpl,
    request: request,
    text: text,
    json: json,
    getJson: function (url, init) { return json(url, init); },
    postJson: postJson,
    absolute: absolute,
    mediaUrl: mediaUrl,
    cleanText: cleanText,
    cleanTitle: cleanTitle,
    number: number,
    integer: integer,
    unique: unique,
    asArray: asArray,
    today: today,
    sourceInfo: sourceInfo,
    envelope: envelope,
    errorEnvelope: errorEnvelope,
    guard: guard,
    mapLimit: mapLimit,
    logger: logger,
    headers: copyHeaders
  };
})(typeof globalThis !== 'undefined' ? globalThis : this);
