/**
 * Dailymotion shared extractor.
 *
 * Resolves `dailymotion.com/video/<id>` and `dai.ly/<id>` embed pages by
 * reading the public `window.__PLAYER_CONFIG__` payload and, when needed,
 * the `dailymotion.com/player/metadata/video/<id>` JSON endpoint.
 */
(function (global) {
  'use strict';

  var registry = global.FreepiExtractors;
  var RT = global.FreepiRuntime;
  if (!registry || !RT) throw new Error('extractor-dailymotion requires extractor-registry and shared-runtime');
  var shared = registry.shared;

  function videoId(url) {
    var value = String(url || '');
    var match = /(?:video\/([a-z0-9]+)|dai\.ly\/([a-z0-9]+))(?:\?|#|$)/i.exec(value.replace(/^https?:\/\/(?:www\.)?/, ''));
    return match ? (match[1] || match[2]).toLowerCase() : null;
  }

  function playerConfig(html) {
    var pattern = /window\.__PLAYER_CONFIG__\s*=\s*({[\s\S]*?});?\s*(?:<\/script>|<\/head>|$)/i;
    var match = pattern.exec(html || '');
    if (!match) return null;
    try {
      return JSON.parse(match[1].replace(/,\s*([}\]])/g, '$1'));
    } catch (error) {
      // Tolerate a trailing comma once more with a brace balance extraction.
      try {
        var start = match[1].indexOf('{');
        var depth = 0;
        var end = start;
        for (; end < match[1].length; end++) {
          if (match[1][end] === '{') depth++;
          else if (match[1][end] === '}') { depth--; if (depth === 0) break; }
        }
        return JSON.parse(match[1].slice(start, end + 1));
      } catch (error2) {
        return null;
      }
    }
  }

  function fromConfig(config, pageUrl) {
    var items = [];
    var qualities = config && config.metadata && config.metadata.qualities;
    var root = config && config.context && config.context.keywords;
    if (qualities && typeof qualities === 'object') {
      Object.keys(qualities).forEach(function (key) {
        var entry = qualities[key];
        if (!entry) return;
        var url = shared.mediaUrl(entry.url, pageUrl);
        if (url) items.push({ url: url, kind: 'media', type: String(entry.type || ''), quality: key });
      });
    }
    var collected = shared.collectMedia({ config: config }, pageUrl);
    collected.forEach(function (item) {
      if (item.kind === 'media' && !items.some(function (existing) { return existing.url === item.url; })) {
        var type = String(item.type || '').toLowerCase();
        items.push({ url: item.url, kind: 'media', type: type, quality: item.quality });
      }
    });
    return items;
  }

  function fromMetadata(metadata, pageUrl) {
    var items = [];
    if (metadata && metadata.qualities && typeof metadata.qualities === 'object') {
      Object.keys(metadata.qualities).forEach(function (key) {
        var entry = metadata.qualities[key];
        if (!entry || !entry.url) return;
        var url = shared.mediaUrl(entry.url, pageUrl);
        if (url) items.push({ url: url, kind: 'media', type: String(entry.type || (/mp4/i.test(url) ? 'mp4' : 'hls')), quality: key });
      });
    }
    return items;
  }

  function extract(options) {
    var url = options.url || options.pageUrl || '';
    if (!url) return Promise.reject(Object.assign(new Error('Dailymotion extractor requires a video URL.'), { code: 'MALFORMED' }));
    var id = videoId(url);
    if (!id) return Promise.reject(Object.assign(new Error('Dailymotion URL does not contain a video id.'), { code: 'MALFORMED' }));
    var pageUrl = 'https://www.dailymotion.com/embed/video/' + id;
    var headers = Object.assign({ Referer: 'https://www.dailymotion.com/', 'User-Agent': 'Mozilla/5.0' }, options.headers || {});

    return RT.text(pageUrl, { headers: headers, label: 'Dailymotion embed' }).then(function (html) {
      var config = playerConfig(html);
      if (config && config.metadata && config.metadata.qualities) {
        return Promise.resolve(fromConfig(config, pageUrl));
      }
      var metadataUrl = 'https://www.dailymotion.com/player/metadata/video/' + id;
      return RT.json(metadataUrl, { headers: headers, label: 'Dailymotion metadata' })
        .then(function (metadata) { return fromMetadata(metadata, pageUrl); });
    }).then(function (found) {
      var media = [];
      var seen = Object.create(null);
      found.forEach(function (item) {
        if (item.kind !== 'media' || seen[item.url]) return;
        seen[item.url] = 1;
        var type = String(item.type || (/m3u8/i.test(item.url) ? 'hls' : /mpd/i.test(item.url) ? 'dash' : 'mp4')).toLowerCase();
        media.push({ url: item.url, type: type === 'video/mp4' ? 'mp4' : type, mimeType: type === 'video/mp4' ? 'video/mp4' : '', quality: item.quality || null, headers: headers });
      });
      return { media: media, subtitles: [] };
    });
  }

  registry.register({
    id: 'dailymotion',
    name: 'Dailymotion',
    capabilities: ['hls', 'mp4', 'subtitles'],
    matches: function (context) {
      var opts = context.options || {};
      if (String(opts.extractor || opts.provider || '').toLowerCase() === 'dailymotion') return true;
      return /(^|\.)dailymotion\.com$/i.test(context.host) || context.host === 'dai.ly';
    },
    extract: extract
  });
})(typeof globalThis !== 'undefined' ? globalThis : this);