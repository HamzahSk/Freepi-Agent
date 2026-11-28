(() => {
  // raw:/home/runner/work/_temp/movie-source/extensions/lk21/scripts/common.js
  var common_default = `/**
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
    return (el.textContent || el.innerText || '').replace(/\\s+/g, ' ').trim();
  }

  function cleanTitle(raw) {
    var t = String(raw || '').replace(/\\s+/g, ' ').trim();
    t = t.split(' Film Subtitle')[0];
    t = t.split(' Sub Indo')[0];
    t = t.split(' | Streaming')[0];
    t = t.replace(/^(?:Lk21\\s+Nonton|Nonton|Streaming)\\s+/i, '');
    t = t.replace(/\\s*\\|.*$/, '');
    return t.trim();
  }

  function num(raw) {
    var m = String(raw || '').match(/\\d+(?:[.,]\\d+)?/);
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
    return /\\.(?:m3u8|mp4|m4v|webm|mpd|mov)(\\?|#|$)/i.test(u);
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
      var path = new URL(u).pathname.replace(/^\\/+|\\/+$/g, '');
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

  /** Card in the captured \`li.slider/article a[itemprop=url]\` theme. */
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
      (title.match(/\\((\\d{4})\\)/) || [])[1] || null;

    var ratingEl = el.querySelector('.rating [itemprop="ratingValue"], [itemprop="ratingValue"], .rating, .score');
    var rating = num(ratingEl ? text(ratingEl) : null);
    var quality = text(el.querySelector('.poster .label, .quality, .label-HD, .label-SD, .quality-badge, .poster .label-HD')) || null;
    // Series cards label "S.1" in .duration and "EPS 9" in .episode strong;
    // movies label the runtime in .duration.
    var epsEl = el.querySelector('.episode strong');
    var duration = text(el.querySelector('.duration, .runtime, .video-duration')) || null;
    if (epsEl) {
      var eps = text(epsEl);
      var seasonLabel = text(el.querySelector('.duration')) || '';
      duration = ((seasonLabel && seasonLabel !== '') ? seasonLabel + ' \\u00b7 ' : '') + eps + ' eps';
    }

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
      var match = url.pathname.match(/^\\/(genre|country|year|quality)\\/([^\\/]+)\\/?$/);
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

    var match = /(?:Halaman|Page|Hal)\\s+(\\d+)\\s+(?:dari|of)\\s+([\\d.,]+)/i.exec(textVal);
    var current = match ? parseInt(match[1], 10) : (fallbackPage || 1);
    var totalPages = match ? parseInt(match[2].replace(/[.,]/g, ''), 10) : null;

    var pages = [];
    document.querySelectorAll('#pagination a, .pagination a, a.page-numbers, a[href*="/page/"]')
      .forEach(function (a) {
        var m = /\\/page\\/(\\d+)/.exec(a.getAttribute('href') || '');
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
        if (!nextUrl && /^(\xBB|next|selanjutnya|berikutnya)$/i.test(t)) nextUrl = abs(a.getAttribute('href'));
      });
    }
    if (!nextUrl && totalPages === null) totalPages = current;
    if (!nextUrl && totalPages !== null && totalPages > current) {
      // Fallback: construct /page/N+1 preserving the query string (?s=, filters).
      var base = window.location.pathname.replace(/\\/page\\/\\d+\\/?$/, '').replace(/\\/+$/, '');
      var query = window.location.search || '';
      var path = ((base || '') + '/page/' + (current + 1) + '/').replace(/^\\/+/, '');
      nextUrl = window.location.origin + '/' + path + query;
    }
    if (totalPages === null) totalPages = current;

    return {
      page: current,
      totalPages: Math.max(totalPages, current),
      nextPageUrl: nextUrl
    };
  }

  function pageFromUrl() {
    var m = /\\/page\\/(\\d+)/.exec(window.location.pathname);
    return m ? parseInt(m[1], 10) : 1;
  }

  function subtitlesFromHtml() {
    var seen = Object.create(null);
    var out = [];
    var html;
    try { html = (document.documentElement.outerHTML || '').replace(/\\\\\\//g, '/'); } catch (e) { return out; }
    var re = /(?:https?:)?\\/\\/[^\\s"'<>\\\\]+?\\.(?:srt|vtt)(?:\\?[^\\s"'<>\\\\]*)?/gi;
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

  /** Parses the \`#season-data\` JSON block (series hub + episode pages). */
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

  /** \`#watch-history-data\` JSON (id/title/rating/poster/slug/year/next/...). */
  function watchHistory() {
    var el = document.querySelector('#watch-history-data');
    if (!el) return null;
    try { return JSON.parse(el.textContent || ''); } catch (e) { return null; }
  }

  /** Taxonomy chips in \`.tag-list\` (type from href first segment). */
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

  /** Streaming providers from \`#player-list\` / \`#player-select\`. */
  function playerSources(iframeBase) {
    var out = [];
    var seen = Object.create(null);

    function add(raw, server) {
      var u = abs(raw);
      if (!u) return;
      if (/youtube\\.com|youtu\\.be|recaptcha|googlevideo\\.com|doubleclick|googleads|javascript:/i.test(u)) return;
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
})();`;

  // raw:/home/runner/work/_temp/movie-source/extensions/lk21/scripts/home.js
  var home_default = `/**
 * Home page extraction. Produces freepi.home.v1.
 */
(function () {
  'use strict';
  var F = window.__freepi;
  F.finish(function () {
    var sections = { featured: [], popular: [], latest: [], recommended: [], series: [] };
    var all = {};
    var seen = Object.create(null);

    function merge(key, cards) {
      cards.forEach(function (c) {
        if (c && c.slug && !seen[c.slug]) {
          seen[c.slug] = 1;
          sections[key].push(c);
        }
      });
    }

    // Captured theme (lk21-request/): sidebar .pop-movie-item widgets (most popular).
    document.querySelectorAll('.pop-movie-item').forEach(function (item) {
      var a = item.querySelector('a[href]') || null;
      if (!a) return;
      var href = a.getAttribute('href') || '';
      var slug = F.slugOf(F.abs(href));
      if (!slug || seen[slug]) return;
      var img = item.querySelector('img');
      var poster = null;
      if (img) poster = img.getAttribute('data-src') || img.getAttribute('src');
      seen[slug] = 1;
      sections.popular.push({
        slug: slug,
        title: F.cleanTitle(F.text(item.querySelector('.pop-movie-title, h3')) || (img && img.getAttribute('alt')) || F.cleanTitle(F.text(a))),
        url: F.abs(href),
        poster: F.abs(poster),
        rating: null,
        quality: null,
        duration: null,
        genres: [],
        year: F.text(item.querySelector('.pop-movie-year')) || null
      });
    });

    // New theme: .widget[data-type="latest-movies"] etc.
    document.querySelectorAll('.widget[data-type]').forEach(function (widget) {
      var type = (widget.getAttribute('data-type') || '').toLowerCase();
      if (widget.id === 'you-may-wrapper') type = 'recommendation';
      all[type] = (all[type] || []).concat(F.collectCards(widget));
    });

    // Old theme: section[id="populer"], #latest, #recomendation, #series-unggulan
    document.querySelectorAll('section[id], div[id]').forEach(function (el) {
      var id = (el.id || '').toLowerCase();
      if (!/populer|latest|recomend|recommend|series|featured/i.test(id)) return;
      if (el.id === 'you-may-wrapper') return;
      var sectionCards = F.collectCards(el);
      if (!sectionCards.length) return;
      all['by-id-' + id] = (all['by-id-' + id] || []).concat(sectionCards);
    });

    Object.keys(all).forEach(function (key) {
      var cards = all[key];
      if (key.indexOf('featured') >= 0 || key.indexOf('slider') >= 0) merge('featured', cards);
      else if (key.indexOf('popular') >= 0 || key.indexOf('trending') >= 0 ||
          key.indexOf('populer') >= 0 || key.indexOf('top-movie') >= 0) merge('popular', cards);
      else if (key.indexOf('series') >= 0 || key.indexOf('serial') >= 0) merge('series', cards);
      else if (key.indexOf('recommend') >= 0 || key.indexOf('you-may') >= 0) merge('recommended', cards);
      else if (key.indexOf('latest-movie') >= 0 || key.indexOf('latest-film') >= 0 || key === 'latest' ||
          key === 'by-id-latest') merge('latest', cards);
      else merge('recommended', cards); // extra rails: latest-action, top-series-today, korea, ...
    });

    // Featured hero sliders (old and new theme).
    var hero = document.querySelector('.featured .slider, .featured .swiper-wrapper, .hero-slider');
    if (hero) merge('featured', F.collectCards(hero));

    // Latest from the main post grid (list-style home) and the captured
    // theme's "Nonton Film Terbaru" grid (.video-list li / main .column).
    var mainGrid = document.querySelector('#post-container, .gallery-grid, main');
    if (mainGrid && !sections.latest.length) {
      var gridCards = F.collectCards(mainGrid);
      if (gridCards.length) {
        merge('latest', gridCards.slice(0, 24));
      } else {
        // Captured theme: <li><a href="/slug"> with .video-info/.video-title.
        var videoItems = mainGrid.querySelectorAll('.video-info, .video-list li, .column li');
        videoItems.forEach(function (el) {
          var a = el.querySelector('a[href]') || (el.tagName === 'A' ? el : null);
          if (!a) return;
          var href = a.getAttribute('href') || '';
          var slug = F.slugOf(F.abs(href));
          if (!slug || seen[slug]) return;
          var img = a.querySelector('img');
          if (!img) return;
          seen[slug] = 1;
          sections.latest.push({
            slug: slug,
            title: F.cleanTitle(F.text(el.querySelector('.video-title, .poster-title, h3')) || (img && img.getAttribute('alt')) || F.cleanTitle(F.text(a))),
            url: F.abs(href),
            poster: F.abs(img.getAttribute('data-src') || img.getAttribute('src')),
            rating: F.num(F.text(el.querySelector('.rating'))),
            quality: F.text(el.querySelector('.label-HD, .quality')) || null,
            duration: F.text(el.querySelector('.video-year, .year')) || null,
            genres: [],
            year: F.text(el.querySelector('.video-year, .year')) || null
          });
        });
      }
    }

    // Absolute fallback: the entire document (only if nothing matched found).
    var total = sections.featured.length + sections.popular.length + sections.latest.length +
      sections.recommended.length + sections.series.length;
    if (total === 0) merge('latest', F.collectCards(document).slice(0, 30));

    F.send({
      schema: 'freepi.home.v1',
      ok: true,
      source: F.sourceInfo(),
      data: {
        sections: sections,
        filters: F.collectFilters()
      }
    });
  });
})();`;

  // raw:/home/runner/work/_temp/movie-source/extensions/lk21/scripts/list.js
  var list_default = "/**\n * Catalog listing / search result extraction. Produces freepi.list.v1.\n */\n(function () {\n  'use strict';\n  var F = window.__freepi;\n\n  function isEmptyHint() {\n    var body = '';\n    try { body = (document.body && document.body.innerText || '').slice(0, 3000); } catch (e) {}\n    return /tidak ditemukan|tidak ada (?:hasil|film)|no results?|nothing found|not found|maaf,?\\s*(?:hasil|film)|searched for.*no results/i\n      .test(body) || !!document.querySelector('.no-results, .search-no-results, #no-results, .not-found');\n  }\n\n  var requested = new URL(window.__freepiRequestUrl || location.href);\n  var isSearch = requested.searchParams.has('s');\n\n  function collectGrid() {\n    var scope = document.querySelector('.search-results, #search-results, #search-result, #post-container, .gallery-grid, .movies-list, .video-list-wrapper, main') || document.body;\n    var items = F.collectCards(scope);\n    if (!items.length && !isSearch) items = F.collectCards(document).slice(0, 200);\n    return { items: items, scope: scope };\n  }\n\n  function collectFiltered() {\n    // Captured theme: filter/archive pages list <li> cards under .video-list / .column.\n    var out = [];\n    var seen = Object.create(null);\n    document.querySelectorAll('.video-list li, .video-list-wrapper li, .column li, .movie-list li')\n      .forEach(function (el) {\n        if (el.closest('.footer, footer, .mob-list-eps, .episode')) return;\n        var a = el.querySelector('a[href]') || (el.tagName === 'A' ? el : null);\n        if (!a) return;\n        var href = a.getAttribute('href') || '';\n        var slug = F.slugOf(F.abs(href));\n        if (!slug || seen[slug]) return;\n        var img = a.querySelector('img');\n        if (!img && !el.querySelector('.video-info')) return;\n        seen[slug] = 1;\n        out.push({\n          slug: slug,\n          title: F.cleanTitle(F.text(el.querySelector('.video-title, .poster-title, h3, h2')) || (img && img.getAttribute('alt')) || F.cleanTitle(F.text(a))),\n          url: F.abs(href),\n          poster: F.abs(img && (img.getAttribute('data-src') || img.getAttribute('src'))),\n          rating: F.num(F.text(el.querySelector('.rating'))),\n          quality: F.text(el.querySelector('.label-HD, .quality')) || null,\n          duration: F.text(el.querySelector('.video-year, .year')) || null,\n          genres: [],\n          year: F.text(el.querySelector('.video-year, .year')) || null\n        });\n      });\n    return out;\n  }\n\n  F.finish(function () {\n    var grid = collectGrid();\n    var items = grid.items;\n    if (!items.length && !isSearch) items = collectFiltered();\n    if (isSearch) {\n      var heading = F.text(document.querySelector('h1, .page-title'));\n      if (!/search|pencarian|hasil|cari/i.test(heading) && !document.querySelector('.search-results, #search-results, #search-result, .search-no-results')) {\n        F.send(F.errorPayload('MALFORMED', 'LK21 did not return a recognizable search results page.'));\n        return;\n      }\n    }\n\n    var page = F.pageFromUrl();\n    var pagination = F.paginationInfo(page);\n    var emptyHint = items.length === 0 && isEmptyHint();\n\n    var title = F.text(document.querySelector('h1')) ||\n      F.text(document.querySelector('.page-title')) || '';\n\n    F.send({\n      schema: 'freepi.list.v1',\n      ok: true,\n      source: F.sourceInfo(),\n      data: {\n        title: title,\n        items: items,\n        page: pagination.page,\n        totalPages: pagination.totalPages,\n        nextPageUrl: pagination.nextPageUrl,\n        emptyHint: emptyHint\n      }\n    });\n  });\n})();";

  // raw:/home/runner/work/_temp/movie-source/extensions/lk21/scripts/detail.js
  var detail_default = `/**
 * Movie/series/episode detail page extraction. Produces freepi.detail.v1.
 *
 * Handles the markup captured from the live site (lk21-request/):
 *  - TVSeries hub:  .info-tag, .tag-list, .rating-number[data-base-rating],
 *    .rating-users[data-base-votes], .synopsis, .detail p, #season-data,
 *    #watch-history-data, "Play Awal"/"Play Terbaru" links, no player.
 *  - TVEpisode:     #player-list li a[data-url][data-server], #main-player,
 *    #watch-history-data {next, current_season, current_eps}, "Series:" link.
 *  - Movie:         JSON-LD Movie, #player-list, .yt-lightbox trailer,
 *    .info-tag [17+, WEBDL, 1080p, 1h 42m], runtime in #watch-history-data.
 */
(function () {
  'use strict';
  var F = window.__freepi;

  function parseJsonLd() {
    var found = null;
    document.querySelectorAll('script[type="application/ld+json"]').forEach(function (s) {
      if (found) return;
      try {
        var parsed = JSON.parse(s.textContent || '');
        var list = Array.isArray(parsed) ? parsed : [parsed];
        for (var i = 0; i < list.length; i++) {
          var o = list[i] || {};
          if (/movie|tvseries|tvepisode|creativework|videoobject/i.test(o['@type'] || '')) {
            found = o;
            break;
          }
        }
      } catch (e) { /* skip malformed blocks */ }
    });
    return found || null;
  }

  function metaMap() {
    var meta = {};

    // Captured theme: .detail p / .meta-info p with a <span> label.
    document.querySelectorAll('.detail p, .meta-info p').forEach(function (p) {
      if (p.closest('.synopsis, .trailer-series')) return;
      var span = p.querySelector('span');
      if (!span) return;
      var label = F.text(span).replace(/:$/, '').trim();
      if (!label) return;
      var clone = p.cloneNode(true);
      var s = clone.querySelector('span');
      if (s) s.remove();
      var value = F.text(clone);
      if (value && !meta[label]) meta[label] = value;
    });

    // Old theme: #movie-detail .content > div with h2 label + h3 values.
    document.querySelectorAll('#movie-detail .content > div').forEach(function (row) {
      var label = F.text(row.querySelector('h2'));
      var vals = [];
      row.querySelectorAll('h3').forEach(function (h3) { var t = F.text(h3); if (t) vals.push(t); });
      if (label && vals.length && !meta[label]) meta[label] = vals.join(', ');
    });

    return meta;
  }

  function infoTags() {
    var out = [];
    document.querySelectorAll('.info-tag span, .tags span, .tag-item')
      .forEach(function (el) { var t = F.text(el); if (t) out.push(t); });
    return out;
  }

  function synopsis() {
    var s = document.querySelector('.synopsis');
    if (s) {
      var full = s.getAttribute('data-full');
      if (full && full.length > 20) return String(full).replace(/\\s+/g, ' ').trim();
      var clone = s.cloneNode(true);
      clone.querySelectorAll('a, button, script, style').forEach(function (a) { a.remove(); });
      return F.text(clone);
    }
    var block = document.querySelector('#movie-detail blockquote');
    if (block) {
      var c = block.cloneNode(true);
      c.querySelectorAll('strong, .hidden, script, style, a').forEach(function (el) { el.remove(); });
      return F.text(c);
    }
    var desc = document.querySelector('.entry-content p, .post-content p, [itemprop="description"]');
    return desc ? F.text(desc) : '';
  }

  // -------------------------------------------------------------- sources

  function gatherSources() {
    var out = [];
    var seen = Object.create(null);

    function add(rawUrl, server, qualities, rel) {
      var u = F.abs(rawUrl);
      if (!u) return;
      if (/youtube\\.com|youtu\\.be|recaptcha|googlevideo\\.com|doubleclick|googleads/i.test(u)) return;
      if (seen[u]) return;
      seen[u] = 1;
      var decoded = null;
      try {
        var parsed = new URL(u, window.location.href);
        var q = parsed.searchParams.get('url');
        if (q) decoded = q;
      } catch (e) {}
      out.push({
        provider: server,
        label: server,
        embedUrl: u,
        decodedUrl: decoded || u,
        maxQuality: /^\\d{3,4}$/.test(rel || '') ? rel : qualities[0] || null,
        qualities: qualities
      });
    }

    // Captured theme: #player-list li a[data-url][data-server] (+ main iframe).
    document.querySelector('#player-list, .player-options') && F.playerSources().forEach(function (src) {
      add(src.embedUrl, src.provider, src.qualities || [], '');
    });

    // Old theme: #loadProviders.
    document.querySelectorAll(
      '#loadProviders li a, #loadProviders a[target="iframe"], .player-list li a, ' +
      '.player-select li a, #player-list a[data-iframe]'
    ).forEach(function (a) {
      var raw = a.getAttribute('data-url') || a.getAttribute('data-iframe') ||
        a.getAttribute('href') || '';
      if (!raw || /^javascript:/i.test(raw) || raw === '#') return;
      var li = a.closest('li') || a.parentElement;
      var server = a.getAttribute('data-server') || F.text(a);
      if (!server) server = (a.className || '').trim().split(/\\s+/)[0] || 'Provider';
      var qualities = [];
      if (li) {
        li.querySelectorAll('.label').forEach(function (el) {
          var t = F.text(el);
          if (/^\\d{3,4}$/.test(t) && qualities.indexOf(t) < 0) qualities.push(t);
        });
      }
      add(raw, server, qualities, a.getAttribute('rel') || '');
    });

    // Embeds embedded directly on the page (excluding the already-added main one).
    document.querySelectorAll('iframe[src]').forEach(function (iframe) {
      var src = iframe.getAttribute('src') || '';
      if (!/playeriframe|iframe\\.php|embed|player\\.php|filemoon|turbovid|hownet|luxubu|hydrax|short\\.ink|videonode/i.test(src)) return;
      if (iframe.id === 'main-player' && maybeAlreadyAdded(src)) return;
      add(src.split('?')[0], 'Embed ' + (out.length + 1), []);
    });

    return out;
  }

  function maybeAlreadyAdded(url) {
    var u = F.abs(url);
    if (!u) return false;
    var base = u.split('?')[0].replace(/\\/+$/, '');
    var existing = collectionContains(u) || collectionContains(base);
    return existing;
  }

  var __sourceUrls = [];
  function collectionContains(u) {
    for (var i = 0; i < __sourceUrls.length; i++) {
      if (__sourceUrls[i] === u) return true;
      var a = __sourceUrls[i].split('?')[0].replace(/\\/+$/, '');
      if (a === u.split('?')[0].replace(/\\/+$/, '')) return true;
    }
    return false;
  }

  function related() {
    // Captured: .mob-related-video .sliders / .mob-related-series .sliders,
    // plus #you-may wrapper and old #related rail.
    var scope = document.querySelector(
      '.mob-related-video .sliders, .mob-related-series .sliders, .mob-related-video .slider-wrapper,' +
      '.mob-related-series .slider-wrapper, #you-may-wrapper, #related, .related, .related-movies'
    );
    var cards = scope ? F.collectCards(scope) : [];
    if (!cards.length) cards = F.collectCards(document).slice(0, 24);
    return cards.slice(0, 24);
  }

  function seasonData() {
    var seasons = F.seasonData() || [];
    var flatten = [];
    for (var i = 0; i < seasons.length; i++) {
      for (var j = 0; j < seasons[i].episodes.length; j++) {
        flatten.push(seasons[i].episodes[j]);
      }
    }
    return { seasons: seasons, episodes: flatten };
  }

  function ldNames(value) {
    var out = [];
    if (Array.isArray(value)) {
      value.forEach(function (per) {
        var n = typeof per === 'string' ? per : (per && per.name);
        if (n) out.push(String(n));
      });
    } else if (value && typeof value === 'object' && value.name) {
      out.push(String(value.name));
    }
    return out;
  }

  F.finish(function () {
    var ld = parseJsonLd();
    var meta = metaMap();
    var tags = F.tagList();
    var info = infoTags();
    var watch = F.watchHistory() || {};
    var sd = seasonData();
    var flatten = sd.episodes;

    var ogTitle = '';
    var og = document.querySelector('meta[property="og:title"]');
    if (og) ogTitle = og.getAttribute('content') || '';
    var ogType = '';
    var ogt = document.querySelector('meta[property="og:type"]');
    if (ogt) ogType = ogt.getAttribute('content') || '';

    var title = F.cleanTitle(
      F.text(document.querySelector('.movie-info h1, .detail h1, .post-header h1, h1, .post-title h1')) ||
      (watch.title ? String(watch.title) : '') ||
      (ld && ld.name ? String(ld.name) : '') ||
      ogTitle
    );
    if (!title && ld && /tvseries|tvepisode/i.test(ld['@type'] || '') && ld.partOfSeries) {
      title = F.cleanTitle(ld.partOfSeries.name || '');
    }

    // Hero poster: the captured theme puts it inside the \`.detail\`/\`.movie-info\`
    // meta block. \`img[itemprop=image]\` is intentionally NOT used here - every
    // related card carries that attribute and it would win by document order.
    var posterRel = document.querySelector(
      '.movie-info picture img, .content-left picture img, .detail a img, .meta-info a img, ' +
      '#movie-detail img.img-thumbnail, .content-poster img'
    );
    var poster = '';
    if (posterRel) poster = posterRel.getAttribute('data-src') || posterRel.getAttribute('src') || '';
    if (!poster) {
      var posterLink = document.querySelector('.movie-info a[href*="poster"], .detail a[href*="poster"], a[href*="poster"][target="_blank"]');
      if (posterLink) {
        var pimg = posterLink.querySelector('img');
        if (pimg) poster = pimg.getAttribute('data-src') || pimg.getAttribute('src') || '';
      }
    }
    if (!poster) {
      var ogImg = document.querySelector('meta[property="og:image"]');
      if (ogImg) poster = ogImg.getAttribute('content') || '';
    }
    if (!poster && ld) poster = ld.image || '';
    if (!poster && watch.poster) poster = String(watch.poster);

    var year = watch && watch.year ? String(watch.year) : '';
    if (!year && ld) year = String(ld.dateCreated || '').slice(0, 4) || String(ld.datePublished || '').slice(0, 4);
    if (!year) year = (title.match(/\\((\\d{4})\\)/) || [])[1] || '';

    var rating = watch && watch.rating ? String(watch.rating) : '';
    var ratingEl = document.querySelector('.rating-number');
    if (!rating && ratingEl) rating = ratingEl.getAttribute('data-base-rating') || '';
    if (!rating && ld && ld.aggregateRating) rating = String(ld.aggregateRating.ratingValue || '');
    if (!rating && meta['IMDb']) rating = meta['IMDb'];
    var ratingVal = F.num(rating) || null;

    var ratingCount = '';
    var votesEl = document.querySelector('.rating-users');
    if (votesEl) ratingCount = votesEl.getAttribute('data-base-votes') || '';
    if (!ratingCount && ld && ld.aggregateRating) ratingCount = String(ld.aggregateRating.reviewCount || '');
    if (!ratingCount && meta['Votes']) ratingCount = F.num(meta['Votes']) || '';
    if (!ratingCount) {
      var imdbMatch = /from\\s+([\\d.,]+)/i.exec(meta['IMDb'] || '');
      if (imdbMatch) ratingCount = imdbMatch[1];
    }
    if (!ratingCount) {
      var big = info.filter(function (t) { return /^\\d{3,}$/.test(t); })[0];
      if (!big) var big = /[\\d.,]+/.exec(meta['Votes'] || '');
      if (big) ratingCount = big[0] || big;
    }

    // .info-tag: [17+, WEBDL|1080p|1h 42m, West, Ongoing] - classify with priority.
    var quality = '';
    var durationText = '';
    info.forEach(function (t) {
      if (!quality && /WEBDL|WEB-DL|WEBRip|BluRay|BDRip|HDCAM|HDTS|HDRip|CAM|DVD|1080p|720p|480p/i.test(t)) quality = t;
    });
    if (!quality) {
      var qSel = document.querySelector('.detail .quality, [itemprop="videoQuality"], .movie-quality, .quality-label');
      if (qSel) quality = F.text(qSel);
    }
    info.forEach(function (t) {
      if (!durationText && (/\\d{1,2}:\\d{2}|\\d+\\s*(?:h|jam|min|menit)\\b/i.test(t) || /^\\d{3,4}$/.test(t))) {
        if (!/^\\d{3,4}$/.test(t)) durationText = t;
      }
    });
    if (!durationText) durationText = meta['Durasi'] || '';
    if (!durationText && watch && watch.runtime) durationText = String(watch.runtime);
    if (!durationText && ld && ld.duration) durationText = String(ld.duration);
    // "1h 42m" -> "01:42" style from watch.runtime is a nicer durationText.
    if ((!durationText || !/\\d/.test(durationText)) && watch && watch.runtime) {
      durationText = String(watch.runtime);
    }

    var genres = tags.filter(function (t) { return t.type === 'genre'; }).map(function (t) { return t.name; });
    if (!genres.length && ld && Array.isArray(ld.genre)) genres = ld.genre.slice();
    if (!genres.length && meta['Genre']) genres = splitValues(meta['Genre']);

    var countries = tags.filter(function (t) { return t.type === 'country'; }).map(function (t) { return t.name; });
    if (!countries.length && meta['Negara']) countries = splitValues(meta['Negara']);

    var directors = ldNames(ld && ld.director);
    if (!directors.length && meta['Sutradara']) directors = splitValues(meta['Sutradara']);

    var cast = [];
    if (meta['Bintang film'] || meta['Bintang Film']) {
      cast = splitValues(meta['Bintang film'] || meta['Bintang Film'] || '');
    }
    if (!cast.length && ld && Array.isArray(ld.actor)) cast = ldNames(ld.actor);

    var imdbId = '';
    var imdbStr = (meta['IMDb'] || '') + ' ' + (ld && ld.sameAs || '') + ' ' +
      document.documentElement.outerHTML.slice(0, 300000);
    var tt = /tt\\d+/.exec(imdbStr);
    if (tt) imdbId = tt[0];

    var trailer = '';
    var trailerEl = document.querySelector(
      '.trailer-series iframe, .simple-box iframe, a.fancybox[href*="youtube"], ' +
      'a[href*="youtube.com/watch"], a[href*="youtu.be"], iframe[src*="youtube.com/embed"], ' +
      'a.yt-lightbox[href*="youtube"]'
    );
    if (trailerEl) trailer = trailerEl.getAttribute('src') || trailerEl.getAttribute('href') || '';

    var published = meta['Diterbitkan'] || meta['Release'] || (ld && ld.datePublished) || '';
    var uploaded = meta['Diunggah'] || meta['Updated'] || '';
    var translator = meta['Penerjemah'] || meta['Subtitle'] || '';

    var redirectUrl = '';
    var openNow = document.querySelector('#openNow');
    if (openNow && openNow.getAttribute('href')) redirectUrl = openNow.getAttribute('href');

    // Series/next-episode navigation (captured watch payload + eps nav).
    var nextSlug = watch.next || '';
    var prevSlug = '';
    var currentSeason = watch.current_season !== undefined ? parseInt(watch.current_season, 10) : null;
    var currentEp = watch.current_eps !== undefined ? parseInt(watch.current_eps, 10) : null;
    var seriesSlug = '';
    var seriesTitle = '';
    document.querySelectorAll('.detail p, .meta-info p').forEach(function (p) {
      if (seriesSlug) return;
      var a = p.querySelector('a[href]');
      var span = p.querySelector('span');
      if (a && span && /series/i.test(F.text(span))) {
        var href = a.getAttribute('href') || '';
        var s = F.slugOf(F.abs(href));
        if (s) { seriesSlug = s; seriesTitle = F.text(a); }
      }
    });
    // Also honour a JSON-LD partOfSeries for TVEpisode pages.
    if (!seriesSlug && ld && ld.partOfSeries && ld.partOfSeries.url) {
      var ps = F.slugOf(F.abs(ld.partOfSeries.url));
      if (ps) { seriesSlug = ps; seriesTitle = ld.partOfSeries.name || ''; }
    }

    var firstSlug = '';
    var latestSlug = '';
    document.querySelectorAll('.movie-action a[href]').forEach(function (a) {
      var t = F.text(a);
      var href = a.getAttribute('href') || '';
      if (/play\\s*awal|mulai\\s*putar/i.test(t) && !firstSlug) firstSlug = F.slugOf(F.abs(href)) || '';
      if (/play\\s*terbaru|episode\\s*terbaru/i.test(t) && !latestSlug) latestSlug = F.slugOf(F.abs(href)) || '';
    });
    // Series hub \u201CTerbaru: Season 1 Episode 8\u201D info row -> latestSlug.
    if (!latestSlug) {
      document.querySelectorAll('.meta-info p, .detail p').forEach(function (p) {
        var span = p.querySelector('span');
        var a = p.querySelector('a[href]');
        if (span && a && /terbaru|latest/i.test(F.text(span))) {
          var s2 = F.slugOf(F.abs(a.getAttribute('href')));
          if (s2) latestSlug = s2;
        }
      });
    }

    // Episode navigation links inside .player-action (.action-right):
    // <a>EPS 1</a> <li>EPS 2</li> <a>EPS 3</a>. The anchor before the current
    // episode is the previous one, the anchor after it the next one. These
    // always win for prev; watch.next is a fallback for next.
    var epsSlugs = [];
    document.querySelectorAll('.player-action .action-right li a[href]').forEach(function (a) {
      var s3 = F.slugOf(F.abs(a.getAttribute('href')));
      if (s3) epsSlugs.push(s3);
    });
    if (epsSlugs.length > 0 && !prevSlug) prevSlug = epsSlugs[0];
    if (epsSlugs.length > 1 && !nextSlug) nextSlug = epsSlugs[1];

    // When there is no explicit Play Awal / Play Terbaru button (episode
    // pages), fall back to the season list order.
    if (flatten.length > 0) {
      if (!firstSlug) firstSlug = flatten[0].slug || '';
      if (!latestSlug) latestSlug = flatten[flatten.length - 1].slug || '';
    }

    var sources = gatherSources();
    var subtitles = F.subtitlesFromHtml();

    F.send({
      schema: 'freepi.detail.v1',
      ok: true,
      source: F.sourceInfo(),
      data: {
        title: title,
        year: year,
        poster: poster,
        backdrop: poster,
        synopsis: synopsis(),
        jsonLd: ld || {},
        meta: meta,
        infoTags: info,
        watch: watch || {},
        ogType: ogType,
        rating: ratingVal,
        ratingCount: ratingCount,
        quality: quality,
        durationText: durationText,
        genres: genres,
        countries: countries,
        directors: directors,
        cast: cast,
        imdbId: imdbId,
        imdbUrl: (ld && ld.sameAs) || '',
        trailerUrl: trailer,
        published: published,
        uploaded: uploaded,
        translator: translator,
        redirectUrl: F.abs(redirectUrl),
        postId: watch.id !== undefined ? String(watch.id) : '',
        seasons: sd.seasons,
        episodes: flatten,
        totalSeasons: watch.total_season !== undefined ? watch.total_season : sd.seasons.length,
        totalEpisodes: watch.total_eps !== undefined ? watch.total_eps : flatten.length,
        currentSeason: currentSeason,
        currentEpisode: currentEp,
        firstSlug: firstSlug,
        latestSlug: latestSlug,
        nextSlug: nextSlug || '',
        prevSlug: prevSlug || '',
        seriesSlug: seriesSlug,
        seriesTitle: seriesTitle,
        sources: sources,
        subtitles: subtitles,
        related: related()
      }
    });
  });

  function splitValues(v) {
    return String(v || '').split(/[,/]/).map(function (s) { return s.trim(); })
      .filter(function (s) { return s && s !== 'N/A' && s.length < 60; });
  }
})();`;

  // extensions/lk21/index.js
  var programs = { home: home_default, list: list_default, detail: detail_default };
  function nativeFetch(url, init = {}) {
    const bridge = globalThis.MovieSourceHttp;
    const runtimeFetch = globalThis.FreepiRuntime && globalThis.FreepiRuntime.fetch;
    if (bridge && typeof bridge.request === "function") {
      const payload = {
        url: String(url),
        method: String(init.method || "GET").toUpperCase(),
        headers: init.headers || {},
        body: init.body == null ? null : String(init.body)
      };
      let result;
      try {
        result = JSON.parse(bridge.request(JSON.stringify(payload)));
      } catch (error) {
        return Promise.reject(new Error("LK21 native request failed: " + (error && error.message || error)));
      }
      if (result.error) return Promise.reject(new Error(result.error));
      const body = String(result.body || "");
      return Promise.resolve({
        ok: result.status >= 200 && result.status < 300,
        status: result.status,
        url: result.url || url,
        text: async () => body
      });
    }
    if (typeof runtimeFetch === "function") return runtimeFetch(url, init);
    if (typeof globalThis.fetch === "function") return globalThis.fetch(url, init);
    return Promise.reject(new Error("No HTTP transport is available"));
  }
  var base = "https://tv13.lk21official.cc";
  async function extract(fetchImpl, kind, path) {
    const url = new URL(path, base).href;
    const response = await fetchImpl(url, { headers: { referer: base + "/" } });
    if (!response.ok) {
      throw Object.assign(new Error(`LK21 returned HTTP ${response.status}`), { code: response.status === 403 ? "ACCESS_BLOCKED" : "NETWORK" });
    }
    const html = await response.text();
    const doc = new DOMParser().parseFromString(html, "text/html");
    const location = new URL(response.url || url);
    return new Promise((resolve, reject) => {
      const w = {
        location,
        __freepiRequestUrl: url,
        __freepiRequestId: "runtime",
        FreepiScrapeBridge: {
          postMessage: (_ignore, raw) => {
            const value = JSON.parse(raw);
            if (value.ok) resolve(value);
            else reject(Object.assign(new Error(value.error.message), { code: value.error.code }));
          }
        }
      };
      new Function("window", "document", "location", common_default + "\n" + programs[kind])(w, doc, location);
    });
  }
  function createLk21(fetchImpl) {
    return {
      home: () => extract(fetchImpl, "home", "/"),
      list: (args) => extract(fetchImpl, "list", args && args.url || "/"),
      search: (args) => extract(fetchImpl, "list", "/search?" + new URLSearchParams({ s: args && args.query || "" })),
      stream: async () => {
        throw Object.assign(new Error("LK21 playback must be resolved by the dedicated browser stream pipeline."), { code: "ACCESS_BLOCKED" });
      },
      detail: async (args) => {
        let url = args && args.url || "/";
        for (let i = 0; i < 4; i++) {
          const result = await extract(fetchImpl, "detail", url);
          if (!result.data.redirectUrl) return result;
          url = new URL(result.data.redirectUrl, url.startsWith("http") ? url : base).href;
        }
        throw new Error("LK21 detail redirected too many times");
      }
    };
  }
  var lk21 = createLk21(nativeFetch);
  globalThis.MovieSources = globalThis.MovieSources || {};
  globalThis.MovieSources.lk21 = lk21;
})();
