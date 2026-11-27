/** Document-start observer in each real frame. No ad clicks or API/timer patches. */
(function () {
  'use strict';
  if (window.__freepiStreamObserver) return;
  window.__freepiStreamObserver = true;
  var F = window.__freepi, start = Date.now(), selected = false;
  function log(stage, text) {
    try { window.FreepiScrapeBridge.logStage(stage, text); } catch (e) {}
  }
  function gather() {
    var media = [], subtitles = [], seen = Object.create(null), scripts = [];
    function add(raw, type) {
      if (!raw) return;
      var url = F.abs(raw);
      if (!url || seen[url]) return;
      // MIME-labelled extensionless sources are candidates too; Kotlin validates their response.
      if (!F.isMedia(new URL(url).pathname) && !/^(video\/|application\/(?:x-mpegurl|vnd.apple.mpegurl|dash\+xml)|hls$|dash$|mp4$|webm$)/i.test(type || '')) return;
      if (/\.(?:ts|m4s|aac)(?:$|\?)/i.test(new URL(url).pathname)) return;
      seen[url] = true;
      media.push({url: url, pageUrl: location.href, type: type || ''});
    }
    function track(t) {
      if (!t || !/^(subtitles|captions)$/.test(t.kind || 'subtitles')) return;
      var url = F.abs(t.file || t.src || t.url);
      if (url) subtitles.push({url: url, label: t.label || 'Subtitle', language: t.srclang || t.language || null});
    }
    document.querySelectorAll('video, video source').forEach(function (v) {
      add(v.currentSrc || v.getAttribute('src'), v.getAttribute('type'));
    });
    document.querySelectorAll('track[src]').forEach(function (t) {
      track({src: t.getAttribute('src'), kind: t.kind, label: t.label, srclang: t.srclang});
    });
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
    // Keep packed and encoded DATA available for Rhino; never evaluate full provider scripts there.
    document.querySelectorAll('script:not([src])').forEach(function (s) {
      var text = s.textContent || '';
      if (/sources|file\s*:|atob\(|eval\(function/.test(text)) scripts.push(text.slice(0, 200000));
    });
    return {pageUrl: location.href, title: document.title, media: media,
      subtitles: subtitles, scripts: scripts.slice(0, 20), iframes: []};
  }
  log('PLAYER_INITIALIZATION', 'observing ' + location.href + '; no synthetic interaction');
  function tick() {
    // Select early, before the parent's load event can be delayed by the default provider.
    if (window === window.top && !selected) {
      var frame = document.querySelector('iframe#main-player');
      if (frame) {
        selected = true;
        if (frame.src !== window.__freepiRequestUrl) frame.src = window.__freepiRequestUrl;
        start = Date.now();
        log('INITIAL_PAGE', 'selected requested source in real parent iframe');
      }
    }
    var provider = window.__freepiProviderUrl || window.__freepiRequestUrl;
    var active = false;
    try { active = location.origin === new URL(provider).origin; } catch (e) {}
    if (!active && window !== window.top) return;
    if (F.isChallenge()) {
      F.send(F.errorPayload('ACCESS_BLOCKED', 'PLAYER_INITIALIZATION: browser access challenge'));
      return;
    }
    var data;
    try { data = gather(); } catch (e) { setTimeout(tick, 150); return; }
    if (active && data.media.length) {
      log('STREAM_METADATA', 'runtime candidates=' + data.media.length + '; validation pending');
      F.send({schema:'freepi.stream.v1', ok:true, source:F.sourceInfo(), data:data});
      return;
    }
    // Only top frame reports an empty observation; a wrapper must not beat its async provider.
    if (Date.now() - start > 20000) {
      if (window === window.top) {
        log('STREAM_METADATA', 'no DOM media; Kotlin will inspect observed network candidates');
        data.media = []; data.scripts = [];
        F.send({schema:'freepi.stream.v1', ok:true, source:F.sourceInfo(), data:data});
      }
      return;
    }
    setTimeout(tick, 150);
  }
  setTimeout(tick, 0);
})();
