/**
 * P2P video-node extractor (legacy LK21-style `iframe3/p2p/<id>` nodes).
 */
(function (global) {
  'use strict';

  var registry = global.FreepiExtractors;
  if (!registry) throw new Error('extractor-p2p requires extractor-registry');
  var shared = registry.shared;

  function extract(options) {
    var payload = options.payload;
    var pageUrl = options.pageUrl || options.baseUrl;
    var items = shared.collectMedia(payload, pageUrl);
    var media = [];
    var subtitles = [];
    items.forEach(function (item) {
      if (item.kind === 'subtitle') {
        subtitles.push({ url: item.url, label: item.label, language: item.language });
      } else if (item.kind === 'media') {
        var type = String(item.type || '').toLowerCase();
        media.push({ url: item.url, type: type === 'video/mp4' ? 'mp4' : type, mimeType: item.type || '', headers: options.headers || {} });
      }
    });
    return { media: media, subtitles: subtitles };
  }

  function parseResponse(options) {
    var result = registry.videoNode.parseResponse({
      payload: options.payload,
      videoNodeUrl: options.videoNodeUrl,
      apiUrl: options.apiUrl,
      expectedHost: 'p2p'
    });
    return result;
  }

  registry.register({
    id: 'p2p',
    name: 'P2P',
    capabilities: ['hls', 'dash', 'mp4', 'subtitles', 'video-node'],
    matches: function (context) {
      var opts = context.options || {};
      if (String(opts.extractor || opts.provider || '').toLowerCase() === 'p2p') return true;
      return /^\/iframe3\/p2p\//.test(new URL(context.url || '', 'https://movie-source.invalid/').pathname);
    },
    extract: extract
  });
  registry.p2p = { parseResponse: parseResponse };
})(typeof globalThis !== 'undefined' ? globalThis : this);