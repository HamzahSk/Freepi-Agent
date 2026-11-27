// Observational live diagnostics. Stops on access restrictions; no clicks or provider-code changes.
// URLs contain no query, slug, credentials, response bodies, cookies or tokens in the saved report.
import { chromium } from 'playwright';
import { mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
const url = process.argv[2];
if (!url) throw new Error('Usage: node lk21-live.mjs <catalog URL or accessible initialization-script URL>');
const safe = raw => { try { const u=new URL(raw); return u.origin+'/[redacted]'; } catch { return '[redacted]'; } };
const out = new URL('./out/lk21-live/', import.meta.url);
mkdirSync(out,{recursive:true});
const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||'/usr/bin/chromium',headless:true,args:['--no-sandbox']});
const page=await browser.newPage(), start=Date.now(), events=[], pending=[];
page.on('request',r=>events.push({ms:Date.now()-start,event:'request',url:safe(r.url()),method:r.method(),type:r.resourceType()}));
page.on('response',r=>{
  events.push({ms:Date.now()-start,event:'response',url:safe(r.url()),status:r.status(),type:r.headers()['content-type']});
  if(new URL(r.url()).pathname.endsWith('/init_player.js'))pending.push((async()=>{
    try {
      const body=await r.body();
      const fixture=readFileSync(new URL('../../app/src/test/resources/lk21/player/playcdn-init-2026-11-27.js',import.meta.url));
      events.push({event:'initialization-script',status:r.status(),bytes:body.length,sha256:createHash('sha256').update(body).digest('hex'),matchesFixture:body.equals(fixture)});
    }catch{}
  })());
});
try {
  await page.goto(url,{waitUntil:'domcontentloaded',timeout:25000});
  const restricted=await page.evaluate(()=>/just a moment|attention required|verify you are human/i.test(document.title)||!!document.querySelector('#challenge-form'));
  if(restricted)events.push({event:'access-restricted',action:'stopped'});
  else {
    if(!new URL(url).pathname.endsWith('.js'))await page.waitForTimeout(10000);
    for(const frame of page.frames()){
      try { events.push({event:'snapshot',url:safe(frame.url()),data:await frame.evaluate(()=>({title:document.title,videoElements:document.querySelectorAll('video').length,playlistItems:typeof jwplayer==='function'?(jwplayer().getPlaylist?.()||[]).length:0,customPlayButton:!!document.querySelector('.custom-play-btn')}))}); }catch{}
    }
  }
} catch(e) {events.push({event:'navigation-failed',type:e.name});}
finally {
  await Promise.allSettled(pending);
  const report={elapsedMs:Date.now()-start,requests:events.filter(e=>e.event==='request').length,events};
  writeFileSync(new URL('network.json',out),JSON.stringify(report,null,2));
  console.log(JSON.stringify(report,null,2));await browser.close();
}
