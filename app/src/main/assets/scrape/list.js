/**
 * Catalog listing / search result extraction. Produces freepi.list.v1.
 */
(function () {
  'use strict';
  var F = window.__freepi;

  function isEmptyHint() {
    var body = '';
    try { body = (document.body && document.body.innerText || '').slice(0, 2000); } catch (e) {}
    return /tidak ditemukan|tidak ada (?:hasil|film)|no results?|nothing found|not found|maaf,?\s*(?:hasil|film)/i
      .test(body) || !!document.querySelector('.no-results, .search-no-results, #no-results');
  }

  F.finish(function () {
    var scope = document.querySelector('#post-container, .gallery-grid, .movies-list, main') || document.body;
    var items = F.collectCards(scope);
    if (!items.length) items = F.collectCards(document).slice(0, 200);

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