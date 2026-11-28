import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { chromium } from 'playwright-core';
import { stringify } from 'devalue';

const live = process.argv.includes('--live');
const root = new URL('../../app/src/test/resources/', import.meta.url);
const read = path => readFile(new URL(path, root), 'utf8');
const revision = (await read('extensions/REVISION')).trim();
const base = `https://raw.githubusercontent.com/HamzahSk/movie-source/${revision}/`;
// Live mode really downloads and verifies every advertised module. Fixtures pin the same revision.
const download = path => execFileSync('curl', ['--fail', '--silent', '--show-error', '--max-time', '40', base + path], { encoding: 'utf8', maxBuffer: 12 * 1024 * 1024 });
const manifest = JSON.parse(live ? download('manifest.json') : await read('extensions/manifest.json'));
const code = {};
for (const [id, module] of Object.entries(manifest.modules)) {
  code[id] = live ? download(module.path) : await read('extensions/' + module.path);
  assert.equal(createHash('sha256').update(code[id]).digest('hex'), module.sha256, `${id} SHA-256`);
}
console.log(`registry ${revision}: ${manifest.sources.length} extensions, ${Object.keys(code).length} verified modules (${live ? 'downloaded' : 'pinned fixture'})`);
function bundle(roots) {
  const seen = new Set(), visiting = new Set(), output = [];
  function visit(id) {
    if (seen.has(id)) return;
    assert(!visiting.has(id)); visiting.add(id);
    for (const dependency of manifest.modules[id].dependencies || []) visit(dependency);
    visiting.delete(id); seen.add(id); output.push(code[id]);
  }
  roots.forEach(visit); return output.join('\n');
}
const fixtures = {};
for (const name of ['trending','discover_movie','discover_tv','search_multi','movie_27205','tv_1396','tv_1396_season1']) fixtures[name] = JSON.parse(await read(`extensions/fixtures/movy/${name}.json`));
for (const name of ['home','list']) fixtures['mb_' + name] = JSON.parse(await read(`extensions/fixtures/moviebox/${name}.json`));
const lk = {};
for (const kind of ['movie','series','episode']) lk[kind] = await read(`lk21/captured_${kind}.html`);
const subject = { subjectId: '42', title: 'Fixture Movie', detailPath: 'fixture-movie', subjectType: 1, duration: 7200, genre: 'Drama', cover: { url: 'https://cdn.example/poster.jpg' } };
const movieboxPage = (series, id) => `<script id="__NUXT_DATA__" type="application/json">${stringify({ data: { detail: { data: { subject: { ...subject, subjectId: id, detailPath: series ? "fixture-series" : "fixture-movie", subjectType: series ? 2 : 1 }, resource: { seasons: series ? [{ se: 1, maxEp: 2, allEp: '1,2' }] : [] } } } } })}</script>`;
const requests = [];
function fixtureRequest(raw) {
  const req = JSON.parse(raw), url = new URL(req.url); requests.push(req);
  const response = body => JSON.stringify({ status: 200, url: req.url, body: typeof body === 'string' ? body : JSON.stringify(body) });
  if (url.hostname.includes('lk21')) {
    // Captured detail pages contain genuine recommendation cards; add an explicitly synthetic search container.
    const body = url.pathname.includes('episode') ? lk.episode : url.pathname.includes('series') ? lk.series : lk.movie;
    return response(url.searchParams.has('s') ? body.replace('<body', '<body class="search-results"') : body);
  }
  if (url.hostname === 'db.wecollege.net' && url.pathname.startsWith('/3/')) {
    const p = url.pathname;
    const key = p.includes('/trending/') ? 'trending' : p.includes('/discover/movie') ? 'discover_movie' : p.includes('/discover/tv') ? 'discover_tv' : p.includes('/search/') ? 'search_multi' : p.includes('/season/') ? 'tv_1396_season1' : p.includes('/movie/') ? 'movie_27205' : 'tv_1396';
    return response(fixtures[key]);
  }
  if (url.hostname === 'movieboxhd.net') return response(movieboxPage(url.pathname.includes('series'), url.searchParams.get('id')));
  if (url.hostname === 'h5-api.aoneroom.com') {
    if (url.pathname.endsWith('/home')) return response(fixtures.mb_home);
    if (url.pathname.endsWith('/subject/filter')) return response(fixtures.mb_list);
    if (url.pathname.endsWith('/subject/search')) return response({ code: 0, data: { items: [subject], pager: { page: 1, perPage: 24, totalCount: 48 } } });
    if (url.pathname.endsWith('/subject/play')) return response({ code: 0, data: { streams: [{ url: 'https://cdn.example/movie.mp4', format: 'mp4' }], captions: [{ url: 'https://cdn.example/en.vtt', language: 'en' }] } });
  }
  if (url.hostname === 'api.wecollege.net') return response(url.pathname === '/seed' ? { seed: 'fixture' } : 'ENCRYPTED_FIXTURE');
  if (url.hostname === 'enc-dec.app') return response({ status: 200, result: { sources: [{ url: 'https://cdn.example/master.m3u8', quality: '1080p' }], subtitles: [{ url: 'https://cdn.example/id.vtt', language: 'id' }] } });
  if (url.hostname === 'cdn.example') return response('#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080\n1080.m3u8\n');
  if (url.hostname === 'ok.ru') return response(`<div data-options="${JSON.stringify({ videos: [{ url: 'https://cdn.example/ok.mp4' }] }).replaceAll('"', '&quot;')}"></div>`);
  if (url.hostname === 'www.dailymotion.com') return response('<script>window.__PLAYER_CONFIG__ = {"metadata":{"qualities":{"720":{"url":"https://cdn.example/dm.mp4","type":"video/mp4"}}}};</script>');
  throw new Error(`Unrouted fixture request: ${req.url}`);
}
let transport = fixtureRequest;
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] });
const context = await browser.newContext();
const page = await context.newPage();
// Synchronous XHR emulates Android's synchronous @JavascriptInterface without fetch/Node APIs in extensions.
await page.route('https://runtime.example/**', route => {
  if (route.request().method() === 'POST') {
    try { return route.fulfill({ contentType: 'application/json', body: transport(route.request().postData()) }); }
    catch (e) { return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ error: e.message }) }); }
  }
  return route.fulfill({ contentType: 'text/html', body: '<html></html>' });
});
async function install(roots) {
  await page.goto('https://runtime.example/');
  await page.evaluate(() => {
    window.MovieSourceHttp = { request(raw) { const xhr = new XMLHttpRequest(); xhr.open('POST', '/bridge', false); xhr.send(raw); return xhr.responseText; } };
    window.fetch = undefined; window.require = undefined;
  });
  await page.evaluate(bundle(roots));
}
async function operation(id, name, args = {}, ok = true) {
  const value = await page.evaluate(async ({ id, name, args }) => {
    try { return await MovieSources[id][name](args); }
    catch (error) { return { schema: 'freepi.error.v1', ok: false, error: { code: error.code || 'JS_ERROR', message: error.message } }; }
  }, { id, name, args });
  assert.equal(value.ok, ok, `${id}.${name}: ${JSON.stringify(value).slice(0, 250)}`);
  assert.equal(value.schema, ok ? `freepi.${['search','list'].includes(name) ? 'list' : name}.v1` : 'freepi.error.v1');
  return value.data;
}
try {
  for (const id of ['lk21','moviebox','movy','lk21','movy']) {
    const source = manifest.sources.find(source => source.id === id);
    await install([...(source.dependencies || []), source.runtime.module]);
    assert.deepEqual((await page.evaluate(id => Object.keys(MovieSources[id]).sort(), id)), [...source.operations].sort());
    const home = await operation(id, 'home'); assert(Object.values(home.sections).flat().length > 0);
    const search = await operation(id, 'search', { query: 'inception' }); assert(search.items.length > 0);
    await operation(id, 'list', id === 'moviebox' ? { type: 'series', genre: 'Drama', page: 2 } : { url: id === 'movy' ? 'https://movy.example/list?type=movie&page=2' : '/page/2/' });
    const movie = id === 'movy' ? 'movy:movie:27205' : id === 'moviebox' ? 'https://movieboxhd.net/moviedetail/fixture-movie?id=42' : '/fixture-movie/';
    const series = id === 'movy' ? 'movy:tv:1396' : id === 'moviebox' ? 'https://movieboxhd.net/moviedetail/fixture-series?id=43' : '/fixture-series/';
    await operation(id, 'detail', { url: movie });
    const tv = await operation(id, 'detail', { url: series }); assert(tv.seasons.length > 0);
    if (source.operations.includes('episodes')) await operation(id, 'episodes', { url: series });
    if (id === 'lk21') await operation(id, 'stream', { url: movie }, false);
    else {
      const media = await operation(id, 'stream', { url: id === 'moviebox' ? 'moviebox:42:s1:e1' : movie, source: { provider: 'Miami' } });
      assert(media.media.length > 0);
      if (id === 'movy') {
        await operation(id, 'detail', { url: 'movy:tv:1396:s1:e1' });
        await operation(id, 'stream', { url: 'movy:tv:1396:s1:e1', source: { provider: 'Miami' } });
      }
    }
    console.log(`${id}: all ${source.operations.length} operations exercised; catalog/movie/series verified${id === 'lk21' ? '; stream requires legacy browser pipeline (expected ACCESS_BLOCKED)' : '; stream media resolved'}`);
  }
  await install(manifest.extractors.map(e => e.module));
  const result = await page.evaluate(async () => {
    const registry = FreepiExtractors;
    const out = {};
    for (const id of ['p2p','hydrax']) out[id] = (await registry.resolve(id, { payload: { sources: [{ file: 'https://cdn.example/test.m3u8', type: 'hls' }] }, pageUrl: 'https://provider.example/' })).media.length;
    for (const [id, url] of [['direct','https://cdn.example/test.mp4'],['okru','https://ok.ru/video/123'],['dailymotion','https://www.dailymotion.com/video/x123']]) out[id] = (await registry.resolve(id, { url })).media.length;
    const failed = await registry.resolve('videasy', { server: 'invalid' }).catch(error => error.code);
    out.failure = failed;
    out.afterFailure = (await registry.resolve('direct', { url: 'https://cdn.example/after.mp4' })).media.length;
    out.ids = registry.list().map(e => e.id).sort(); return out;
  });
  for (const id of ['p2p','hydrax','direct','okru','dailymotion']) assert(result[id] > 0, id);
  assert.equal(result.afterFailure, 1); assert(result.failure);
  assert.equal(result.ids.length, 6);
  console.log('shared registry: all six extractors, dependency deduplication, failure isolation verified');
  // Genuine live site probes via curl (no credentials or access-control workarounds).
  if (live) {
    const reports = [];
    for (const [name, url] of [['lk21','https://tv13.lk21official.cc/'],['moviebox','https://h5-api.aoneroom.com/wefeed-h5api-bff/home?host=movieboxhd.net'],['movy','https://db.wecollege.net/3/trending/all/week'],['videasy','https://api.wecollege.net/seed']]) {
      try {
        const body = execFileSync('curl', ['-L','--silent','--show-error','--max-time','30','-w','\nHTTP_STATUS:%{http_code}',url], { encoding:'utf8', maxBuffer: 8*1024*1024 });
        const status = body.match(/HTTP_STATUS:(\d+)/)?.[1];
        reports.push({ name, status, bytes: body.length, json: (() => { try { JSON.parse(body.substring(0,body.lastIndexOf('\nHTTP_STATUS:'))); return true; } catch { return false; } })() });
      } catch (error) { reports.push({ name, error: String(error.message).slice(0,150) }); }
    }
    console.log('LIVE endpoint probes (not proof of playback):', JSON.stringify(reports));
    transport = raw => {
      const request = JSON.parse(raw);
      assert.match(request.url, /^https:\/\//);
      const args = ['--silent','--show-error','--max-time','20','-L','-X',request.method || 'GET','-w','\nHTTP_STATUS:%{http_code}',request.url];
      for (const [key,value] of Object.entries(request.headers || {})) args.push('-H', `${key}: ${value}`);
      if (request.body) args.push('--data-binary', request.body);
      const output = execFileSync('curl', args, { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
      const separator = output.lastIndexOf('\nHTTP_STATUS:');
      return JSON.stringify({ status: Number(output.slice(separator + 13)), url: request.url, body: output.slice(0, separator) });
    };
    const liveOperations = [];
    for (const source of manifest.sources) {
      const id = source.id;
      await install([...(source.dependencies || []), source.runtime.module]);
      async function attempt(name, args = {}) {
        const result = await page.evaluate(async ({ id, name, args }) => {
          try { return await MovieSources[id][name](args); }
          catch (error) { return { ok: false, error: { code: error.code || 'JS_ERROR', message: error.message } }; }
        }, { id, name, args });
        const report = { id, operation: name, ok: result.ok, ...(result.ok ? {} : { error: result.error }) };
        liveOperations.push(report); console.log('LIVE operation:', JSON.stringify(report)); return result;
      }
      const home = await attempt('home');
      await attempt('search', { query: 'inception' });
      const list = await attempt('list', id === 'movy' ? { url: 'https://movy.example/list?type=movie' } : id === 'moviebox' ? { type: 'movie', perPage: 3 } : { url: '/latest/' });
      const movie = id === 'movy' ? 'movy:movie:27205' : list.data?.items?.[0]?.slug || 'slightest-touch-2026';
      const series = id === 'movy' ? 'movy:tv:1396' : home.data?.sections?.series?.[0]?.slug;
      await attempt('detail', { url: movie });
      if (series) await attempt('detail', { url: series });
      if (source.operations.includes('episodes')) await attempt('episodes', { url: series || movie });
      await attempt('stream', { url: movie, source: { provider: 'Miami' } });
    }
    reports.push({ liveOperations });
    await writeFile(new URL('../../app/build/extension-live-report.json', import.meta.url), JSON.stringify(reports, null, 2));
  }
  console.log(`PASS: source switching/reinitialization, ${requests.length} bridged fixture requests; no native ESM or Node runtime`);
} finally { await browser.close(); }
