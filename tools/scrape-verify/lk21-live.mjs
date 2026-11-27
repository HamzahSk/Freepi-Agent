// Observational diagnostics only. Never clicks overlays or modifies provider code.
import { chromium } from 'playwright';
import { mkdirSync, writeFileSync } from 'node:fs';
const url = process.argv[2];
if (!url) throw new Error('Usage: node lk21-live.mjs <actual catalog episode/movie URL>');
const out = new URL('./out/lk21-live/', import.meta.url);
mkdirSync(out, { recursive: true });
const browser = await chromium.launch({ executablePath: '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] });
const page = await browser.newPage();
const started = Date.now(), events = [], bodies = [];
page.on('request', r => events.push({ms: Date.now()-started, event:'request', method:r.method(), url:r.url(), type:r.resourceType(), body:r.url().endsWith('/api.php') ? r.postData() : undefined}));
page.on('response', r => {
  events.push({ms:Date.now()-started,event:'response',url:r.url(),status:r.status(),type:r.headers()['content-type']});
  if (/api\.php|abyssplayer\.com|\/player-v2\//.test(r.url())) bodies.push((async()=>{
    try { const body=await r.text(); writeFileSync(new URL(`response-${bodies.length}-${new URL(r.url()).hostname}.txt`,out), body); } catch {}
  })());
});
page.on('pageerror', e=>events.push({ms:Date.now()-started,event:'error',message:e.message}));
try {
  await page.goto(url, {waitUntil:'domcontentloaded',timeout:30000});
  await page.waitForTimeout(15000);
  for (const frame of page.frames()) {
    try { events.push({event:'snapshot-before-interaction', url:frame.url(), data:await frame.evaluate(()=>({title:document.title, video:[...document.querySelectorAll('video')].map(v=>({src:v.currentSrc,readyState:v.readyState})), overlays:[...document.querySelectorAll('#overlay,#uyeouyeo')].map(e=>({id:e.id,tag:e.tagName,href:e.getAttribute('href'),click:String(e.onclick).slice(0,200)})),playlist: typeof window.jwplayer==='function' ? window.jwplayer().getPlaylist?.() : null}))}); } catch(e) { events.push({event:'snapshot-error',url:frame.url(),message:e.message}); }
  }
  events.push({event:'interaction-skipped',reason:'Captured popup and provider overlays open advertising links; no ad clicks are authorized.'});
} finally {
  await Promise.allSettled(bodies);
  writeFileSync(new URL('network.json',out),JSON.stringify(events,null,2));
  console.log(JSON.stringify(events.filter(e=>e.event!=='request'&&e.event!=='response'||/api\.php|abyss|m3u8|\.mpd/.test(e.url)),null,2));
  await browser.close();
}
