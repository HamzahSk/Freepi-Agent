/**
 * Shared video-node handshake helper.
 *
 * LK21-style sites expose `https://host/iframe3/{p2p|hydrax}/{id}` video nodes
 * that answer `POST /api.php` with `{ host, id }` and return a JSON object with
 * an `embedUrl`. This module validates that handshake without replaying the
 * request; the observer (extension side) performs the actual fetch.
 */
(function (global) {
  'use strict';

  var registry = global.FreepiExtractors;
  if (!registry) throw new Error('extractor-video-node requires extractor-registry');

  var SUPPORTED = { p2p: 1, hydrax: 1 };
  var shared = registry.shared;

  function parseResponse(options) {
    var videoNodeUrl = String(options.videoNodeUrl || '');
    var apiUrl = String(options.apiUrl || '');
    var node = new URL(videoNodeUrl);
    var api = new URL(apiUrl, node);
    var match = /^\/iframe3\/(p2p|hydrax)\/([^/]+)\/?$/i.exec(node.pathname);
    if (!match) throw new TypeError('Unsupported video-node URL');
    if (api.origin !== node.origin || api.pathname !== '/api.php') {
      throw new TypeError('Unexpected video-node API URL');
    }
    var kind = String(options.expectedHost || match[1]).toLowerCase();
    if (!SUPPORTED[kind] || kind !== match[1].toLowerCase()) {
      throw new TypeError('Video-node extractor does not match its URL');
    }
    var payload = options.payload;
    var response = payload;
    if (typeof response === 'string') {
      try {
        response = JSON.parse(response);
      } catch (error) {
        throw new TypeError('Video-node API response is not valid JSON');
      }
    }
    if (!response || typeof response !== 'object' || Array.isArray(response)) {
      throw new TypeError('Video-node API response must be a JSON object');
    }
    var embedUrl = shared.mediaUrl(response.embedUrl, api.href);
    if (!embedUrl) throw new TypeError('Video-node API response has no valid embedUrl');
    return {
      extractor: kind,
      videoNodeUrl: node.href,
      apiUrl: api.href,
      host: kind,
      id: match[2],
      responsePayload: response,
      embedUrl: embedUrl
    };
  }

  function apiRequest(videoNodeUrl, kind) {
    var node = new URL(videoNodeUrl);
    var match = /^\/iframe3\/(p2p|hydrax)\/([^/]+)\/?$/i.exec(node.pathname);
    if (!match) throw new TypeError('Unsupported video-node URL');
    var requested = String(kind).toLowerCase();
    if (requested !== match[1].toLowerCase()) throw new TypeError('Video-node extractor does not match its URL');
    return {
      method: 'POST',
      url: new URL('/api.php', node.origin).href,
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ host: requested, id: match[2] }).toString()
    };
  }

  registry.videoNode = {
    parseResponse: parseResponse,
    apiRequest: apiRequest
  };
})(typeof globalThis !== 'undefined' ? globalThis : this);