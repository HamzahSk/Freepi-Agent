/** Passive document-start inspection of the selected player frame tree. No synthetic gestures. */
(function () {
  'use strict';
  if (window.__freepiStreamObserver) return;
  window.__freepiStreamObserver = true;
  var F = window.__freepi, start = Date.now(), selected = false, active = false, sent = false;
  var observed = [], responseTracks = [], frameDepth = 0;
  var maxDepth = window.__freepiMaxDepth === undefined ? 6 : window.__freepiMaxDepth;
  var selector = 'iframe#main-player,iframe#video-iframe,iframe[allowfullscreen],iframe[allow*="fullscreen"],.player iframe,#player iframe,#vstr iframe';
  function log(stage, text) {
    try { window.FreepiScrapeBridge.logStage(stage, text); } catch (e) {}
  }
  // Only a selected player's parent activates its known player iframe children.
  window.addEventListener('message', function (event) {
    if (event.source !== window.parent || window === window.top) return;
    var value = event.data;
    if (value && value.freepiPlayer === window.__freepiRequestId && value.depth <= maxDepth) {
      active = true; frameDepth = value.depth;
      try { window.FreepiScrapeBridge.playerPage(window.__freepiRequestId, location.href); } catch (e) {}
    }
  });
  function inspect(value, depth, trackContext) {
    if (!value || depth > 12 || typeof value !== 'object') return;
    if (Array.isArray(value)) { value.slice(0, 200).forEach(function (v) { inspect(v, depth + 1, trackContext); }); return; }
    var file = value.fileUrl || value.file || value.src || value.url;
    if (trackContext || /^(subtitles|captions)$/.test(value.kind || '')) { if (responseTracks.length < 200) responseTracks.push(value); }
    else if (observed.length < 200 && file && (value.fileUrl || value.type || F.isMedia(file)))
      observed.push({url: file, type: value.type || (value.fileUrl ? 'hls' : '')});
    Object.keys(value).slice(0, 200).forEach(function (key) {
      if (value[key] && typeof value[key] === 'object') inspect(value[key], depth + 1, /^(tracks|subtitles|captions)$/.test(key));
    });
  }
  function inspectJson(raw) {
    try {
      if (typeof raw === 'string') { if (raw.length > 524288) return; raw = JSON.parse(raw); }
      inspect(raw, 0);
    } catch (e) {}
  }
  // Read completed responses without changing requests, headers, data, timers or return values.
  var originalOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function () {
    this.addEventListener('load', function () {
      if (this.status < 200 || this.status >= 300) return;
      try {
        if (this.responseType === 'json') inspectJson(this.response);
        else if (!this.responseType || this.responseType === 'text') {
          var type = this.getResponseHeader('Content-Type') || '';
          if (/json/i.test(type) || /^\s*[\[{]/.test(this.responseText)) inspectJson(this.responseText);
        }
      } catch (e) {}
    }, {once: true});
    return originalOpen.apply(this, arguments);
  };
  if (window.fetch) {
    var originalFetch = window.fetch;
    window.fetch = function () {
      var result = originalFetch.apply(this, arguments);
      result.then(function (response) {
        if (!response.ok || !/json/i.test(response.headers.get('Content-Type') || '')) return;
        if (Number(response.headers.get('Content-Length')) > 524288) return;
        var copy = response.clone();
        // Limit the cloned branch; never buffer a video/manifest/segment or change the original body.
        if (!copy.body || !copy.body.getReader) return;
        var reader = copy.body.getReader(), size = 0, chunks = [], decoder = new TextDecoder();
        function read() { return reader.read().then(function (part) {
          if (part.done) { chunks.push(decoder.decode()); inspectJson(chunks.join('')); return; }
          size += part.value.byteLength;
          if (size > 524288) { reader.cancel().catch(function () {}); return; }
          chunks.push(decoder.decode(part.value, {stream: true}));
          return read();
        }); }
        read().catch(function () {});
      }).catch(function () {});
      return result;
    };
  }
  function gather() {
    var media = [], subtitles = [], seen = Object.create(null), scripts = [], iframes = [];
    function add(raw, type) {
      if (!raw) return;
      var url = F.abs(raw);
      if (!url || !/^https?:/.test(url) || seen[url]) return;
      if (!F.isMedia(new URL(url).pathname) && !/^(video\/|application\/(?:x-mpegurl|vnd.apple.mpegurl|dash\+xml)|hls$|dash$|mp4$|webm$)/i.test(type || '')) return;
      if (/\.(?:ts|m4s|aac|m4a)$/i.test(new URL(url).pathname)) return;
      seen[url] = true;
      media.push({url: url, pageUrl: location.href, type: type || ''});
    }
    function track(t) {
      if (!t || !/^(subtitles|captions)$/.test(t.kind || 'subtitles')) return;
      var url = F.abs(t.file || t.src || t.url);
      if (url && /^https?:/.test(url)) subtitles.push({url: url, label: t.label || 'Subtitle', language: t.srclang || t.language || null});
    }
    observed.slice(0, 200).forEach(function (s) { add(s.url, s.type); });
    responseTracks.slice(0, 200).forEach(track);
    document.querySelectorAll('video,video source').forEach(function (v) { add(v.currentSrc || v.getAttribute('src'), v.getAttribute('type')); });
    document.querySelectorAll('track[src]').forEach(function (t) { track({src:t.getAttribute('src'),kind:t.kind,label:t.label,srclang:t.srclang}); });
    try {
      if (typeof window.jwplayer === 'function') {
        var player = window.jwplayer();
        (player.getPlaylist ? player.getPlaylist() : []).forEach(function (item) {
          add(item.file, item.type);
          (item.sources || []).forEach(function (s) { add(s.file || s.src, s.type); });
          (item.tracks || []).forEach(track);
        });
      }
    } catch (e) {}
    document.querySelectorAll('script:not([src])').forEach(function (s) {
      var text = s.textContent || '';
      if (/sources|file\s*:|atob\(|eval\(function/.test(text)) scripts.push(text.slice(0, 200000));
    });
    document.querySelectorAll(selector).forEach(function (f) { if (f.getAttribute('src')) iframes.push(F.abs(f.getAttribute('src'))); });
    return {pageUrl:location.href,title:document.title,media:media,subtitles:subtitles,scripts:scripts.slice(0,20),iframes:iframes,depth:frameDepth};
  }
  function tick() {
    if (sent) return;
    if (window === window.top && !selected) {
      var frame = document.querySelector('iframe#main-player');
      if (frame) {
        selected = true; frameDepth = -1;
        if (frame.src !== window.__freepiRequestUrl) frame.src = window.__freepiRequestUrl;
        start = Date.now();
        log('INITIAL_PAGE', 'selected requested source in real parent iframe');
      } else if (location.href === window.__freepiRequestUrl) { active = true; }
    }
    if ((active || selected) && frameDepth < maxDepth) {
      var frames = window === window.top && selected ? document.querySelectorAll('iframe#main-player') : document.querySelectorAll(selector);
      frames.forEach(function (f) {
        try { f.contentWindow.postMessage({freepiPlayer:window.__freepiRequestId,depth:frameDepth + 1}, '*'); } catch (e) {}
      });
    }
    if (active && F.isChallenge()) {
      sent = true; F.send(F.errorPayload('ACCESS_BLOCKED', 'PLAYER_CONFIG: browser access challenge')); return;
    }
    var data;
    try { data = active ? gather() : {pageUrl:location.href,media:[],subtitles:[],scripts:[],iframes:[]}; }
    catch (e) { setTimeout(tick,150); return; }
    if (active && data.media.length) {
      log('MEDIA_METADATA', 'runtime candidates=' + data.media.length + ' depth=' + frameDepth);
      sent = true; F.send({schema:'freepi.stream.v1',ok:true,source:F.sourceInfo(),data:data}); return;
    }
    if (window === window.top && F.isChallenge()) {
      sent = true; F.send(F.errorPayload('ACCESS_BLOCKED', 'INITIAL_PAGE: browser access challenge')); return;
    }
    if (Date.now() - start > 20000) {
      if (window === window.top) { sent = true; F.send({schema:'freepi.stream.v1',ok:true,source:F.sourceInfo(),data:data}); }
      return;
    }
    setTimeout(tick,150);
  }
  setTimeout(tick,0);
})();
