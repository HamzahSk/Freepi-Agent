/**
 * Provider / embed page extraction. Produces freepi.stream.v1.
 *
 * Runs inside the hidden WebView after the provider's player page has fully
 * rendered, so JS-created <video> tags, dynamically inserted iframes and
 * script-set source objects are all visible to the DOM scan.
 */
(function () {
  'use strict';
  var F = window.__freepi;

  function gather() {
    var media = [];
    var iframes = [];
    var seenMedia = Object.create(null);
    var seenFrames = Object.create(null);

    function pushMedia(u) {
      var list = Array.isArray(u) ? u : [u];
      list.forEach(function (raw) {
        if (!raw) return;
        var v = F.abs(String(raw).trim());
        if (!v || !F.isMedia(v) || seenMedia[v]) return;
        seenMedia[v] = 1;
        media.push({
          url: v,
          kind: /\.m3u8/i.test(v) ? 'hls' : /\.mpd/i.test(v) ? 'dash' : 'progressive'
        });
      });
    }

    function pushFrame(raw) {
      var v = F.abs(String(raw || '').trim());
      if (!v || seenFrames[v]) return;
      if (/youtube|youtu\.be|googlevideo|recaptcha|doubleclick|googleads/i.test(v)) return;
      seenFrames[v] = 1;
      iframes.push(v);
    }

    try {
      document.querySelectorAll('video[src], video source[src], source[src], #main-player[src]')
        .forEach(function (el) {
          var src = el.getAttribute('src') || '';
          if (src && !/^blob:/.test(src)) pushMedia(src);
        });
    } catch (e) {}

    try {
      document.querySelectorAll('video').forEach(function (video) {
        var src = video.currentSrc || video.src || '';
        if (src && !/^blob:/.test(src) && F.isMedia(src)) pushMedia(src);
      });
    } catch (e) {}

    try {
      document.querySelectorAll('iframe[src]').forEach(function (iframe) {
        pushFrame(iframe.getAttribute('src'));
      });
    } catch (e) {}

    // Raw tags in scripts that reference media/player config.
    var scripts = [];
    try {
      document.querySelectorAll('script:not([src])').forEach(function (s) {
        var t = s.textContent || '';
        if (!t) return;
        if (/m3u8|\.mp4|file\s*:|sources\s*:|atob\s*\(|eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k/i.test(t)) {
          scripts.push(t.slice(0, 200000));
        }
      });
    } catch (e) {}

    // Runtime properties set by player libraries.
    try {
      window.__freepiPlayer = window.__freepiPlayer || {};
    } catch (e) {}

    // Straight URL scan of the whole rendered HTML (escaped slashes included).
    var html = '';
    try { html = (document.documentElement.outerHTML || '').replace(/\\\//g, '/'); } catch (e) {}
    var mediaRe = /https?:\/\/[^\s"'<>()\\]+?\.(?:m3u8|mp4|m4v|webm|mpd|mov)(?:\?[^\s"'<>()\\]*)?/gi;
    var m;
    while ((m = mediaRe.exec(html)) && media.length < 30) {
      pushMedia(m[0]);
    }

    // file:/src:/source: JS fields (also inside scripts above).
    try {
      scripts.forEach(function (scriptText) {
        var field = /(?:file|src|url|source)\s*[:=]\s*["']([^"']*?\.(?:m3u8|mp4|m4v|webm|mpd|mov)(?:\?[^"']*)?)["']/gi;
        var fm;
        while ((fm = field.exec(scriptText)) && media.length < 30) {
          pushMedia(fm[1]);
        }
      });
    } catch (e) {}

    return {
      media: media,
      iframes: iframes,
      subtitles: F.subtitlesFromHtml(),
      scripts: scripts.slice(0, 80)
    };
  }

  F.finish(function () {
    var data = gather();
    F.send({
      schema: 'freepi.stream.v1',
      ok: true,
      source: F.sourceInfo(),
      data: {
        pageUrl: window.location.href,
        title: document.title || '',
        media: data.media,
        iframes: data.iframes,
        subtitles: data.subtitles,
        scripts: data.scripts
      }
    });
  });
})();