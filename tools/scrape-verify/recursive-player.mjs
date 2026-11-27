// Offline Chromium comparison: captured HTML + accessible init script, synthetic media metadata.
// No live media, ad clicks, gesture simulation or security-response modification.
import { chromium } from 'playwright';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import assert from 'node:assert/strict';
const root = new URL('../../', import.meta.url);
const read = path => readFileSync(new URL(path, root), 'utf8');
const body = path => read(path).replaceAll('\r\n','\n').split('\n\n').slice(1).join('\n\n');
const common = read('app/src/main/assets/scrape/common.js');
const current = read('app/src/main/assets/scrape/stream.js');
// Stable pre-change observer, supplied explicitly to make the benchmark reproducible.
const baselinePath = process.argv[2];
const baseline = baselinePath ? readFileSync(baselinePath, 'utf8') : null;
const popupUrl = 'https://videonode.de/iframe3/p2p/rt_gcIppZ3yLHIx4pgRqYg';
const api = body('lk21-request/[148] response_videonode.de_message.txt');
const provider = JSON.parse(api).embedUrl;
const html = body('lk21-request/[150] response_playcdn.de_message.txt');
const init = read('app/src/test/resources/lk21/player/playcdn-init-2026-11-27.js');
const popup = body('lk21-request/[84] response_videonode.de_message.txt').replace('var host = "hydrax"','var host = "p2p"').replace('a9rCpj6LGAx-CefoBJ7rkg','rt_gcIppZ3yLHIx4pgRqYg');
const browser = await chromium.launch({executablePath:'/usr/bin/chromium',args:['--no-sandbox']});
const results=[];
try {
  for (const [name,script,nested,mode] of [
    ...(baseline ? [['baseline captured init',baseline,false,'xhr']] : []),
    ['fixed captured init',current,false,'xhr'],
    ['fixed deeper cross-origin iframe',current,true,'fetch'],
  ]) {
    const context=await browser.newContext();
    let result, requests=0, verify=0, metadataAt;
    const start=performance.now();
    await context.exposeFunction('resultBridge',(id,raw)=>{result??=JSON.parse(raw)});
    await context.addInitScript({content:`window.__freepiRequestId='recursive';window.__freepiRequestUrl=${JSON.stringify(popupUrl)};window.__freepiProviderUrl=${JSON.stringify(provider)};window.FreepiScrapeBridge={postMessage:(id,p)=>window.resultBridge(id,p),logStage:()=>{},playerPage:()=>{}};${common}\n${script}`});
    await context.route('**/*',async route=>{
      const request=route.request(), url=new URL(request.url()); requests++;
      if(url.hostname==='catalog.test')return route.fulfill({contentType:'text/html',body:'<iframe id="main-player" src="about:blank"></iframe>'});
      if(url.hostname==='videonode.de'&&url.pathname.startsWith('/iframe3/'))return route.fulfill({contentType:'text/html',body:popup});
      if(url.hostname==='videonode.de'&&url.pathname==='/api.php')return route.fulfill({contentType:'application/json',body:api});
      if(url.origin===new URL(provider).origin&&url.pathname===new URL(provider).pathname)return route.fulfill({contentType:'text/html',body:nested?'<iframe allowfullscreen src="https://deeper.test/embed"></iframe>':html});
      if(url.hostname==='playcdn.de'&&url.pathname.endsWith('init_player.js'))return route.fulfill({contentType:'application/javascript',body:init});
      if(url.hostname==='playcdn.de'&&url.pathname.startsWith('/verify/')) {
        verify++; metadataAt=performance.now()-start;
        return route.fulfill({contentType:'application/json',body:JSON.stringify({status:'success',fileUrl:'https://media.test/master.m3u8',title:'Offline fixture'})});
      }
      if(url.hostname==='deeper.test'&&url.pathname==='/embed')return route.fulfill({contentType:'text/html',body:`<script>setTimeout(()=>fetch('/config').then(r=>r.json()),200)</script>`});
      if(url.hostname==='deeper.test'&&url.pathname==='/config') {
        verify++; metadataAt=performance.now()-start;
        return route.fulfill({contentType:'application/json',body:JSON.stringify({playlist:[{sources:[{file:'https://media.test/manifest',type:'dash'}],tracks:[{file:'/en.vtt',language:'en',label:'English'}]}]})});
      }
      if(url.hostname==='playcdn.de'&&url.pathname.startsWith('/js/'))return route.fulfill({contentType:'application/javascript',body:''});
      return route.abort();
    });
    const page=await context.newPage();
    await page.goto('https://catalog.test/movie',{waitUntil:'domcontentloaded'});
    for(let i=0;i<230&&!result;i++)await page.waitForTimeout(100);
    const elapsedMs=Math.round(performance.now()-start);
    if(name.startsWith('baseline'))assert.equal(result?.data?.media.length,0);
    else {
      assert.ok(result?.data?.media.length,'runtime metadata should be found');
      assert.equal(result.data.media[0].url,nested?'https://media.test/manifest':'https://media.test/master.m3u8');
      if(nested)assert.equal(result.data.subtitles[0].language,'en');
    }
    assert.equal(verify,1,'one configuration exchange');
    const providerFrame=page.frames().find(f=>f.url()===provider);
    const button=nested?null:await providerFrame.evaluate(()=>({custom:!!document.querySelector('.custom-play-btn'),jw:!!document.querySelector('.jwplayer'),video:!!document.querySelector('video')}));
    const row={name,elapsedMs,metadataResponseMs:Math.round(metadataAt),requests,mediaCandidates:result?.data?.media.length??0,button};
    results.push(row);console.log(JSON.stringify(row));
    await context.close();
  }
} finally {await browser.close()}
mkdirSync(new URL('out/',import.meta.url),{recursive:true});
writeFileSync(new URL('out/recursive-player.json',import.meta.url),JSON.stringify(results,null,2));
