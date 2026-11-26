/**
 * Freepi Stream - Playwright verification harness.
 *
 * Runs the exact extraction scripts shipped in app/src/main/assets/scrape
 * against realistic page fixtures rendered by real desktop Chromium, and
 * (optionally) probes the live source host to record its actual behaviour.
 *
 * Usage:
 *   node run.mjs fixtures           # local fixture verification (default)
 *   node run.mjs live               # probe the live host (records challenge)
 *   node run.mjs all
 *
 * Output: ./out/<page>.payload.json  +  a summary table on stdout.
 */
import { chromium } from 'playwright';
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)));
const ASSETS = resolve(ROOT, '../../app/src/main/assets/scrape');
const FIXTURES = join(ROOT, 'fixtures');
const OUT = join(ROOT, 'out');

const commonJs = readFileSync(join(ASSETS, 'common.js'), 'utf8');

function pageScript(name) {
  return readFileSync(join(ASSETS, `${name}.js`), 'utf8');
}

function wrap(requestId, url, script) {
  return `window.__freepiRequestId=${JSON.stringify(requestId)};` +
    `window.__freepiRequestUrl=${JSON.stringify(url)};\n` +
    commonJs + '\n' + script;
}

async function extractOnFixture(browser, fixture, scriptName, requestId) {
  mkdirSync(OUT, { recursive: true });
  const page = await browser.newPage();
  const fileUrl = 'file://' + join(FIXTURES, fixture);
  // file:// pages with relative images may never fire the full 'load' event;
  // domcontentloaded + a small settle is enough (the WebView path injects after
  // its own page_finished callback and a 350ms settle).
  await page.goto(fileUrl, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(400);
  await page.exposeFunction('__freepiBridgePost', () => {}).catch(() => {});
  // The extraction scripts call window.FreepiScrapeBridge.postMessage(id, payload)
  await page.evaluate(() => {
    const holder = window;
    Object.defineProperty(holder, '__freepiPayload', {
      value: null, writable: true, configurable: true
    });
    holder.FreepiScrapeBridge = {
      postMessage: (id, payload) => { holder.__freepiPayload = payload; }
    };
  });
  const bundled = wrap(requestId, fileUrl, pageScript(scriptName));
  const started = Date.now();
  await page.evaluate(bundled);
  const payload = await page.waitForFunction(() => window.__freepiPayload && true, { timeout: 20000 })
    .then(() => page.evaluate(() => JSON.parse(window.__freepiPayload)))
    .catch(async () => {
      const snapshot = await page.evaluate(() => ({
        url: location.href, title: document.title,
        body: (document.body.innerText || '').slice(0, 400),
        payload: window.__freepiPayload,
      }));
      return { error: 'payload_not_delivered', snapshot };
    });
  if (payload && payload.error) {
    return { fixture, ok: false, elapsedMs: Date.now() - started, payload };
  }
  writeFileSync(join(OUT, `${fixture.replace('.html', '')}.payload.json`), JSON.stringify(payload, null, 2));
  await page.close();
  return { fixture, ok: true, elapsedMs: Date.now() - started, payload };
}

async function verifyFixtures() {
  mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch({ headless: true });
  const tasks = [
    ['home.html', 'home', 'home-fixture'],
    ['list.html', 'list', 'list-fixture'],
    ['detail.html', 'detail', 'detail-fixture'],
    ['embed.html', 'stream', 'embed-fixture'],
  ];
  const results = [];
  for (const [fixture, script, id] of tasks) {
    const r = await extractOnFixture(browser, fixture, script, id);
    results.push(r);
    const ok = r.ok ? 'OK' : 'FAIL';
    console.log(`[${ok}] ${fixture.padEnd(12)} ${(r.payload && r.payload.schema) || r.payload?.error || ''}` +
      `  ${r.elapsedMs}ms`);
  }
  await browser.close();
  return results;
}

async function probeLive() {
  // No bypassing: record what the host actually answers to a real Chromium.
  const browser = await chromium.launch({ headless: true });
  const urls = [
    'https://tv13.lk21official.cc/',
    'https://tv13.lk21official.cc/page/2/',
    'https://tv13.lk21official.cc/genre/action/',
    'https://tv13.lk21official.cc/search.php?s=venom',
  ];
  for (const url of urls) {
    const page = await browser.newPage({ locale: 'id-ID' });
    const response = await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 45000 })
      .catch(() => null);
    await page.waitForTimeout(6000);
    const info = await page.evaluate(() => ({
      title: document.title,
      text: (document.body?.innerText || '').slice(0, 160),
      cards: document.querySelectorAll('article, .mega-item, .search-item').length,
      challenge: /tunggu sebentar|just a moment|verifikasi keamanan/i.test(document.title || ''),
    }));
    console.log(`[LIVE] ${url} status=${response ? response.status() : 'n/a'} ` +
      `title="${info.title}" cards=${info.cards} challenge=${info.challenge}`);
    await page.close();
  }
  await browser.close();
}

const mode = process.argv[2] || 'fixtures';
if (mode === 'all' || mode === 'fixtures') await verifyFixtures();
if (mode === 'all' || mode === 'live') await probeLive();