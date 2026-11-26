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

    // Latest from the main post grid (list-style home).
    var mainGrid = document.querySelector('#post-container, .gallery-grid, main');
    if (mainGrid && !sections.latest.length) merge('latest', F.collectCards(mainGrid).slice(0, 20));

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