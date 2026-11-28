/**
 * OK.ru shared extractor (generic, not bound to a catalog extension).
 *
 * Resolves public `ok.ru/video/...` and `ok.ru/videoembed/...` pages by reading
 * the embedded `data-options` payload and, when the page hands off a separate
 * metadata request, fetching that JSON too.
 */
(function (global) {
  'use strict';

  var registry = global.FreepiExtractors;
  var RT = global.FreepiRuntime;
  if (!registry || !RT) throw new Error('extractor-okru requires extractor-registry and shared-runtime');
  var shared = registry.shared;

  function decodeEntities(value) {
    return String(value || '')
      .replaceAll('&quot;', '"')
      .replaceAll('&amp;', '&')
      .replaceAll('&#x27;', "'")
      .replaceAll('&#39;', "'")
      .replaceAll('&lt;', '<')
      .replaceAll('&gt;', '>');
  }

  function tryParse(value) {
    if (value == null) return null;
    if (typeof value === 'object') return value;
    var text = String(value).trim();
    if (!text) return null;
    try { return JSON.parse(decodeEntities(text)); } catch (error) { /* keep trying */ }
    var start = text.indexOf('{');
    try {
      return JSON.parse(text.slice(start, text.lastIndexOf('}') + 1));
    } catch (error) {
      return null;
    }
  }

  function findDataOptions(html) {
    var pattern = /<(?:div|a)[^>]+data-options="([\s\S]*?)"[^>]*>/i;
    var match = pattern.exec(html || '');
    if (!match) return null;
    var decoded = decodeEntities(match[1]);
    // The payload is often wrapped in <div ...> content; JSON can appear after
    // an opening tag name. Normalize double-escaped braces.
    try {
      return JSON.parse(decoded);
    } catch (error) {
      var start = decoded.indexOf('{');
      var end = decoded.lastIndexOf('}');
      if (start >= 0 && end > start) {
        try {
          var candidate = decoded.slice(start, end + 1)
            .replaceAll('"{', '{').replaceAll('}"', '}')
            .replaceAll('\\"', '"');
          return JSON.parse(candidate);
        } catch (error2) {
          return null;
        }
      }
      return null;
    }
  }

  function metadataPayload(data) {
    if (!data) return null;
    var flashvars = data.flashvars;
    var metadataUrl = null;
    if (typeof flashvars === 'string' && /^https?:\/\//i.test(flashvars)) {
      metadataUrl = flashvars;
    } else if (flashvars && typeof flashvars === 'object') {
      if (typeof flashvars.metadataUrl === 'string') metadataUrl = flashvars.metadataUrl;
      if (flashvars.video && typeof flashvars.video === 'object') return flashvars.video;
      if (flashvars.videoUrl || flashvars.metadata) return tryParse(flashvars.metadata) || flashvars;
    }
    if (metadataUrl) {
      return RT.json(metadataUrl, { headers: { Referer: 'https://ok.ru/' }, label: 'OK.ru metadata' })
        .catch(function () { return null; });
    }
    if (typeof data.movie === 'object' && data.movie) return data.movie;
    if (data.videos && typeof data.videos === 'object') return data;
    return null;
  }

  function videosFrom(metadata, pageUrl) {
    var items = [];
    if (metadata && (metadata.videos || metadata.videoUrls)) {
      items = items.concat(shared.collectMedia(metadata, pageUrl));
    }
    if (!items.length && metadata && metadata.videos && typeof metadata.videos === 'object') {
      Object.keys(metadata.videos).forEach(function (quality) {
        var url = shared.mediaUrl(metadata.videos[quality], pageUrl);
        if (url) items.push({ url: url, kind: 'media', type: '', quality: quality });
      });
    }
    return items;
  }

  function extract(options) {
    var url = options.url || options.pageUrl || '';
    if (!url) return Promise.reject(Object.assign(new Error('OK.ru extractor requires a video URL.'), { code: 'MALFORMED' }));
    var pageUrl = registry.identify(url) ? url
      : url.replace(/^https?:\/\/(?:www\.|m\.)?ok\.ru\/video\//i, 'https://ok.ru/videoembed/');

    return RT.text(pageUrl, { headers: Object.assign({ Referer: 'https://ok.ru/', 'User-Agent': 'Mozilla/5.0' }, options.headers || {}), label: 'OK.ru page' })
      .then(function (html) {
        var data = findDataOptions(html) || {};
        var metadata;
        return Promise.resolve(metadataPayload(data)).then(function (meta) {
          metadata = meta;
          var items = videosFrom(metadata, pageUrl);
          if (!items.length) {
            items = shared.collectMedia(shared.fromHtml(html, pageUrl), pageUrl);
          }
          if (!items.length) {
            items = shared.collectMedia({ html: [] }, '').length ? [] : [];
            var regex = /["'](?:url|src|file|default)["']\s*:\s*["']([^"']+\.(?:mp4|m3u8|mpd)(?:\?[^"']*)?)["']/gi;
            var match;
            while ((match = regex.exec(html))) {
              var found = shared.mediaUrl(match[1], pageUrl);
              if (found) items.push({ url: found, kind: 'media', type: /m3u8/i.test(found) ? 'hls' : /mpd/i.test(found) ? 'dash' : 'mp4', quality: null });
            }
          }
          var media = [];
          var subtitles = [];
          items.forEach(function (item) {
            if (item.kind === 'subtitle') subtitles.push({ url: item.url, label: item.label, language: item.language });
            else media.push({ url: item.url, type: String(item.type || (/m3u8/i.test(item.url) ? 'hls' : /mpd/i.test(item.url) ? 'dash' : 'mp4')), mimeType: item.type || '', quality: item.quality || null, headers: { Referer: 'https://ok.ru/', 'User-Agent': 'Mozilla/5.0' } });
          });
          var seen = Object.create(null);
          media = media.filter(function (item) { if (seen[item.url]) return false; seen[item.url] = 1; return true; });
          return { media: media, subtitles: subtitles };
        });
      });
  }

  registry.register({
    id: 'okru',
    name: 'OK.ru',
    capabilities: ['mp4', 'hls', 'subtitles'],
    matches: function (context) {
      var opts = context.options || {};
      if (String(opts.extractor || opts.provider || '').toLowerCase() === 'okru') return true;
      return /(^|\.)ok\.ru$/i.test(context.host) || context.host === 'ok.ru';
    },
    extract: extract
  });
})(typeof globalThis !== 'undefined' ? globalThis : this);