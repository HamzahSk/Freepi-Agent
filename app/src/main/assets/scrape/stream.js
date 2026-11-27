/** Document-start Chromium observer. Never click overlays, invoke SoTrym, decode datas or replace fetch. */
(function () {
  'use strict';
  if (window.__freepiStreamObserver) return;
  window.__freepiStreamObserver = true;
  var F = window.__freepi, start = Date.now(), selected = false, active = false, sent = false;
  var observed = [], responseTracks = [], frameDepth = 0, stateKey = '', lastSend = 0;
  var ancestorBlocked = false, ancestorPending = false;
  var failedScripts = 0, jsErrors = 0, ready = false, setupError = false, boundPlayer = null;
  var timer = null;
  var maxDepth = window.__freepiMaxDepth === undefined ? 6 : window.__freepiMaxDepth;
  var deadline = window.__freepiDeadline || (start + (window.__freepiTimeoutMs || 29000));
  var selector = 'iframe#main-player,iframe#video-iframe,iframe[allowfullscreen],iframe[allow*="fullscreen"],.player iframe,#player iframe,#vstr iframe';
  function state(stage, value) {
    if (!active || stateKey === stage + value) return;
    stateKey = stage + value;
    try { window.FreepiScrapeBridge.playerState(window.__freepiRequestId, stage, value, location.href); } catch (e) {}
  }
  function register() {
    try { window.FreepiScrapeBridge.playerPage(window.__freepiRequestId, location.href); } catch (e) {}
  }
  function activate(depth) { if (!active) { active = true; frameDepth = depth; register(); } }
  window.addEventListener('message', function (event) {
    if (event.source !== window.parent || window === window.top) return;
    var v = event.data;
    if (v && v.freepiPlayer === window.__freepiRequestId && v.depth >= 0 && v.depth <= maxDepth) {
      ancestorBlocked = !!v.blocked; ancestorPending = !!v.loading;
      var wasActive = active; activate(v.depth); if (!wasActive) tick();
    }
  });
  // No provider message text, payloads, signed URLs or credentials cross the diagnostics bridge.
  window.addEventListener('error', function (e) {
    if (e.target && e.target.tagName === 'SCRIPT') { failedScripts++; state('DEPENDENCY_LOADING', 'resource_failed'); }
    else if (e.target === window) {
      jsErrors++; state('PLAYER_INITIALIZATION', 'js_error');
      try { window.FreepiScrapeBridge.javascriptError(window.__freepiRequestId,e.error && e.error.name,e.lineno || 0,e.colno || 0,e.filename || location.href); } catch (ignore) {}
    }
  }, true);
  window.addEventListener('unhandledrejection', function () { jsErrors++; state('PLAYER_INITIALIZATION', 'js_error'); });
  function inspect(value, depth, trackContext, base) {
    if (!value || depth > 12 || typeof value !== 'object') return;
    if (Array.isArray(value)) { value.slice(0, 200).forEach(function (v) { inspect(v, depth + 1, trackContext, base); }); return; }
    var file = value.fileUrl || value.file || value.src || value.url;
    if (trackContext || /^(subtitles|captions)$/.test(value.kind || '')) {
      if (responseTracks.length < 200 && file) responseTracks.push({file:new URL(file,base).href,kind:'subtitles',label:value.label,language:value.language || value.srclang});
    } else if (observed.length < 200 && file && (value.fileUrl || value.type || F.isMedia(file)))
      observed.push({url:new URL(file,base).href,type:value.type || (value.fileUrl ? 'hls' : '')});
    Object.keys(value).slice(0, 200).forEach(function (key) {
      if (value[key] && typeof value[key] === 'object') inspect(value[key], depth + 1, /^(tracks|subtitles|captions)$/.test(key), base);
    });
  }
  // Observe completed XHR JSON without replaying a request. fetch stays native: the captured
  // provider checks fetch.toString(), and replacing it changes provider initialization behavior.
  var originalOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function () {
    this.addEventListener('load', function () {
      if (this.status < 200 || this.status >= 300) return;
      try {
        var value = null;
        if (this.responseType === 'json') value = this.response;
        else if ((!this.responseType || this.responseType === 'text') && this.responseText.length <= 524288 &&
            /json/i.test(this.getResponseHeader('Content-Type') || '')) value = JSON.parse(this.responseText);
        inspect(value, 0, false, this.responseURL || location.href);
      } catch (e) {}
    }, {once:true});
    return originalOpen.apply(this, arguments);
  };
  function gate() {
    return Array.from(document.querySelectorAll('#overlay, #uyeouyeo.visible, .custom-play-btn')).some(function (el) {
      var style = getComputedStyle(el), rect = el.getBoundingClientRect();
      return style.display !== 'none' && style.visibility !== 'hidden' && rect.width > 0 && rect.height > 0;
    });
  }
  function gather() {
    var media = [], subtitles = [], seen = Object.create(null);
    function add(raw, type) {
      if (!raw) return;
      var url = F.abs(raw);
      if (!url || !/^https?:/.test(url) || seen[url]) return;
      var path = new URL(url).pathname;
      if (!F.isMedia(path) && !/^(video\/|application\/(?:x-mpegurl|vnd.apple.mpegurl|dash\+xml)|hls$|dash$|mp4$|webm$)/i.test(type || '')) return;
      if (/\.(?:ts|m4s|aac|m4a)$/i.test(path)) return;
      seen[url] = true; media.push({url:url,pageUrl:location.href,type:type || ''});
    }
    function track(t) {
      if (!t || !/^(subtitles|captions)$/.test(t.kind || 'subtitles')) return;
      var url = F.abs(t.file || t.src || t.url);
      if (url && /^https?:/.test(url)) subtitles.push({url:url,label:t.label || 'Subtitle',language:t.srclang || t.language || null});
    }
    observed.forEach(function (s) { add(s.url, s.type); });
    responseTracks.forEach(track);
    document.querySelectorAll('video,video source').forEach(function (v) { add(v.currentSrc || v.getAttribute('src'),v.getAttribute('type')); });
    document.querySelectorAll('track[src]').forEach(function (t) { track({src:t.getAttribute('src'),kind:t.kind,label:t.label,srclang:t.srclang}); });
    // Accessible network manifest references only; segments and unrelated frames are excluded.
    performance.getEntriesByType('resource').slice(-200).forEach(function (r) { add(r.name); });
    try {
      if (typeof window.jwplayer === 'function') {
        var player = window.jwplayer();
        if (player && player !== boundPlayer && typeof player.on === 'function') {
          boundPlayer = player;
          player.on('ready',function () { ready = true; });
          player.on('setupError',function () { setupError = true; });
          player.on('error',function () { setupError = true; });
        }
        if (player.getState && player.getState()) ready = true;
        (player.getPlaylist ? player.getPlaylist() : []).forEach(function (item) {
          if (item.drm) return;
          add(item.file,item.type);
          (item.sources || []).forEach(function (s) { if (!s.drm) add(s.file || s.src,s.type); });
          (item.tracks || []).forEach(track);
        });
      }
    } catch (e) {}
    return {pageUrl:location.href,media:media,subtitles:subtitles,depth:frameDepth};
  }
  function failure() {
    var stage = 'PLAYER_METADATA', reason = 'no authorized playable source exposed through public player APIs or accessible network metadata';
    if (ancestorBlocked || gate()) { stage = 'PLAYER_INITIALIZATION'; reason = 'provider requires user interaction or advertising flow; native extraction cannot continue automatically'; }
    else if (failedScripts) { stage = 'DEPENDENCY_LOADING'; reason = 'script requests failed (' + failedScripts + '); no playable metadata available'; }
    else if (ancestorPending || document.readyState !== 'complete') { stage = 'DEPENDENCY_LOADING'; reason = 'page dependencies did not finish loading'; }
    else if (setupError) { stage = 'PLAYER_INITIALIZATION'; reason = 'JWPlayer reported a setup or playback error'; }
    else if (jsErrors) { stage = 'PLAYER_INITIALIZATION'; reason = 'JavaScript execution failed (' + jsErrors + ' errors)'; }
    else if (!ready) { stage = 'PLAYER_INITIALIZATION'; reason = 'player did not expose a ready state or playable metadata'; }
    return F.errorPayload('TIMEOUT', stage + ': ' + reason);
  }
  function tick() {
    clearTimeout(timer); timer = null;
    if (sent || (!active && window !== window.top)) return;
    if (window === window.top && !selected) {
      var frame = document.querySelector('iframe#main-player');
      if (frame) {
        selected = true; frameDepth = -1;
        // Assign once only. Do not repeatedly reload the page while its dependencies initialize.
        if (frame.src !== window.__freepiRequestUrl) frame.src = window.__freepiRequestUrl;
      } else if (location.href === window.__freepiRequestUrl) activate(0);
    }
    if ((active || selected) && frameDepth < maxDepth) {
      var frames = window === window.top && selected ? document.querySelectorAll('iframe#main-player') : document.querySelectorAll(selector);
      frames.forEach(function (f) {
        try { f.contentWindow.postMessage({freepiPlayer:window.__freepiRequestId,depth:frameDepth + 1,blocked:ancestorBlocked || gate(),loading:ancestorPending || document.readyState !== 'complete'},'*'); } catch (e) {}
      });
    }
    if ((active || window === window.top) && F.isChallenge()) {
      sent = true; F.send(F.errorPayload('ACCESS_BLOCKED','PLAYER_INITIALIZATION: browser access challenge')); return;
    }
    if (active) {
      try {
        var data = gather();
        if (ancestorBlocked || gate()) state('PLAYER_INITIALIZATION','interaction_required');
        else if (failedScripts) state('DEPENDENCY_LOADING','dependencies_failed');
        else if (ancestorPending || document.readyState !== 'complete') state('DEPENDENCY_LOADING','dependencies_pending');
        else if (setupError) state('PLAYER_INITIALIZATION','setup_error');
        else if (jsErrors && !ready) state('PLAYER_INITIALIZATION','js_error');
        else state(ready ? 'PLAYER_METADATA' : 'PLAYER_INITIALIZATION',ready ? (data.media.length ? 'metadata_pending' : 'ready') : 'waiting_for_player');
        if (document.readyState === 'complete' && !ancestorPending && !ancestorBlocked && !gate() && !setupError && data.media.length && Date.now() - lastSend >= 750) {
          lastSend = Date.now();
          // Kotlin validates while this frame remains alive; rejection does not end observation.
          F.send({schema:'freepi.stream.v1',ok:true,source:F.sourceInfo(),data:data});
        }
      } catch (e) { state('PLAYER_INITIALIZATION','js_error'); }
    }
    if (Date.now() >= deadline) {
      // An activated child reports its own dependency/initialization failure before the root fallback.
      if ((active && (gate() || ancestorBlocked || !document.querySelector(selector))) || (window === window.top && !selected)) { sent = true; F.send(failure()); }
      return;
    }
    timer = setTimeout(tick,250);
  }
  document.addEventListener('readystatechange',function () { state('PLAYER_HTML',document.readyState); });
  timer = setTimeout(tick,0);
})();
