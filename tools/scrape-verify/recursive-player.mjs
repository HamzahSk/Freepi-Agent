// Offline Chromium regression suite using the exact document-start production observer.
// Captured pages run unmodified. All response metadata below is synthetic and grants no live access.
import { chromium } from 'playwright';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import assert from 'node:assert/strict';
const root = new URL('../../', import.meta.url);
const read = path => readFileSync(new URL(path, root), 'utf8');
const body = path => read(path).replaceAll('\r\n', '\n').split('\n\n').slice(1).join('\n\n');
const common = read('app/src/main/assets/scrape/common.js');
const current = read('app/src/main/assets/scrape/stream.js');
const baseline = process.argv[2] ? readFileSync(process.argv[2], 'utf8') : null;
const popupUrl = 'https://videonode.de/iframe3/p2p/rt_gcIppZ3yLHIx4pgRqYg';
const api = body('lk21-request/[148] response_videonode.de_message.txt');
const provider = JSON.parse(api).embedUrl;
const captured = body('lk21-request/[150] response_playcdn.de_message.txt');
const sotrym = read('app/src/test/resources/lk21/player/live-provider.html');
const init = read('app/src/test/resources/lk21/player/playcdn-init-2026-11-27.js');
const popup = body('lk21-request/[84] response_videonode.de_message.txt').replace('var host = "hydrax"', 'var host = "p2p"').replace('a9rCpj6LGAx-CefoBJ7rkg', 'rt_gcIppZ3yLHIx4pgRqYg');
const browser = await chromium.launch({executablePath: '/usr/bin/chromium', args: ['--no-sandbox']});
const results = [];
const publicPlayer = file => `window.jwplayer=()=>({getState:()=> 'idle',getPlaylist:()=>[{sources:[{file:${JSON.stringify(file)},type:'hls'}]}]});`;
try {
  const cases = [
    ...(baseline ? [{name:'baseline captured advertising gate',script:baseline,mode:'captured',unsafe:true}] : []),
    {name:'captured advertising gate',mode:'captured',error:'interaction or advertising'},
    {name:'captured SoTrym window.load',mode:'sotrym',error:'interaction or advertising'},
    {name:'verify fileUrl before custom play gesture',mode:'verify-play',expected:'https://media.test/master.m3u8'},
    {name:'verify JSON with text content type',mode:'verify-text',expected:'https://media.test/master.m3u8'},
    {name:'hidden overlay is non-blocking',mode:'verify-hidden',expected:'https://media.test/master.m3u8'},
    {name:'verify metadata does not bypass advertising gate',mode:'verify-ad',error:'interaction or advertising'},
    {name:'failed verify cannot expose stale fileUrl',mode:'verify-denied',error:'PLAYER_METADATA'},
    {name:'verify HTTP denial cannot expose fileUrl',mode:'verify-http',error:'PLAYER_METADATA'},
    {name:'DRM configuration is not delivered as an unprotected stream',mode:'verify-drm',error:'PLAYER_METADATA'},
    {name:'delayed external dependency',mode:'delayed',expected:'https://media.test/master.m3u8'},
    {name:'fetch remains native and JWPlayer exposes metadata',mode:'fetch',expected:'https://media.test/master.m3u8'},
    {name:'rejected first candidate then new metadata',mode:'retry',expected:'https://media.test/good.m3u8'},
    {name:'failed script diagnostics',mode:'failed',error:'DEPENDENCY_LOADING'},
    {name:'JavaScript error diagnostics',mode:'jserror',error:'JavaScript execution failed'},
    {name:'parent advertising gate blocks child metadata',mode:'ancestor-gate',error:'interaction or advertising'},
    {name:'nested cross-origin player',mode:'nested',expected:'https://media.test/master.m3u8'},
  ];
  for (const c of cases) {
    const context = await browser.newContext();
    let result, requests = 0, verify = 0, lastState, payloads = [], selectedLoads = 0, fetchNative = null;
    const responses = [], registered = new Set();
    const start = performance.now();
    await context.exposeFunction('resultBridge', (id, raw) => {
      const value = JSON.parse(raw); payloads.push(value);
      if (c.mode === 'retry' && value.ok && value.data.media.every(m => m.url.endsWith('bad.m3u8'))) return;
      result ??= value;
    });
    await context.exposeFunction('stateBridge', (id, stage, state) => { lastState = {stage,state}; });
    await context.exposeFunction('responseBridge', (id, url, status, candidates) => { responses.push({url,status,candidates}); });
    await context.exposeFunction('pageBridge', (id, url) => { registered.add(url); });
    await context.addInitScript({content:`window.__freepiRequestId='recursive';window.__freepiDeadline=Date.now()+3500;window.__freepiRequestUrl=${JSON.stringify(popupUrl)};window.__freepiProviderUrl=${JSON.stringify(provider)};window.FreepiScrapeBridge={postMessage:(id,p)=>window.resultBridge(id,p),logStage:()=>{},playerPage:(...s)=>window.pageBridge(...s),metadataResponse:(...s)=>window.responseBridge(...s),playerState:(...s)=>window.stateBridge(...s)};${common}\n${c.script || current}`});
    await context.route('**/*', async route => {
      const url = new URL(route.request().url()); requests++;
      if (url.hostname === 'catalog.test') return route.fulfill({contentType:'text/html',body:`<iframe id="main-player" src="${popupUrl}"></iframe>`});
      if (url.hostname === 'videonode.de' && url.pathname.startsWith('/iframe3/')) { selectedLoads++; return route.fulfill({contentType:'text/html',body:popup}); }
      if (url.hostname === 'videonode.de' && url.pathname === '/api.php') return route.fulfill({contentType:'application/json',body:api});
      if (url.origin === new URL(provider).origin && url.pathname === new URL(provider).pathname) {
        let html;
        switch (c.mode) {
          case 'captured': html = captured; break;
          case 'sotrym': html = sotrym; break;
          // Synthetic pages have no advertising flow. Use the captured initialization
          // program unchanged, and never click its custom play control.
          case 'verify-play': case 'verify-text': case 'verify-denied': case 'verify-http': case 'verify-drm':
          case 'verify-hidden': case 'verify-ad':
            html = `<div id='vstr' style='width:640px;height:360px;position:relative'></div>
              ${c.mode === 'verify-ad' ? "<div id='overlay' style='position:fixed;inset:0'></div>" : ''}
              ${c.mode === 'verify-hidden' ? "<div id='overlay' style='display:none;width:640px;height:360px'></div>" : ''}
              <script src='/js/init_player.js'></script>`;
            break;
          case 'delayed': html = `<script src='/delayed.js'></script>`; break;
          case 'fetch': html = `<script>window.fetchNative=fetch.toString().includes('[native code]');fetch('/fixture.json').then(r=>r.json()).then(data=>{window.jwplayer=()=>({getState:()=> 'idle',getPlaylist:()=>[data]})})</script>`; break;
          case 'retry': html = `<script>${publicPlayer('https://media.test/bad.m3u8')}setTimeout(()=>{${publicPlayer('https://media.test/good.m3u8')}},900)</script>`; break;
          case 'ancestor-gate': html = `<div id='overlay' style='position:fixed;inset:0'></div><iframe allowfullscreen src='https://deeper.test/player'></iframe>`; break;
          case 'nested': html = `<iframe allowfullscreen src='https://deeper.test/player'></iframe>`; break;
          case 'failed': html = `<script src='/failed.js'></script>`; break;
          case 'jserror': html = `<script>throw new Error('credential-sentinel-do-not-log')</script>`; break;
        }
        return route.fulfill({contentType:'text/html',body:html});
      }
      if (url.pathname === '/fixture.json') return route.fulfill({contentType:'application/json',body:JSON.stringify({sources:[{file:'https://media.test/master.m3u8',type:'hls'}]})});
      if (url.pathname === '/delayed.js') {
        await new Promise(resolve=>setTimeout(resolve,1300));
        return route.fulfill({contentType:'application/javascript',body:`window.addEventListener('load',()=>{${publicPlayer('https://media.test/master.m3u8')}})`});
      }
      if (url.pathname === '/failed.js') return route.abort();
      if (url.hostname === 'deeper.test') return route.fulfill({contentType:'text/html',body:`<script>${publicPlayer('https://media.test/master.m3u8')}</script>`});
      if (url.hostname === 'playcdn.de' && url.pathname.endsWith('init_player.js')) return route.fulfill({contentType:'application/javascript',body:init});
      if (url.hostname === 'playcdn.de' && url.pathname.startsWith('/verify/')) {
        verify++;
        return route.fulfill({status:c.mode === 'verify-http' ? 403 : 200,
          contentType:c.mode === 'verify-text' ? 'text/plain' : 'application/json',
          body:JSON.stringify({status:c.mode === 'verify-denied' ? 'error' : 'success',
            fileUrl:'https://media.test/master.m3u8',title:'Offline fixture',
            ...(c.mode === 'verify-drm' ? {drm:{licenseUrl:'https://license.test/'}} : {})})});
      }
      if (url.hostname === 'iamcdn.net') {
        if (url.pathname.endsWith('core.bundle.js')) return route.fulfill({contentType:'application/javascript',body:''});
        if (url.pathname.endsWith('lite.bundle.js')) return route.fulfill({contentType:'application/javascript',body:`window.SoTrym=()=>{window.soTrymCalled=true;${publicPlayer('https://media.test/master.m3u8')}}`});
        return route.fulfill({contentType:'application/javascript',body:''});
      }
      if (url.hostname === 'playcdn.de' && url.pathname.startsWith('/js/')) return route.fulfill({contentType:'application/javascript',body:''});
      return route.abort();
    });
    const page = await context.newPage();
    await page.goto('https://catalog.test/movie', {waitUntil:'domcontentloaded'});
    for (let i=0;i<65 && !result;i++) await page.waitForTimeout(100);
    assert.ok(result, `${c.name}: bridge did not report a result`);
    assert.equal(selectedLoads, 1, 'the selected wrapper must not reload');
    const frame = page.frames().find(f=>f.url() === provider);
    if (c.unsafe) assert.ok(result.data.media.length, 'baseline accepts metadata before the advertising flow');
    else if (c.error) {
      assert.equal(result.ok,false);
      assert.ok(result.error.message.includes(c.error), JSON.stringify(result.error));
      assert.ok(!JSON.stringify(lastState).includes('credential-sentinel'));
    } else {
      assert.equal(result.ok,true);
      assert.ok(result.data.media.some(m=>m.url === c.expected), JSON.stringify(result.data));
      assert.ok(registered.has(result.data.pageUrl), 'metadata must come from a registered player page');
      if (c.mode.startsWith('verify-')) {
        assert.equal(verify,1,'observe the original verification request without replay');
        assert.ok(responses.some(r=>r.status === 200 && r.candidates === 1),'verify response must be diagnosed');
        assert.equal(await frame.locator('.custom-play-btn').isVisible(),true,'play button remains untouched');
        assert.equal(await frame.evaluate(()=>typeof window.jwplayer),'undefined','metadata precedes JWPlayer setup');
        assert.equal(lastState.stage,'PLAYER_METADATA');
      }
      if (c.mode === 'delayed') assert.ok(performance.now()-start >= 1300);
      if (c.mode === 'fetch') { fetchNative = await frame.evaluate(()=>window.fetchNative); assert.equal(fetchNative,true); }
      if (c.mode === 'retry') assert.ok(payloads.length >= 2);
      if (c.mode === 'nested') assert.equal(result.data.depth,2);
    }
    if (c.mode === 'sotrym') assert.equal(await frame.evaluate(()=>window.soTrymCalled),true,'page must run its own window.load and lite fallback');
    if (c.mode === 'verify-denied') assert.ok(responses.some(r=>r.candidates === 0));
    if (c.mode === 'verify-http') assert.ok(responses.some(r=>r.status === 403 && r.candidates === 0));
    if (c.mode === 'verify-drm') assert.ok(responses.some(r=>r.candidates === 0));
    if (c.mode === 'verify-ad') assert.ok(responses.some(r=>r.candidates === 1),'observed fileUrl remains blocked by real overlay');
    const row = {name:c.name,elapsedMs:Math.round(performance.now()-start),requests,selectedLoads,verify,fetchNative,mediaCandidates:result.data?.media?.length || 0,error:result.error?.message,lastState};
    results.push(row); console.log(JSON.stringify(row));
    await context.close();
  }
} finally { await browser.close(); }
mkdirSync(new URL('out/',import.meta.url),{recursive:true});
writeFileSync(new URL('out/recursive-player.json',import.meta.url),JSON.stringify(results,null,2));
