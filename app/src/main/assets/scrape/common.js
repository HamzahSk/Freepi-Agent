/**
 * Freepi Stream - shared DOM extraction helpers.
 *
 * Runs inside the hidden scraping WebView (Chromium). Exposes window.__freepi
 * with everything the per-page extractors need, and posts the final JSON back
 * to Kotlin through window.FreepiScrapeBridge.
 */
(function () {
  'use strict';

  var REQUEST_ID = window.__freepiRequestId || '';
  var REQUEST_URL = window.__freepiRequestUrl || '';
  var BRIDGE = window.FreepiScrapeBridge;

  function send(payload) {
    try {
      if (!BRIDGE || !BRIDGE.postMessage) return;
      BRIDGE.postMessage(REQUEST_ID, JSON.stringify(payload));
    } catch (e) {
      // Never let a bridge failure kill the WebView task silently.
      try {
        BRIDGE.postMessage(REQUEST_ID, JSON.stringify(errorPayload('JS_ERROR', String(e))));
      } catch (ignore) {
        /* nothing left to do */
      }
    }
  }

  function errorPayload(code, message) {
    return {
      schema: 'freepi.error.v1',
      ok: false,
      source: sourceInfo(),
      error: { code: code, message: String(message || '').slice(0, 500) }
    };
  }

  function sourceInfo() {
    return {
      requestedUrl: REQUEST_URL,
      finalUrl: window.location.href,
      title: document.title || '',
      readyState: document.readyState,
      fetchedAt: Date.now()
    };
  }

  function isChallenge() {
    var title = document.title || '';
    var body = '';
    try { body = (document.body && document.body.innerText || '').slice(0, 4000); } catch (e) {}
    var haystack = title + ' ' + body;
    return /just a moment|attention required|tunggu sebentar|melakukan verifikasi keamanan|verify you are human|cf_browser_verification|challenge-platform|cf-chl/i.test(haystack) ||
      !!document.querySelector('#challenge-form, #challenge-running, #challenge-stage, .cf-browser-verification, #cf-chl-running');
  }

  function error(msg) { return String(msg && msg.message || msg || 'extraction error'); }

  function finish(builder) {
    try {
      if (isChallenge()) {
        send(errorPayload('ACCESS_BLOCKED',
          'The data source is protected by an anti-bot challenge. Try again in a moment.'));
        return;
      }
      builder();
    } catch (e) {
      send(errorPayload('JS_ERROR', error(e)));
    }
  }

  function waitFor(fn, timeoutMs) {
    return new Promise(function (resolve) {
      var start = Date.now();
      function tick() {
        var value = null;
        try { value = fn(); } catch (e) {}
        if (value) return resolve(value);
        if (Date.now() - start >= timeoutMs) return resolve(null);
        setTimeout(tick, 120);
      }
      tick();
    });
  }

  function text(el) {
    if (!el) return '';
    return (el.textContent || el.innerText || '').replace(/\s+/g, ' ').trim();
  }

  function cleanTitle(raw) {
    var t = String(raw || '').replace(/\s+/g, ' ').trim();
    t = t.split(' Film Subtitle')[0];
    t = t.split(' Sub Indo')[0];
    t = t.replace(/^Nonton\s+/i, '');
    t = t.replace(/^Streaming\s+/i, '');
    return t.trim();
  }

  function num(raw) {
    var m = String(raw || '').match(/\d+(?:[.,]\d+)?/);
    return m ? m[0] : null;
  }

  function abs(raw) {
    if (!raw) return null;
    var value = String(raw).trim();
    if (!value) return null;
    if (value.indexOf('data:') === 0 || value.indexOf('blob:') === 0) return null;
    try { return new URL(value, window.location.href).href; } catch (e) { return null; }
  }

  function isMedia(u) {
    return /\.(?:m3u8|mp4|m4v|webm|mpd|mov)(\?|#|$)/i.test(u);
  }

  var RESERVED = {
    genre: 1, country: 1, year: 1, quality: 1, size: 1, artist: 1, director: 1,
    tag: 1, playlist: 1, page: 1, movie: 1, series: 1, release: 1, rating: 1,
    title: 1, populer: 1, latest: 1, 'latest-series': 1, 'search.php': 1,
    search: 1, 'nd.php': 1, dmca: 1, 'wp-content': 1, 'wp-json': 1, feed: 1,
    rss: 1, amp: 1, 'rekomendasi-film-pintar': 1, 'daftar-playlist': 1,
    'petunjuk-cara-download-film': 1, 'subtitle-maker': 1, author: 1, category: 1
  };

  function slugOf(u) {
    try {
      var path = new URL(u).pathname.replace(/^\/+|\/+$/g, '');
      var seg = path.split('/').filter(Boolean);
      if (seg.length !== 1) return null;
      if (RESERVED[seg[0].toLowerCase()]) return null;
      return seg[0];
    } catch (e) { return null; }
  }

  var CARD_CONTAINERS = [
    'article', '.mega-item', '.widget-item', '.featured-item', '.search-item',
    'li.slider', '.slider-item', '.ml-item', '.item', '.item-media', 'li.ml-item'
  ].join(', ');

  function cardAnchor(el, a) {
    if (a.getAttribute('href') === '#') return false;
    var href = a.getAttribute('href') || '';
    if (/^javascript:/i.test(href)) return false;
    if (el.querySelector('.poster-title a')) return a !== el.querySelector('.poster-title a') ? a : a;
    return true;
  }

  /** Taxonomy links that are part of a single card (not navigation). */
  function cardGenres(el) {
    var genres = [];
    var els = el.querySelectorAll('figcaption .genre, [itemprop="genre"], a[href*="/genre/"]');
    for (var i = 0; i < els.length; i++) {
      var value = els[i].getAttribute('content') || text(els[i]);
      if (value && genres.indexOf(value) < 0) genres.push(value);
    }
    // The captured theme splits genres with commas inside one .genre node.
    var flat = genres.join(',').split(',').map(function (s) { return s.trim(); })
      .filter(function (s) { return s && s.length < 40; })
      .filter(function (s, idx, arr) { return arr.indexOf(s) === idx; });
    return flat.slice(0, 6);
  }

  /** Card in the captured `li.slider/article a[itemprop=url]` theme. */
  function cardFromContainer(el) {
    if (!el || el.nodeType !== 1) return null;
    if (el.closest('.header, header, nav, .navbar, .widget-header, .breadcrumb, .sidebar, #pagination, .mobile-menu, .filter-overlay, .pop-movie-info')) return null;
    var a = el.querySelector('a[itemprop="url"]') || el.querySelector('a[rel="bookmark"]') || null;
    if (!a) {
      var links = el.querySelectorAll('a[href]');
      for (var i = 0; i < links.length; i++) {
        var l = links[i];
        var href = l.getAttribute('href') || '';
        if (/^javascript:/i.test(href)) continue;
        if (l.closest('.pop-movie-info, .following, .breadcrumb, nav, .pagination')) continue;
        if (slugOf(abs(href))) { a = l; break; }
      }
    }
    if (!a) return null;
    var hrefVal = abs(a.getAttribute('href'));
    var slug = slugOf(hrefVal);
    if (!slug) return null;

    var img = el.querySelector('img') || null;
    var poster = null;
    if (img) {
      poster = img.getAttribute('data-src') || img.getAttribute('data-original') ||
        img.getAttribute('data-lazy-src') || img.getAttribute('src');
    }
    poster = abs(poster);

    var title = text(el.querySelector('.poster-title, .grid-title, .entry-title, .video-info .video-title, h1, h2, h3, .title')) ||
      cleanTitle(a.getAttribute('title')) ||
      cleanTitle(img && img.getAttribute('alt')) ||
      cleanTitle(text(a));
    title = cleanTitle(title);
    if (!title) return null;

    var year = text(el.querySelector('.year, .date, .video-year, [itemprop="datePublished"]')) ||
      (title.match(/\((\d{4})\)/) || [])[1] || null;

    var ratingEl = el.querySelector('.rating [itemprop="ratingValue"], [itemprop="ratingValue"], .rating, .score');
    var rating = num(ratingEl ? text(ratingEl) : null);
    var quality = text(el.querySelector('.poster .label, .quality, .label-HD, .label-SD, .quality-badge, .poster .label-HD')) || null;
    var duration = text(el.querySelector('.duration, .runtime, .episode strong, .episode, .video-duration')) || null;

    return {
      slug: slug,
      title: title,
      url: hrefVal,
      poster: poster,
      rating: rating,
      quality: quality,
      duration: duration,
      genres: cardGenres(el),
      year: year
    };
  }

  function collectCards(root) {
    root = root || document;
    var seen = Object.create(null);
    var out = [];

    function push(c) {
      if (!c || !c.slug || !c.title || seen[c.slug]) return;
      seen[c.slug] = 1;
      out.push(c);
    }

    var containers = [];
    try {
      root.querySelectorAll(CARD_CONTAINERS).forEach(function (el) {
        if (containers.indexOf(el) < 0) containers.push(el);
      });
    } catch (e) {}

    containers.forEach(function (el) { push(cardFromContainer(el)); });

    if (!out.length) {
      root.querySelectorAll('a[rel="bookmark"], a[itemprop="url"]').forEach(function (a) {
        var parent = a.closest(CARD_CONTAINERS) || a.parentElement;
        push(cardFromContainer(parent));
      });
    }
    if (!out.length) {
      var anchorLinks = root.querySelectorAll('a[href]');
      for (var i = 0; i < anchorLinks.length && out.length < 200; i++) {
        var anchor = anchorLinks[i];
        var href = anchor.getAttribute('href') || '';
        if (/^javascript:/i.test(href)) continue;
        if (!slugOf(abs(href))) continue;
        if (!anchor.querySelector('img') && !(anchor.closest('article'))) continue;
        var parent = anchor.closest(CARD_CONTAINERS) || anchor.parentElement;
        push(cardFromContainer(parent));
      }
    }
    return out;
  }

  function collectFilters() {
    var groups = { genres: {}, countries: {}, years: {}, qualities: {} };
    var order = { genres: 'genre', countries: 'country', years: 'year', qualities: 'quality' };

    document.querySelectorAll('a[href]').forEach(function (a) {
      var href = a.getAttribute('href') || '';
      if (!href || /^javascript:/i.test(href)) return;
      var url;
      try { url = new URL(href, window.location.href); } catch (e) { return; }
      var match = url.pathname.match(/^\/(genre|country|year|quality)\/([^\/]+)\/?$/);
      if (!match) return;
      var name = text(a);
      if (!name || name.indexOf(' ') >= 0 && name.length > 40) return;
      var key = Object.keys(order).find(function (k) { return order[k] === match[1]; });
      var entry = { name: name, path: '/' + match[1] + '/' + match[2] + '/' };
      if (!groups[key][entry.path]) groups[key][entry.path] = entry;
    });

    var result = {};
    Object.keys(order).forEach(function (key) {
      result[key] = Object.keys(groups[key]).map(function (p) { return groups[key][p]; })
        .sort(function (x, y) { return x.name.localeCompare(y.name); });
    });
    result.years.sort(function (x, y) { return y.name.localeCompare(x.name); });
    return result;
  }

  function paginationInfo(fallbackPage) {
    var textVal = '';
    ['#pagination', '.pagination', 'nav.pagination', '.page-nav', 'ul.page-numbers']
      .forEach(function (sel) {
        if (!textVal) { var el = document.querySelector(sel); if (el) textVal = text(el); }
      });

    var match = /(?:Halaman|Page|Hal)\s+(\d+)\s+(?:dari|of)\s+([\d.,]+)/i.exec(textVal);
    var current = match ? parseInt(match[1], 10) : (fallbackPage || 1);
    var totalPages = match ? parseInt(match[2].replace(/[.,]/g, ''), 10) : null;

    var pages = [];
    document.querySelectorAll('#pagination a, .pagination a, a.page-numbers, a[href*="/page/"]')
      .forEach(function (a) {
        var m = /\/page\/(\d+)/.exec(a.getAttribute('href') || '');
        if (m) pages.push(parseInt(m[1], 10));
      });
    if (totalPages === null && pages.length) totalPages = Math.max.apply(null, pages);

    var nextUrl = null;
    var nextEl = document.querySelector(
      '#pagination a.next, .pagination a.next, a.next.page-numbers, a[rel="next"], .nextpostslink, a.pagination-next'
    );
    if (nextEl) nextUrl = abs(nextEl.getAttribute('href'));

    if (!nextUrl) {
      var nextText = document.querySelector('#pagination a, .pagination a');
      document.querySelectorAll('#pagination a, .pagination a').forEach(function (a) {
        var t = text(a);
        if (!nextUrl && /^(»|next|selanjutnya|berikutnya)$/i.test(t)) nextUrl = abs(a.getAttribute('href'));
      });
    }
    if (!nextUrl && totalPages === null) totalPages = current;
    if (!nextUrl && totalPages !== null && totalPages > current) {
      // Fallback: construct /page/N+1 preserving any query string.
      var base = window.location.pathname.replace(/\/page\/\d+\/?$/, '').replace(/\/+$/, '') || '';
      var query = window.location.search || '';
      nextUrl = window.location.origin + '/' +
        (base ? base.replace(/^\//, '') + '/' : '') + 'page/' + (current + 1) + '/' +
        (query ? query.replace('?s=', '?s=') : '');
    }
    if (totalPages === null) totalPages = current;

    return {
      page: current,
      totalPages: Math.max(totalPages, current),
      nextPageUrl: nextUrl
    };
  }

  function pageFromUrl() {
    var m = /\/page\/(\d+)/.exec(window.location.pathname);
    return m ? parseInt(m[1], 10) : 1;
  }

  function subtitlesFromHtml() {
    var seen = Object.create(null);
    var out = [];
    var html;
    try { html = (document.documentElement.outerHTML || '').replace(/\\\//g, '/'); } catch (e) { return out; }
    var re = /(?:https?:)?\/\/[^\s"'<>\\]+?\.(?:srt|vtt)(?:\?[^\s"'<>\\]*)?/gi;
    var m;
    while ((m = re.exec(html)) && out.length < 50) {
      var raw = m[0];
      if (raw.indexOf('//') === 0) raw = 'https:' + raw;
      var u = abs(raw);
      if (!u || seen[u]) continue;
      seen[u] = 1;
      out.push({ label: decodeURIComponent(u.split('/').pop().split('?')[0] || 'subtitle'), url: u });
    }
    return out;
  }

  /** Parses the `#season-data` JSON block (series hub + episode pages). */
  function seasonData() {
    var el = document.querySelector('#season-data');
    if (!el) return null;
    try {
      var raw = JSON.parse(el.textContent || '');
      return parseSeasons(raw);
    } catch (e) { return null; }
  }

  function parseSeasons(raw) {
    var seasons = [];
    if (raw && typeof raw === 'object') {
      Object.keys(raw).forEach(function (key) {
        var list = raw[key];
        if (!Array.isArray(list)) return;
        var eps = [];
        for (var i = 0; i < list.length; i++) {
          var item = list[i] || {};
          if (item.slug && (item.title || item.episode_no !== undefined)) {
            eps.push({ number: parseInt(item.episode_no, 10) || i + 1, title: String(item.title || ''), slug: String(item.slug) });
          }
        }
        if (eps.length) {
          seasons.push({ number: parseInt(key, 10) || 0, episodes: eps });
        }
      });
    }
    seasons.sort(function (a, b) { return a.number - b.number; });
    return seasons;
  }

  /** `#watch-history-data` JSON (id/title/rating/poster/slug/year/next/...). */
  function watchHistory() {
    var el = document.querySelector('#watch-history-data');
    if (!el) return null;
    try { return JSON.parse(el.textContent || ''); } catch (e) { return null; }
  }

  /** Taxonomy chips in `.tag-list` (type from href first segment). */
  function tagList() {
    var tags = [];
    var els = document.querySelectorAll('.tag-list .tag a, .tag-list a, .tag a[href], [itemprop="genre"]');
    for (var i = 0; i < els.length; i++) {
      var a = els[i];
      var href = a.getAttribute('href') || '';
      var type = href.split('/').filter(Boolean)[0] || '';
      var name = text(a);
      if (name) tags.push({ type: type, name: name });
    }
    return tags;
  }

  /** Streaming providers from `#player-list` / `#player-select`. */
  function playerSources(iframeBase) {
    var out = [];
    var seen = Object.create(null);

    function add(raw, server) {
      var u = abs(raw);
      if (!u) return;
      if (/youtube\.com|youtu\.be|recaptcha|googlevideo\.com|doubleclick|googleads|javascript:/i.test(u)) return;
      if (seen[u]) return;
      seen[u] = 1;
      out.push({
        provider: server, label: server, embedUrl: u, decodedUrl: u,
        maxQuality: null, qualities: []
      });
    }

    document.querySelectorAll('#player-list li a, #player-select option').forEach(function (a) {
      var raw = a.getAttribute('data-url') || a.getAttribute('href') || a.getAttribute('value') || '';
      if (!raw || raw === '#' || /^javascript:/i.test(raw)) return;
      var server = a.getAttribute('data-server') || text(a) || 'Provider';
      add(raw, server.toUpperCase());
    });

    // The already-active main player iframe is also a valid provider entry.
    if (iframeBase) add(iframeBase, 'MAIN');
    return out;
  }

  window.__freepi = {
    REQUEST_ID: REQUEST_ID,
    send: send,
    errorPayload: errorPayload,
    sourceInfo: sourceInfo,
    isChallenge: isChallenge,
    finish: finish,
    waitFor: waitFor,
    text: text,
    cleanTitle: cleanTitle,
    num: num,
    abs: abs,
    isMedia: isMedia,
    slugOf: slugOf,
    cardFromContainer: cardFromContainer,
    collectCards: collectCards,
    collectFilters: collectFilters,
    paginationInfo: paginationInfo,
    pageFromUrl: pageFromUrl,
    subtitlesFromHtml: subtitlesFromHtml,
    seasonData: seasonData,
    watchHistory: watchHistory,
    tagList: tagList,
    playerSources: playerSources
  };
})();