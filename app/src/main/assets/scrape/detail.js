/**
 * Movie/series detail page extraction. Produces freepi.detail.v1.
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
          if (/movie|tvseries|creativework|videoobject/i.test(o['@type'] || '')) {
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

    // Old theme: #movie-detail .content > div with h2 label + h3 values
    document.querySelectorAll('#movie-detail .content > div').forEach(function (row) {
      var label = F.text(row.querySelector('h2'));
      var vals = [];
      row.querySelectorAll('h3').forEach(function (h3) { var t = F.text(h3); if (t) vals.push(t); });
      if (label && vals.length) meta[label] = vals.join(', ');
    });

    // New theme: .detail p with a <span> label
    document.querySelectorAll('.detail p, .meta-info p').forEach(function (p) {
      if (p.closest('.synopsis, .trailer-series')) return;
      var span = p.querySelector('span');
      var label = F.text(span).replace(/:$/, '').trim();
      if (!label) return;
      var clone = p.cloneNode(true);
      var s = clone.querySelector('span');
      if (s) s.remove();
      var value = F.text(clone);
      if (value && !meta[label]) meta[label] = value;
    });

    return meta;
  }

  function infoTags() {
    var out = [];
    document.querySelectorAll('.info-tag span, .tags span, .tag-item')
      .forEach(function (el) { var t = F.text(el); if (t) out.push(t); });
    return out;
  }

  function watchData() {
    var el = document.querySelector('#watch-history-data');
    if (!el) return null;
    try { return JSON.parse(el.textContent || ''); } catch (e) { return null; }
  }

  function tagList() {
    var tags = [];
    document.querySelectorAll('.tag-list .tag a, .tag-list a, [itemprop="genre"]').forEach(function (a) {
      var href = a.getAttribute('href') || '';
      var type = href.split('/').filter(Boolean)[0] || '';
      tags.push({ type: type, name: F.text(a) });
    });
    return tags;
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

    document.querySelectorAll(
      '#player-list a[data-url], #player-list li a, #loadProviders li a, ' +
      '#loadProviders a[target="iframe"], .player-list li a, .player-select li a, ' +
      '#player-list a[data-iframe]'
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

    // Embeds embedded directly on the page.
    document.querySelectorAll('iframe[src]').forEach(function (iframe) {
      var src = iframe.getAttribute('src') || '';
      if (/playeriframe|iframe\.php|embed|player\.php|filemoon|turbovid|hownet|luxubu|hydrax|short\.ink/i.test(src)) {
        add(src, 'Embed ' + (out.length + 1), []);
      }
    });

    return out;
  }

  function related() {
    var scope = document.querySelector('#related, .related, .related-movies, .widget[data-type="related"]') ||
      document.querySelector('.widget[data-type]');
    var cards = scope ? F.collectCards(scope) : [];
    if (!cards.length && document.querySelector('.widget[data-type]')) {
      cards = F.collectCards(document.querySelector('.widget[data-type]'));
    }
    if (!cards.length) cards = F.collectCards(document).slice(0, 24);
    return cards.slice(0, 24);
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
    var tags = tagList();
    var info = infoTags();
    var watch = watchData();

    var ogTitle = '';
    var og = document.querySelector('meta[property="og:title"]');
    if (og) ogTitle = og.getAttribute('content') || '';

    var title = F.cleanTitle(
      (watch && watch.title ? String(watch.title) : '') ||
      F.text(document.querySelector('.movie-info h1, .detail h1, .post-header h1, h1')) ||
      (ld && ld.name ? String(ld.name) : '') ||
      ogTitle
    );

    var posterRel = document.querySelector(
      '#movie-detail img.img-thumbnail, .detail img, img[itemprop="image"], .content-poster img'
    );
    var poster = '';
    if (posterRel) poster = posterRel.getAttribute('data-src') || posterRel.getAttribute('src') || '';
    if (!poster) {
      var ogImg = document.querySelector('meta[property="og:image"]');
      if (ogImg) poster = ogImg.getAttribute('content') || '';
    }
    if (!poster && ld) poster = ld.image || '';

    var year = watch && watch.year ? String(watch.year) : '';
    if (!year && ld) year = String(ld.dateCreated || '').slice(0, 4) || String(ld.datePublished || '').slice(0, 4);
    if (!year) year = (title.match(/\((\d{4})\)/) || [])[1] || '';
    if (!year) {
      var h2 = F.text(document.querySelector('.movie-info h2, h2'));
      year = h2.replace(/[()]/g, '').trim().match(/^(19|20)\d{2}$/) ? h2.replace(/[()]/g, '').trim() : '';
    }

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
      if (big) ratingCount = big;
    }

    var quality = info.filter(function (t) {
      return /WEBDL|WEB-DL|WEBRip|BluRay|BDRip|HDCAM|HDTS|HDRip|CAM|DVD|1080p|720p|480p/i.test(t);
    })[0] || '';
    if (!quality) {
      var qSel = document.querySelector('.info-tag span, .detail .quality, [itemprop="videoQuality"]');
      if (qSel) quality = F.text(qSel);
    }
    if (!quality) quality = F.text(document.querySelector('.movie-quality, .quality-label')) || '';

    var genres = tags.filter(function (t) { return t.type === 'genre'; }).map(function (t) { return t.name; });
    if (!genres.length && ld && Array.isArray(ld.genre)) genres = ld.genre.slice();
    if (!genres.length && meta['Genre']) genres = meta['Genre'].split(',').map(function (s) { return s.trim(); });

    var countries = tags.filter(function (t) { return t.type === 'country'; }).map(function (t) { return t.name; });
    if (!countries.length && meta['Negara']) countries = meta['Negara'].split(/[,/]/).map(function (s) { return s.trim(); });

    var directors = ldNames(ld && ld.director);
    if (!directors.length && meta['Sutradara']) {
      directors = meta['Sutradara'].split(/[,/]/).map(function (s) { return s.trim(); });
    }

    var cast = [];
    if (meta['Bintang film'] || meta['Bintang Film']) {
      cast = (meta['Bintang film'] || meta['Bintang Film'] || '').split(',').map(function (s) { return s.trim(); });
    }

    var durationText = '';
    info.forEach(function (t) {
      if (!durationText && /\d{1,2}:\d{2}|\d+\s*(?:h|jam|min|menit)\b/i.test(t)) durationText = t;
    });
    if (!durationText) durationText = meta['Durasi'] || '';
    if (!durationText && watch && watch.runtime) durationText = String(watch.runtime);
    if (!durationText && ld && ld.duration) durationText = String(ld.duration);

    var imdbId = '';
    var imdbStr = (meta['IMDb'] || '') + ' ' + (ld && ld.sameAs || '') + ' ' +
      document.documentElement.outerHTML.slice(0, 300000);
    var tt = /tt\d+/.exec(imdbStr);
    if (tt) imdbId = tt[0];

    var trailer = '';
    var trailerEl = document.querySelector(
      '.trailer-series iframe, .simple-box iframe, a.fancybox[href*="youtube"], ' +
      'a[href*="youtube.com/watch"], a[href*="youtu.be"], iframe[src*="youtube.com/embed"]'
    );
    if (trailerEl) trailer = trailerEl.getAttribute('src') || trailerEl.getAttribute('href') || '';

    var published = meta['Diterbitkan'] || meta['Release'] || (ld && ld.datePublished) || '';
    var uploaded = meta['Diunggah'] || meta['Updated'] || '';
    var translator = meta['Penerjemah'] || '';

    var redirectUrl = '';
    var openNow = document.querySelector('#openNow');
    if (openNow && openNow.getAttribute('href')) redirectUrl = openNow.getAttribute('href');

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
        sources: gatherSources(),
        subtitles: F.subtitlesFromHtml(),
        related: related()
      }
    });
  });
})();