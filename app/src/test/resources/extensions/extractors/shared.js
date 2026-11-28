/**
 * Shared extractor helpers: URL safety, recursive playlist/JSON media discovery,
 * subtitle discovery, and HTML attribute parsing. Loaded after the registry.
 */
(function (global) {
  'use strict';

  var registry = global.FreepiExtractors;
  if (!registry) throw new Error('extractor-shared requires extractor-registry');

  var PLAYABLE = { m3u8: 1, mpd: 1, mp4: 1, m4v: 1, webm: 1, mov: 1 };

  function mediaUrl(value, baseUrl) {
    if (typeof value !== 'string' || !value.trim()) return null;
    var raw = value.replaceAll('\\/', '/').trim();
    if (/^data:|^blob:|^javascript:/i.test(raw)) return null;
    try {
      var url = new URL(raw, baseUrl || 'https://movie-source.invalid/');
      if (!/^https?:$/.test(url.protocol) || url.username || url.password) return null;
      return url.href;
    } catch (error) {
      return null;
    }
  }

  function extensionOf(url) {
    try {
      var path = new URL(url).pathname;
      var name = path.split('/').pop().toLowerCase();
      var dot = name.lastIndexOf('.');
      return dot >= 0 ? name.slice(dot + 1) : '';
    } catch (error) {
      return '';
    }
  }

  function isPlayable(url) {
    return !!PLAYABLE[extensionOf(url)];
  }

  function isSegment(url) {
    return /\.(?:ts|m4s|aac|m4a)(?:\?|#|$)/i.test(url);
  }

  function collectMedia(value, baseUrl, options) {
    var opts = options || {};
    var media = new Map();
    var visit = function (node, depth, track) {
      if (depth > 14 || node == null) return;
      if (Array.isArray(node)) {
        node.forEach(function (item) { visit(item, depth + 1, track); });
        return;
      }
      if (typeof node !== 'object') return;

      var raw = node.fileUrl || node.file || node.src || node.url;
      if (node.drm && raw) return;
      var url = mediaUrl(raw, baseUrl);
      var kind = String(node.kind || '').toLowerCase();

      if (url && (track || kind === 'captions' || kind === 'subtitles')) {
        if (!media.has(url)) {
          media.set(url, {
            url: url,
            kind: 'subtitle',
            label: node.label || node.name || 'Subtitle',
            language: node.srclang || node.language || null
          });
        }
      } else if (url && !isSegment(url) && (isPlayable(url) || node.type)) {
        var type = String(node.type || '').toLowerCase();
        if (!media.has(url)) {
          media.set(url, {
            url: url,
            kind: 'media',
            type: type,
            quality: node.quality || node.label || null
          });
        }
      }

      Object.keys(node).slice(0, 200).forEach(function (key) {
        if (/^drm$/i.test(key)) return;
        var child = node[key];
        if (!child || typeof child !== 'object') return;
        visit(child, depth + 1, track || /^(tracks?|captions?|subtitles?)$/i.test(key));
      });
    };
    visit(value, 0, !!(opts.track));
    return Array.from(media.values());
  }

  function decodeHtml(value) {
    return String(value == null ? '' : value)
      .replaceAll('&amp;', '&')
      .replaceAll('&quot;', '"')
      .replaceAll('&#39;', "'")
      .replaceAll('&lt;', '<')
      .replaceAll('&gt;', '>')
      .replaceAll('\\/', '/');
  }

  function attrs(tag) {
    var out = {};
    var pattern = /([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))/g;
    var match;
    while ((match = pattern.exec(String(tag)))) {
      out[match[1].toLowerCase()] = decodeHtml(match[2] != null ? match[2] : match[3] != null ? match[3] : match[4] || '');
    }
    return out;
  }

  function fromHtml(html, pageUrl) {
    var text = decodeHtml(html);
    var output = new Map();
    var addMedia = function (raw, type) {
      var url = mediaUrl(raw, pageUrl);
      if (!url || isSegment(url)) return;
      if (!isPlayable(url) && !type) return;
      output.set(url, { url: url, kind: 'media', type: String(type || ''), quality: null });
    };
    var addTrack = function (raw, label, language) {
      var url = mediaUrl(raw, pageUrl);
      if (!url) return;
      output.set(url, { url: url, kind: 'subtitle', label: label || 'Subtitle', language: language || null });
    };

    var tag;
    var tags = /<(?:video|source)\b[^>]*>/gi;
    while ((tag = tags.exec(text))) {
      var value = attrs(tag[0]);
      addMedia(value.src, value.type || '');
    }
    var trackTags = /<track\b[^>]*>/gi;
    while ((tag = trackTags.exec(text))) {
      var track = attrs(tag[0]);
      if (!track.kind || /^(?:subtitles|captions)$/i.test(track.kind)) {
        addTrack(track.src, track.label, track.srclang || track['data-language']);
      }
    }

    var filePattern = /["'](?:file|fileUrl|src|url)["']\s*:\s*["']([^"']+)["']/gi;
    while ((tag = filePattern.exec(text))) addMedia(tag[1]);

    var subtitlePattern = /["']kind["']\s*:\s*["'](?:subtitles|captions)["'][^{}]{0,2000}?["'](?:file|src|url)["']\s*:\s*["']([^"']+)["']/gi;
    while ((tag = subtitlePattern.exec(text))) addTrack(tag[1], 'Subtitle', null);

    return Array.from(output.values());
  }

  function absolute(value, base) {
    return mediaUrl(value, base);
  }

  function mergeHeaders() {
    var out = {};
    for (var i = 0; i < arguments.length; i++) {
      var value = arguments[i];
      if (!value) continue;
      Object.keys(value).forEach(function (key) { out[key] = value[key]; });
    }
    return out;
  }

  registry.shared = {
    mediaUrl: mediaUrl,
    isPlayable: isPlayable,
    isSegment: isSegment,
    collectMedia: collectMedia,
    decodeHtml: decodeHtml,
    attrs: attrs,
    fromHtml: fromHtml,
    absolute: absolute,
    mergeHeaders: mergeHeaders,
    extensionOf: extensionOf
  };
})(typeof globalThis !== 'undefined' ? globalThis : this);
