/**
 * Shared HLS/DASH playlist expansion. It turns master playlists into concrete
 * quality-labelled media entries so every source produces the same stream shape.
 */
(function (global) {
  'use strict';

  var registry = global.FreepiExtractors;
  var RT = global.FreepiRuntime;
  if (!registry || !RT) throw new Error('extractor-playlist requires extractor-registry and shared-runtime');

  var shared = registry.shared;

  function qualityFromHeight(height, bandwidth) {
    var value = Number(height);
    if (value > 0) {
      if (value >= 2000) return '2160p';
      if (value >= 1400) return '1440p';
      if (value >= 1000) return '1080p';
      if (value >= 700) return '720p';
      if (value >= 400) return '480p';
      if (value >= 300) return '360p';
      return value + 'p';
    }
    return bandwidth ? String(Math.round(Number(bandwidth) / 1000)) + 'kbps' : null;
  }

  function parseAttributes(line) {
    var out = {};
    var body = String(line || '').replace(/^#EXT-X-[A-Z-]+:/, '');
    var pattern = /([A-Z0-9-]+)=("[^"]*"|[^,]*)/gi;
    var match;
    while ((match = pattern.exec(body))) {
      var key = match[1].toUpperCase();
      var value = match[2] || '';
      if (value.charAt(0) === '"') value = value.slice(1, -1);
      out[key] = value;
    }
    return out;
  }

  function parseHls(textValue, playlistUrl, headers) {
    var lines = String(textValue || '').split(/\r?\n/).map(function (line) { return line.trim(); });
    var media = [];
    var subtitles = [];
    var base = playlistUrl;
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      if (!line) continue;
      if (line.indexOf('#EXT-X-MEDIA:') === 0) {
        var mediaAttrs = parseAttributes(line);
        if (/SUBTITLES|CAPTIONS/i.test(mediaAttrs.TYPE || '') && mediaAttrs.URI) {
          var track = shared.mediaUrl(mediaAttrs.URI, base);
          if (track) {
            subtitles.push({
              url: track,
              label: mediaAttrs.NAME || mediaAttrs.LANGUAGE || 'Subtitle',
              language: mediaAttrs.LANGUAGE || null
            });
          }
        }
        continue;
      }
      if (line.indexOf('#EXT-X-STREAM-INF:') !== 0) continue;
      var attrs = parseAttributes(line);
      var next = i + 1;
      while (next < lines.length && (!lines[next] || lines[next].charAt(0) === '#')) next++;
      var variant = shared.mediaUrl(lines[next], base);
      if (!variant) continue;
      var resolution = /^(\d+)x(\d+)$/.exec(attrs.RESOLUTION || '');
      var height = resolution ? Number(resolution[2]) : 0;
      var quality = qualityFromHeight(height, attrs.BANDWIDTH);
      media.push({
        url: variant,
        type: 'hls',
        quality: quality,
        label: [quality, attrs.BANDWIDTH ? Math.round(Number(attrs.BANDWIDTH) / 1000) + 'kbps' : '']
          .filter(Boolean).join(' · '),
        headers: headers || {}
      });
      i = next;
    }
    return { media: media, subtitles: subtitles };
  }

  function parseDash(textValue, mpdUrl, headers) {
    var text = String(textValue || '').replaceAll('\\/', '/');
    var media = [];
    var representationPattern = /<Representation\b([^>]*)>([\s\S]*?)<\/Representation>/gi;
    var match;
    while ((match = representationPattern.exec(text))) {
      var attrs = shared.attrs('<x ' + match[1] + '>');
      var body = match[2];
      var baseMatch = /<BaseURL[^>]*>([\s\S]*?)<\/BaseURL>/i.exec(body);
      var baseUrl = baseMatch ? shared.decodeHtml(baseMatch[1].trim()) : '';
      var url = baseUrl ? shared.mediaUrl(baseUrl, mpdUrl) : mpdUrl;
      if (!url) continue;
      var height = Number(attrs.height) || 0;
      var quality = qualityFromHeight(height, attrs.bandwidth);
      var mime = String(attrs.mimetype || '');
      if (mime && !/video\//i.test(mime)) continue;
      media.push({
        url: url,
        type: 'dash',
        quality: quality,
        label: [quality, attrs.codecs || ''].filter(Boolean).join(' · '),
        headers: headers || {}
      });
    }
    return { media: media, subtitles: [] };
  }

  function expand(url, options) {
    var opts = options || {};
    var target = rtUrl(url, opts.baseUrl);
    if (!target) return Promise.resolve({ media: [], subtitles: [] });
    var extension = shared.extensionOf(target);
    var kind = String(opts.type || '').toLowerCase();
    var hls = kind === 'hls' || extension === 'm3u8';
    var dash = kind === 'dash' || extension === 'mpd';
    if (!hls && !dash) return Promise.resolve({ media: [], subtitles: [] });
    return RT.text(target, { headers: opts.headers || {}, label: 'playlist' }).then(function (body) {
      return hls ? parseHls(body, target, opts.headers) : parseDash(body, target, opts.headers);
    }).catch(function () {
      return { media: [], subtitles: [] };
    });
  }

  function rtUrl(value, base) {
    return shared.mediaUrl(value, base);
  }

  registry.playlist = {
    expand: expand,
    parseHls: parseHls,
    parseDash: parseDash,
    qualityFromHeight: qualityFromHeight
  };
})(typeof globalThis !== 'undefined' ? globalThis : this);
