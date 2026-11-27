// Offline Chromium checks using the captured popup and synthetic downstream fixtures.
import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';
const root = new URL('../../', import.meta.url);
const common = readFileSync(new URL('app/src/main/assets/scrape/common.js', root), 'utf8');
const stream = readFileSync(new URL('app/src/main/assets/scrape/stream.js', root), 'utf8');
const popup = readFileSync(new URL('lk21-request/[84] response_videonode.de_message.txt', root), 'utf8').split('<!DOCTYPE html>')[1];
const browser = await chromium.launch({executablePath:'/usr/bin/chromium', args:['--no-sandbox']});
try {
  for (const interaction of [false, true]) {
    const context = await browser.newContext();
    let result, post, stage = 'before-interaction';
    const events = [];
    await context.exposeFunction('fixturePayload', (id, raw) => { const p=JSON.parse(raw); if (!result) result=p; });
    await context.addInitScript({content: `window.__freepiRequestId='fixture'; window.__freepiRequestUrl='https://popup.test/iframe3/test/dynamic'; window.__freepiProviderUrl='https://provider.test/embed'; window.FreepiScrapeBridge={postMessage:(id,p)=>window.fixturePayload(id,p),logStage:()=>{}};\n${common}\n${stream}`});
    await context.route('**/*', async route => {
      const r=route.request(), u=new URL(r.url());
      events.push({stage,method:r.method(),url:r.url()});
      if(u.hostname==='catalog.test') return route.fulfill({contentType:'text/html',body:'<iframe id="main-player" src="about:blank"></iframe>'});
      if(u.hostname==='popup.test' && u.pathname.startsWith('/iframe3/')) return route.fulfill({contentType:'text/html',body:'<!DOCTYPE html>'+popup});
      if(u.hostname==='popup.test' && u.pathname==='/api.php') {
        post=r.postData();
        await new Promise(resolve=>setTimeout(resolve,600));
        return route.fulfill({contentType:'application/json',body:JSON.stringify({embedUrl:'https://provider.test/embed'})});
      }
      if(u.hostname==='provider.test') return route.fulfill({contentType:'text/html',body:`<button id="play">Initialize fixture player</button><script>
        function initialize(){window.jwplayer=function(){return {getPlaylist:function(){return [{sources:[{file:'https://media.test/master.m3u8'}],tracks:[{kind:'captions',file:'https://media.test/id.vtt',label:'Indonesia',language:'id'}]}]}}};}
        ${interaction ? "document.querySelector('#play').onclick=initialize;" : 'setTimeout(initialize,700);'}
        </script>`});
      // All other traffic remains offline, including ads and security scripts in the capture.
      return route.abort();
    });
    const page=await context.newPage();
    await page.goto('https://catalog.test/movie', {waitUntil:'domcontentloaded'});
    await page.waitForTimeout(1500);
    assert.equal(post, 'host=hydrax&id=a9rCpj6LGAx-CefoBJ7rkg');
    if(interaction) {
      assert.equal(result, undefined, 'must wait for legitimate fixture initialization');
      stage='after-interaction';
      // This button exists only in the synthetic fixture; no live overlays are clicked.
      await page.frameLocator('iframe#main-player').frameLocator('iframe#video-iframe').locator('#play').click();
    }
    for(let i=0;i<30 && !result;i++) await page.waitForTimeout(100);
    assert.equal(result?.data?.media[0]?.url, 'https://media.test/master.m3u8');
    assert.equal(result.data.pageUrl, 'https://provider.test/embed');
    assert.equal(result.data.subtitles[0].language, 'id');
    assert.equal(result.ok, true);
    console.log(JSON.stringify({fixture: interaction?'gesture initialization':'delayed initialization',post,events,result:result.data}));
    await context.close();
  }
} finally {await browser.close();}
