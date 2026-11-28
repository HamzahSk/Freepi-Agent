/**
 * Shared extractor registry.
 *
 * Extensions never import provider-specific code. They call
 * `FreepiExtractors.resolve(urlOrProvider, options)` and the registry chooses a
 * registered extractor by explicit id, URL host, or provider metadata.
 *
 * Every extractor returns the same normalized shape:
 *   { extractor, media: [{ url, type, mimeType, quality, label, headers }],
 *     subtitles: [{ url, label, language }] }
 */
(function (global) {
  'use strict';

  var providers = Object.create(null);
  var order = [];

  function normalizeMedia(items, fallbackHeaders) {
    var seen = Object.create(null);
    var out = [];
    (Array.isArray(items) ? items : []).forEach(function (item) {
      if (!item || !item.url) return;
      var url;
      try { url = new URL(String(item.url)).href; } catch (error) { return; }
      if (!/^https?:$/.test(new URL(url).protocol) || seen[url]) return;
      seen[url] = 1;
      var type = String(item.type || '').toLowerCase();
      var mimeType = item.mimeType || mimeFor(url, type);
      out.push({
        url: url,
        type: type || typeFor(url, mimeType),
        mimeType: mimeType,
        quality: item.quality == null ? null : String(item.quality),
        label: item.label == null ? null : String(item.label),
        headers: Object.assign({}, fallbackHeaders || {}, item.headers || {})
      });
    });
    return out;
  }

  function normalizeSubtitles(items) {
    var seen = Object.create(null);
    var out = [];
    (Array.isArray(items) ? items : []).forEach(function (item) {
      if (!item || !item.url) return;
      var url;
      try { url = new URL(String(item.url)).href; } catch (error) { return; }
      if (!/^https?:$/.test(new URL(url).protocol) || seen[url]) return;
      seen[url] = 1;
      out.push({
        url: url,
        label: String(item.label || item.language || url.split('/').pop().split('?')[0] || 'Subtitle'),
        language: item.language == null ? null : String(item.language)
      });
    });
    return out;
  }

  function extensionOf(url) {
    try {
      var name = new URL(url).pathname.split('/').pop().toLowerCase();
      var dot = name.lastIndexOf('.');
      return dot >= 0 ? name.slice(dot + 1) : '';
    } catch (error) {
      return '';
    }
  }

  function mimeFor(url, type) {
    var extension = extensionOf(url);
    var kind = String(type || '').toLowerCase();
    if (kind === 'hls' || kind === 'application/x-mpegurl' || kind === 'application/vnd.apple.mpegurl' || extension === 'm3u8') {
      return 'application/vnd.apple.mpegurl';
    }
    if (kind === 'dash' || kind === 'application/dash+xml' || extension === 'mpd') return 'application/dash+xml';
    if (kind === 'webm' || extension === 'webm') return 'video/webm';
    if (kind === 'mp4' || kind === 'video/mp4' || extension === 'mp4' || extension === 'm4v' || extension === 'mov') return 'video/mp4';
    return '';
  }

  function typeFor(url, mimeType) {
    if (mimeType === 'application/vnd.apple.mpegurl') return 'hls';
    if (mimeType === 'application/dash+xml') return 'dash';
    if (mimeType === 'video/webm') return 'webm';
    if (mimeType === 'video/mp4') return 'mp4';
    return '';
  }

  function normalizeResult(provider, result, options) {
    var value = result || {};
    var headers = value.headers || (options && options.headers) || {};
    return {
      extractor: provider.id,
      media: normalizeMedia(value.media, headers),
      subtitles: normalizeSubtitles(value.subtitles)
    };
  }

  function identify(target, options) {
    var opts = options || {};
    var explicit = opts.extractor || opts.provider || opts.host;
    if (explicit && providers[String(explicit).toLowerCase()]) return providers[String(explicit).toLowerCase()];
    if (target && providers[String(target).toLowerCase()]) return providers[String(target).toLowerCase()];

    var url = typeof target === 'string' ? target : (opts.url || opts.pageUrl || '');
    var host = '';
    try { host = url ? new URL(url).hostname.toLowerCase() : ''; } catch (error) { host = ''; }
    for (var i = 0; i < order.length; i++) {
      var provider = providers[order[i]];
      if (!provider.matches) continue;
      try {
        if (provider.matches({ url: url, host: host, options: opts })) return provider;
      } catch (error) { /* keep looking */ }
    }
    return null;
  }

  function register(definition) {
    if (!definition || !definition.id || typeof definition.extract !== 'function') {
      throw new Error('Extractor registration requires an id and extract(options).');
    }
    var id = String(definition.id).toLowerCase();
    providers[id] = {
      id: id,
      name: definition.name || definition.id,
      capabilities: Array.isArray(definition.capabilities) ? definition.capabilities.slice() : [],
      matches: typeof definition.matches === 'function' ? definition.matches : null,
      extract: definition.extract
    };
    if (order.indexOf(id) < 0) order.push(id);
    return providers[id];
  }

  function resolve(target, options) {
    var opts = Object.assign({}, options || {});
    if (typeof target === 'object' && target !== null) {
      opts = Object.assign({}, target, opts);
    } else if (typeof target === 'string' && !opts.url) {
      // A bare provider id stays an id; anything else is treated as a URL.
      var explicit = opts.extractor || opts.provider || opts.host;
      if (!explicit && !providers[String(target).toLowerCase()]) opts.url = target;
    }
    var provider = identify(target, opts);
    if (!provider) {
      return Promise.reject(Object.assign(new Error('No shared extractor matched the requested provider.'), { code: 'UNSUPPORTED' }));
    }
    return Promise.resolve()
      .then(function () { return provider.extract(opts); })
      .then(function (result) { return normalizeResult(provider, result, opts); })
      .catch(function (error) {
        if (error && /^[A-Z_]+$/.test(String(error.code || ''))) throw error;
        throw Object.assign(new Error(provider.name + ': ' + (error && error.message || error)), { code: 'EXTRACTOR_ERROR', cause: error });
      });
  }

  function extract(target, options) {
    return resolve(target, options).then(function (result) { return result.media; });
  }

  global.FreepiExtractors = {
    register: register,
    resolve: resolve,
    extract: extract,
    identify: function (target, options) { return identify(target, options); },
    get: function (id) { return providers[String(id || '').toLowerCase()] || null; },
    list: function () {
      return order.map(function (id) {
        return { id: id, name: providers[id].name, capabilities: providers[id].capabilities.slice() };
      });
    },
    normalizeMedia: normalizeMedia,
    normalizeSubtitles: normalizeSubtitles,
    mimeFor: mimeFor,
    typeFor: typeFor
  };
})(typeof globalThis !== 'undefined' ? globalThis : this);
