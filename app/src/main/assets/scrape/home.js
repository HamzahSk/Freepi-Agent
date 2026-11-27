/**
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
})();