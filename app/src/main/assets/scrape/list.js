/**
 * Catalog listing / search result extraction. Produces freepi.list.v1.
 */
(function () {
  'use strict';
  var F = window.__freepi;

  function isEmptyHint() {
    var body = '';
    try { body = (document.body && document.body.innerText || '').slice(0, 3000); } catch (e) {}
    return /tidak ditemukan|tidak ada (?:hasil|film)|no results?|nothing found|not found|maaf,?\s*(?:hasil|film)|searched for.*no results/i
      .test(body) || !!document.querySelector('.no-results, .search-no-results, #no-results, .not-found');
  }

  function collectGrid() {
    var scope = document.querySelector('#post-container, .gallery-grid, .movies-list, .video-list-wrapper, main') || document.body;
    var items = F.collectCards(scope);
    if (!items.length) items = F.collectCards(document).slice(0, 200);
    return { items: items, scope: scope };
  }

  function collectFiltered() {
    // Captured theme: filter/archive pages list <li> cards under .video-list / .column.
    var out = [];
    var seen = Object.create(null);
    document.querySelectorAll('.video-list li, .video-list-wrapper li, .column li, .movie-list li')
      .forEach(function (el) {
        if (el.closest('.footer, footer, .mob-list-eps, .episode')) return;
        var a = el.querySelector('a[href]') || (el.tagName === 'A' ? el : null);
        if (!a) return;
        var href = a.getAttribute('href') || '';
        var slug = F.slugOf(F.abs(href));
        if (!slug || seen[slug]) return;
        var img = a.querySelector('img');
        if (!img && !el.querySelector('.video-info')) return;
        seen[slug] = 1;
        out.push({
          slug: slug,
          title: F.cleanTitle(F.text(el.querySelector('.video-title, .poster-title, h3, h2')) || (img && img.getAttribute('alt')) || F.cleanTitle(F.text(a))),
          url: F.abs(href),
          poster: F.abs(img && (img.getAttribute('data-src') || img.getAttribute('src'))),
          rating: F.num(F.text(el.querySelector('.rating'))),
          quality: F.text(el.querySelector('.label-HD, .quality')) || null,
          duration: F.text(el.querySelector('.video-year, .year')) || null,
          genres: [],
          year: F.text(el.querySelector('.video-year, .year')) || null
        });
      });
    return out;
  }

  F.finish(function () {
    var grid = collectGrid();
    var items = grid.items;
    if (!items.length) items = collectFiltered();

    var page = F.pageFromUrl();
    var pagination = F.paginationInfo(page);
    var emptyHint = items.length === 0 && isEmptyHint();

    var title = F.text(document.querySelector('h1')) ||
      F.text(document.querySelector('.page-title')) || '';

    F.send({
      schema: 'freepi.list.v1',
      ok: true,
      source: F.sourceInfo(),
      data: {
        title: title,
        items: items,
        page: pagination.page,
        totalPages: pagination.totalPages,
        nextPageUrl: pagination.nextPageUrl,
        emptyHint: emptyHint
      }
    });
  });
})();