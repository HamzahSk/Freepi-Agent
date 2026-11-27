/**
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
      if (full && full.length > 20) return String(full).replace(/\s+/g, ' ').trim();
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
      if (/youtube\.com|youtu\.be|recaptcha|googlevideo\.com|doubleclick|googleads/i.test(u)) return;
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
        maxQuality: /^\d{3,4}$/.test(rel || '') ? rel : qualities[0] || null,
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
      if (!server) server = (a.className || '').trim().split(/\s+/)[0] || 'Provider';
      var qualities = [];
      if (li) {
        li.querySelectorAll('.label').forEach(function (el) {
          var t = F.text(el);
          if (/^\d{3,4}$/.test(t) && qualities.indexOf(t) < 0) qualities.push(t);
        });
      }
      add(raw, server, qualities, a.getAttribute('rel') || '');
    });

    // Embeds embedded directly on the page (excluding the already-added main one).
    document.querySelectorAll('iframe[src]').forEach(function (iframe) {
      var src = iframe.getAttribute('src') || '';
      if (!/playeriframe|iframe\.php|embed|player\.php|filemoon|turbovid|hownet|luxubu|hydrax|short\.ink|videonode/i.test(src)) return;
      if (iframe.id === 'main-player' && maybeAlreadyAdded(src)) return;
      add(src.split('?')[0], 'Embed ' + (out.length + 1), []);
    });

    return out;
  }

  function maybeAlreadyAdded(url) {
    var u = F.abs(url);
    if (!u) return false;
    var base = u.split('?')[0].replace(/\/+$/, '');
    var existing = collectionContains(u) || collectionContains(base);
    return existing;
  }

  var __sourceUrls = [];
  function collectionContains(u) {
    for (var i = 0; i < __sourceUrls.length; i++) {
      if (__sourceUrls[i] === u) return true;
      var a = __sourceUrls[i].split('?')[0].replace(/\/+$/, '');
      if (a === u.split('?')[0].replace(/\/+$/, '')) return true;
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

    // Hero poster: the captured theme puts it inside the `.detail`/`.movie-info`
    // meta block. `img[itemprop=image]` is intentionally NOT used here - every
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
    if (!year) year = (title.match(/\((\d{4})\)/) || [])[1] || '';

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
      var imdbMatch = /from\s+([\d.,]+)/i.exec(meta['IMDb'] || '');
      if (imdbMatch) ratingCount = imdbMatch[1];
    }
    if (!ratingCount) {
      var big = info.filter(function (t) { return /^\d{3,}$/.test(t); })[0];
      if (!big) var big = /[\d.,]+/.exec(meta['Votes'] || '');
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
      if (!durationText && (/\d{1,2}:\d{2}|\d+\s*(?:h|jam|min|menit)\b/i.test(t) || /^\d{3,4}$/.test(t))) {
        if (!/^\d{3,4}$/.test(t)) durationText = t;
      }
    });
    if (!durationText) durationText = meta['Durasi'] || '';
    if (!durationText && watch && watch.runtime) durationText = String(watch.runtime);
    if (!durationText && ld && ld.duration) durationText = String(ld.duration);
    // "1h 42m" -> "01:42" style from watch.runtime is a nicer durationText.
    if ((!durationText || !/\d/.test(durationText)) && watch && watch.runtime) {
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
    var tt = /tt\d+/.exec(imdbStr);
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
      if (/play\s*awal|mulai\s*putar/i.test(t) && !firstSlug) firstSlug = F.slugOf(F.abs(href)) || '';
      if (/play\s*terbaru|episode\s*terbaru/i.test(t) && !latestSlug) latestSlug = F.slugOf(F.abs(href)) || '';
    });
    // Series hub “Terbaru: Season 1 Episode 8” info row -> latestSlug.
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
})();