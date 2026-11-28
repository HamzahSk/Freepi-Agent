/**
 * Hydrax video-node extractor. Accepts a video-node API payload (embedUrl
 * handshake) or a raw player HTML document containing direct media/subtitles.
 */
(function (global) {
  'use strict';

  var registry = global.FreepiExtractors;
  if (!registry) throw new Error('extractor-hydrax requires extractor-registry');
  var shared = registry.shared;

  function extract(options) {
    var url = options.url || options.pageUrl || options.baseUrl || 'https://embed.example/';
    var items;
    if (typeof options.payload === 'string' && /<[a-z!]/i.test(options.payload)) {
      items = shared.fromHtml(options.payload, url);
    } else {
      items = shared.collectMedia(options.payload, url);
    }
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
    return registry.videoNode.parseResponse({
      payload: options.payload,
      videoNodeUrl: options.videoNodeUrl,
      apiUrl: options.apiUrl,
      expectedHost: 'hydrax'
    });
  }

  registry.register({
    id: 'hydrax',
    name: 'Hydrax',
    capabilities: ['hls', 'dash', 'mp4', 'subtitles', 'video-node', 'html'],
    matches: function (context) {
      var opts = context.options || {};
      if (String(opts.extractor || opts.provider || '').toLowerCase() === 'hydrax') return true;
      return /^\/iframe3\/hydrax\//.test(new URL(context.url || '', 'https://movie-source.invalid/').pathname);
    },
    extract: extract
  });
  registry.hydrax = { parseResponse: parseResponse };
})(typeof globalThis !== 'undefined' ? globalThis : this);