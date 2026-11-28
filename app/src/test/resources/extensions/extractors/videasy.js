/**
 * Videasy / wecollege shared extractor.
 *
 * Extensions provide the TMDB identity (title, year, imdb id, tmdb id, season,
 * episode) and a server name. This extractor owns the seed, encrypted
 * /sources request, enc-dec.app decryption, HLS/DASH expansion, subtitle
 * normalization, and quality labels.
 */
(function (global) {
  'use strict';

  var registry = global.FreepiExtractors;
  var RT = global.FreepiRuntime;
  if (!registry || !RT) throw new Error('extractor-videasy requires extractor-registry and shared-runtime');
  var shared = registry.shared;

  var API_BASE = 'https://api.wecollege.net';
  var DECRYPTION_URL = 'https://enc-dec.app/api/dec-videasy';
  var CACHE_TTL = 60 * 1000;
  var CACHE_LIMIT = 64;
  var cache = new Map();

  // Keep this list in sync with extensions/movy/config.json. It is data, not
  // source logic, so every extension can reuse the same Videasy resolver.
  var SERVERS = [
    { name: 'Miami', path: 'miami', audio: 'Original', mayHave4K: true },
    { name: 'Boise', path: 'boise', audio: 'Original', mayHave4K: true },
    { name: 'Vegas', path: 'vegas', audio: 'Original' },
    { name: 'Reno', path: 'reno', audio: 'Original' },
    { name: 'Oakland', path: 'oakland', audio: 'Original' },
    { name: 'Seattle', path: 'seattle', audio: 'Original' },
    { name: 'Phoenix', path: 'phoenix', audio: 'Original' },
    { name: 'Atlanta', path: 'atlanta', audio: 'Original' },
    { name: 'Portland', path: 'portland', audio: 'Original' },
    { name: 'Austin', path: 'austin', audio: 'Original', qualityFilter: 'English' },
    { name: 'Dallas', path: 'dallas', audio: 'Original' },
    { name: 'Tampa', path: 'tampa', audio: 'Original' },
    { name: 'Orlando', path: 'orlando', audio: 'Original' },
    { name: 'Munich', path: 'munich', audio: 'German', language: 'german' },
    { name: 'Berlin', path: 'berlin', audio: 'German' },
    { name: 'Paris', path: 'paris', audio: 'French' },
    { name: 'Delhi', path: 'delhi', audio: 'Hindi', qualityFilter: 'Hindi' },
    { name: 'Cancun', path: 'cancun', audio: 'Spanish' }
  ];

  var GENERIC_QUALITY = {
    original: 1, auto: 1, video: 1, 'full video': 1, 'watch video': 1, 'play video': 1,
    hls: 1, dash: 1, stream: 1
  };

  function serverByName(name) {
    var key = String(name || '').toLowerCase();
    for (var i = 0; i < SERVERS.length; i++) {
      if (SERVERS[i].name.toLowerCase() === key) return SERVERS[i];
    }
    return null;
  }

  function origin(value, stripWww) {
    try {
      var url = new URL(value);
      var result = url.protocol + '//' + url.host;
      return stripWww ? result.replace('://www.', '://') : result;
    } catch (error) {
      return null;
    }
  }

  function pctEncode(value) {
    var bytes = new TextEncoder().encode(String(value));
    var out = '';
    var hex = '0123456789ABCDEF';
    for (var i = 0; i < bytes.length; i++) {
      var c = bytes[i];
      var unreserved = (c >= 0x30 && c <= 0x39) || (c >= 0x41 && c <= 0x5A) ||
        (c >= 0x61 && c <= 0x7A) || c === 0x2D || c === 0x2E || c === 0x5F || c === 0x7E;
      if (unreserved) out += String.fromCharCode(c);
      else out += '%' + hex[(c >> 4) & 0x0F] + hex[c & 0x0F];
    }
    return out;
  }

  function doubleEncode(value) {
    return pctEncode(pctEncode(value));
  }

  function qualityValue(value) {
    var text = String(value || '');
    var match = /(\d{3,4})[pP]?/.exec(text);
    if (match) return Number(match[1]);
    if (/4k/i.test(text)) return 2160;
    return 0;
  }

  function isRealResolution(value) {
    var text = String(value || '').trim();
    if (!text) return false;
    return /(\d{3,4})[pP]?/.test(text) || /4k/i.test(text) || /^\d+$/.test(text);
  }

  function isGenericQuality(value) {
    var text = String(value || '').trim().toLowerCase();
    return !!GENERIC_QUALITY[text] || /^(video|stream|hls|dash)(\s+.*)?$/.test(text);
  }

  function isLanguageQuality(server, quality) {
    if (isRealResolution(quality) || /4k/i.test(quality)) return false;
    if (isGenericQuality(quality)) return true;
    return String(quality || '').toLowerCase() === String(server.audio || '').toLowerCase() ||
      (!!server.qualityFilter && String(quality || '').toLowerCase() === String(server.qualityFilter).toLowerCase());
  }

  function containerOf(url) {
    var extension = shared.extensionOf(url);
    if (extension === 'm3u8') return 'HLS';
    if (extension === 'mpd') return 'DASH';
    if (extension === 'mkv') return 'MKV';
    if (extension === 'mp4' || extension === 'm4v' || extension === 'mov') return 'MP4';
    if (extension === 'webm') return 'WebM';
    return null;
  }

  function buildLabel(server, quality, url, subtitleCount) {
    var parts = [server.name];
    if (!isLanguageQuality(server, quality)) {
      if (quality) parts.push(String(quality));
      if (/2160|4k/i.test(String(quality)) && !/4k/i.test(String(quality))) parts.push('4K');
    }
    var container = containerOf(url);
    if (container) parts.push(container);
    if (server.audio) parts.push(server.audio + ' audio');
    if (subtitleCount > 0) parts.push(subtitleCount + ' subs');
    return parts.join(' · ');
  }

  function mediaEntry(url, type, quality, label, headers) {
    return { url: url, type: type || '', quality: quality || null, label: label, headers: headers || {} };
  }

  function normalizeSubtitles(result, limit) {
    var out = [];
    var list = Array.isArray(result && result.subtitles) ? result.subtitles : [];
    for (var i = 0; i < list.length && out.length < Math.max(0, Number(limit) || 0); i++) {
      var item = list[i] || {};
      var raw = item.url || item.file || item.src || item.path;
      var url = shared.mediaUrl(raw, 'https://movie-source.invalid/');
      if (!url || !/\.(?:srt|vtt)(?:\?|#|$)/i.test(url)) continue;
      out.push({
        url: url,
        label: item.label || item.name || item.language || item.lang || 'Subtitle',
        language: item.language || item.lang || item.srclang || null
      });
    }
    return out;
  }

  function expandIfNeeded(server, item, headers) {
    var url = item.url;
    var quality = item.quality == null ? 'Auto' : String(item.quality);
    var lower = url.toLowerCase();
    var hls = lower.indexOf('.m3u8') >= 0 || /application\/(?:x-mpegurl|vnd\.apple\.mpegurl)/i.test(String(item.type || ''));
    var dash = lower.indexOf('.mpd') >= 0 || /application\/dash\+xml/i.test(String(item.type || ''));
    var languageQuality = isLanguageQuality(server, quality);
    var generic = isGenericQuality(quality);
    var mustExpand = server.name === 'Phoenix' ||
      (!isRealResolution(quality) && (hls || dash || languageQuality || generic));
    if (!mustExpand) {
      return Promise.resolve([mediaEntry(url, hls ? 'hls' : dash ? 'dash' : '', quality,
        buildLabel(server, quality, url, item.subtitleCount), headers)]);
    }
    return registry.playlist.expand(url, {
      type: hls ? 'hls' : dash ? 'dash' : '',
      headers: headers
    }).then(function (expanded) {
      if (!expanded.media.length) {
        return [mediaEntry(url, hls ? 'hls' : dash ? 'dash' : '', quality,
          buildLabel(server, quality, url, item.subtitleCount), headers)];
      }
      return expanded.media.map(function (entry) {
        return mediaEntry(entry.url, entry.type || (hls ? 'hls' : 'dash'), entry.quality || quality,
          buildLabel(server, entry.quality || quality, entry.url, item.subtitleCount), headers);
      });
    });
  }

  function cacheKey(server, options) {
    return [server.name, options.path, options.title, options.year, options.imdbId].join('|');
  }

  function readCache(key) {
    var item = cache.get(key);
    if (!item || item.expires <= Date.now()) {
      if (item) cache.delete(key);
      return null;
    }
    return item.value;
  }

  function writeCache(key, value) {
    if (cache.size >= CACHE_LIMIT) {
      var oldest = cache.keys().next();
      if (!oldest.done) cache.delete(oldest.value);
    }
    cache.set(key, { value: value, expires: Date.now() + CACHE_TTL });
  }

  function requestSeed(options) {
    var tmdbId = String(options.tmdbId || '');
    var apiOrigin = origin(options.baseUrl || 'https://db.wecollege.net', false) || 'https://db.wecollege.net';
    var headers = Object.assign({
      Referer: apiOrigin + '/',
      Origin: apiOrigin,
      Accept: 'application/json, text/plain, */*'
    }, options.headers || {});
    return RT.json(API_BASE + '/seed?mediaId=' + encodeURIComponent(tmdbId), {
      headers: headers,
      label: 'Videasy seed'
    }).then(function (value) {
      var seed = value && value.seed;
      if (!seed) throw Object.assign(new Error('Videasy seed response did not contain a seed.'), { code: 'API_RESPONSE' });
      return { seed: String(seed), headers: headers };
    });
  }

  function buildSourcesUrl(server, options, seed) {
    var path = String(options.path || '').split('/').filter(Boolean);
    var tmdbId = String(options.tmdbId || path[1] || '');
    var mediaType = options.mediaType || (path[0] === 'movie' ? 'movie' : 'tv');
    var params = [
      'title=' + doubleEncode(options.title || ''),
      'mediaType=' + encodeURIComponent(mediaType),
      'year=' + encodeURIComponent(options.year || ''),
      'tmdbId=' + encodeURIComponent(tmdbId)
    ];
    if (mediaType !== 'movie') {
      params.push('episodeId=' + encodeURIComponent(options.episode || path[3] || ''));
      params.push('seasonId=' + encodeURIComponent(options.season || path[2] || ''));
    }
    if (options.imdbId) params.push('imdbId=' + encodeURIComponent(options.imdbId));
    if (server.language) params.push('language=' + encodeURIComponent(server.language));
    params.push('enc=2');
    params.push('seed=' + encodeURIComponent(seed));
    return API_BASE + '/' + server.path + '/sources?' + params.join('&');
  }

  function decrypt(textValue, tmdbId, seed) {
    return RT.postJson(DECRYPTION_URL, { text: textValue, id: String(tmdbId), seed: String(seed) }, {
      headers: { Referer: 'https://enc-dec.app/', Origin: 'https://enc-dec.app' },
      label: 'Videasy decryption'
    }).then(function (value) {
      if (!value || value.status === 'error' || value.error) {
        throw Object.assign(new Error('Videasy decryption was rejected.'), { code: 'API_RESPONSE' });
      }
      if (!value.result || typeof value.result !== 'object') {
        throw Object.assign(new Error('Videasy decryption returned no result.'), { code: 'API_RESPONSE' });
      }
      return value.result;
    });
  }

  function extract(options) {
    var server = typeof options.server === 'object' && options.server
      ? options.server
      : serverByName(options.server);
    if (!server) {
      return Promise.reject(Object.assign(new Error('Unknown Videasy server: ' + options.server), { code: 'UNSUPPORTED' }));
    }
    var path = String(options.path || '');
    var parts = path.split('/');
    if (parts[0] !== 'movie' && parts[0] !== 'tv') {
      return Promise.reject(Object.assign(new Error('Videasy requires a movie/<id> or tv/<id>/<season>/<episode> path.'), { code: 'MALFORMED' }));
    }
    var key = cacheKey(server, options);
    var cached = readCache(key);
    if (cached) return Promise.resolve(buildResult(server, cached, options));

    var apiOrigin = origin(options.baseUrl || 'https://db.wecollege.net', false) || 'https://db.wecollege.net';
    var streamHeaders = Object.assign({
      Referer: apiOrigin + '/',
      Origin: apiOrigin
    }, options.headers || {});

    return requestSeed(options).then(function (seedInfo) {
      var url = buildSourcesUrl(server, options, seedInfo.seed);
      return RT.text(url, { headers: seedInfo.headers, label: 'Videasy sources' }).then(function (encrypted) {
        if (!encrypted || !encrypted.trim()) {
          throw Object.assign(new Error('Videasy sources response was empty.'), { code: 'EMPTY_PAGE' });
        }
        return decrypt(encrypted, options.tmdbId || parts[1], seedInfo.seed);
      });
    }).then(function (result) {
      writeCache(key, result);
      return buildResult(server, result, options);
    });
  }

  function buildResult(server, result, options) {
    var subLimit = options.subLimit == null ? 25 : Math.max(0, Number(options.subLimit) || 0);
    var subtitles = normalizeSubtitles(result, subLimit);
    var apiOrigin = origin(options.baseUrl || 'https://db.wecollege.net', false) || 'https://db.wecollege.net';
    var headers = { Referer: apiOrigin + '/', Origin: apiOrigin };

    var raw = [];
    var sources = Array.isArray(result.sources) ? result.sources : [];
    if (server.qualityFilter) {
      sources = sources.filter(function (item) {
        return String(item && item.quality || '').toLowerCase() === String(server.qualityFilter).toLowerCase();
      });
    }
    sources.forEach(function (item) {
      if (!item || !item.url) return;
      var url = shared.mediaUrl(item.url, apiOrigin);
      if (!url) return;
      raw.push({ url: url, quality: item.quality, type: '', subtitleCount: subtitles.length });
    });
    if (result.streams && typeof result.streams === 'object') {
      Object.keys(result.streams).forEach(function (quality) {
        var url = shared.mediaUrl(result.streams[quality], apiOrigin);
        if (url) raw.push({ url: url, quality: quality, type: '', subtitleCount: subtitles.length });
      });
    }
    if (!raw.length && result.url) {
      var direct = shared.mediaUrl(result.url, apiOrigin);
      if (direct) raw.push({ url: direct, quality: 'Auto', type: '', subtitleCount: subtitles.length });
    }

    var seen = Object.create(null);
    raw = raw.filter(function (item) {
      if (!item || seen[item.url]) return false;
      seen[item.url] = 1;
      return true;
    });

    return Promise.all(raw.map(function (item) {
      return expandIfNeeded(server, item, headers).catch(function () {
        return [mediaEntry(item.url, '', item.quality, buildLabel(server, item.quality, item.url, subtitles.length), headers)];
      });
    })).then(function (groups) {
      var media = [];
      groups.forEach(function (items) { media = media.concat(items); });
      return { media: media, subtitles: subtitles, headers: headers, server: server.name };
    });
  }

  registry.register({
    id: 'videasy',
    name: 'Videasy',
    capabilities: ['hls', 'dash', 'mp4', 'subtitles', 'multi-server'],
    matches: function (context) {
      var opts = context.options || {};
      if (String(opts.extractor || opts.provider || '').toLowerCase() === 'videasy') return true;
      if (opts.server && typeof opts.server === 'object' && opts.server.apiBase) return true;
      if (context.host === 'api.wecollege.net' || context.host === 'db.wecollege.net' || context.host === 'enc-dec.app') return true;
      return false;
    },
    extract: extract
  });

  // Server metadata is intentionally public so catalog extensions can build
  // provider pickers without duplicating the resolver's server table.
  registry.videasy = {
    SERVERS: SERVERS,
    serverNames: SERVERS.map(function (server) { return server.name; }),
    serverByName: serverByName
  };
})(typeof globalThis !== 'undefined' ? globalThis : this);
