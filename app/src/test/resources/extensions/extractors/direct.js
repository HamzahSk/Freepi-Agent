/**
 * Direct-media extractor: accepts an already playable URL that needs no
 * provider handshake. Useful for sources that embed `.m3u8/.mpd/.mp4` URLs
 * straight in their catalog or detail payloads.
 */
(function (global) {
  'use strict';

  var registry = global.FreepiExtractors;
  if (!registry) throw new Error('extractor-direct requires extractor-registry');
  var shared = registry.shared;

  function extract(options) {
    var url = options.url || options.embedUrl || '';
    var pageUrl = options.pageUrl || options.baseUrl;
    var target = shared.mediaUrl(url, pageUrl);
    if (!target || !shared.isPlayable(target)) {
      return Promise.reject(Object.assign(new Error('Direct extractor requires a playable media URL.'), { code: 'MALFORMED' }));
    }
    var extension = shared.extensionOf(target);
    var type = { m3u8: 'hls', mpd: 'dash', webm: 'webm' }[extension] || 'mp4';
    return Promise.resolve({
      media: [{ url: target, type: type, mimeType: registry.mimeFor(target, type), quality: options.quality || null, label: options.label || null, headers: options.headers || {} }],
      subtitles: []
    });
  }

  registry.register({
    id: 'direct',
    name: 'Direct',
    capabilities: ['hls', 'dash', 'mp4', 'webm'],
    matches: function (context) {
      var opts = context.options || {};
      if (String(opts.extractor || opts.provider || '').toLowerCase() === 'direct') return true;
      return !!context.url && shared.isPlayable(context.url);
    },
    extract: extract
  });
})(typeof globalThis !== 'undefined' ? globalThis : this);