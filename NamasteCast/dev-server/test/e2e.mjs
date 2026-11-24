// Browser tests: phone remote UI against the mock TV, and the injected
// tv-helper.js against real <video> pages (same-origin + cross-origin iframe).
//   node test/e2e.mjs        (needs playwright + chromium)
import { createRequire } from 'node:module';
import http from 'node:http';
import { readFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { createServer } from '../server.js';

// require() so a globally installed playwright resolves too (ESM ignores NODE_PATH).
const { chromium, devices } = createRequire(import.meta.url)('playwright');
const here = path.dirname(fileURLToPath(import.meta.url));
const shots = process.env.SHOTS || path.join(here, 'screenshots');
await mkdir(shots, { recursive: true });
const helper = await readFile(path.join(here, '../../firetv-app/app/src/main/assets/tv-helper.js'), 'utf8');
const video = await readFile(path.join(here, 'fixtures/sample.webm'));

const listen = (s) => new Promise((r) => s.listen(0, '127.0.0.1', () => r(s.address().port)));
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
let passed = 0;
async function step(name, fn) { await fn(); passed++; console.log('ok -', name); }

const tvServer = createServer();
const tvPort = await listen(tvServer);
const tv = tvServer.tv;

// Pages that look like a course: one with the video inline, one with it in a cross-origin iframe.
const pages = http.createServer((req, res) => {
  if (req.url === '/sample.webm') {
    // Range support so the browser can seek, like any real video CDN.
    const m = /bytes=(\d+)-(\d*)/.exec(req.headers.range || '');
    if (!m) { res.writeHead(200, { 'Content-Type': 'video/webm', 'Accept-Ranges': 'bytes', 'Content-Length': video.length }); return res.end(video); }
    const start = Number(m[1]); const end = m[2] ? Number(m[2]) : video.length - 1;
    res.writeHead(206, { 'Content-Type': 'video/webm', 'Accept-Ranges': 'bytes', 'Content-Length': end - start + 1,
      'Content-Range': `bytes ${start}-${end}/${video.length}` });
    return res.end(video.subarray(start, end + 1));
  }
  const vid = '<video id="v" src="/sample.webm" muted style="width:800px;height:450px"></video>';
  const body = {
    '/inline': `<h1>Episode 1</h1>${vid}<div style="height:3000px"></div>`,
    '/player': `<body style="margin:0">${vid}</body>`,
    '/framed': `<h1>Episode 2</h1><iframe id="f" src="http://localhost:${pagesPort}/player" style="width:820px;height:470px;border:0"></iframe>`,
    '/scrollbox': `<div id="box" style="height:300px;overflow:auto"><div style="height:2000px">x</div></div>`,
  }[req.url];
  res.writeHead(body ? 200 : 404, { 'Content-Type': 'text/html' });
  res.end(`<!doctype html><html><body>${body || ''}</body></html>`);
});
const pagesPort = await listen(pages);

const browser = await chromium.launch();
try {
  // ---------------------------------------------------------- remote UI
  const phone = await browser.newContext({ ...devices['iPhone 13'] });
  const page = await phone.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  const base = `http://127.0.0.1:${tvPort}`;

  await step('pairs from QR link and strips pin from URL', async () => {
    await page.goto(`${base}/?pin=1234`);
    await page.waitForFunction(() => document.getElementById('tvname').textContent === 'Fake Fire TV');
    assert.equal(await page.locator('#pair').isVisible(), false);
    assert.equal(new URL(page.url()).search, '');
  });

  await step('play/pause, seek, speed, TV mode send commands', async () => {
    await page.click('[data-video="toggle"]');
    await page.click('[data-video="seek"][data-value="10"]');
    await page.click('#speedBtn');
    await page.click('#fillBtn');
    await wait(300);
    const vids = tv.commands.filter((c) => c.type === 'video').map((c) => c.action + ':' + (c.value ?? ''));
    assert.deepEqual(vids, ['toggle:0', 'seek:10', 'rate:1.25', 'fill:0']);
    await page.waitForFunction(() => document.getElementById('tdur').textContent === '30:30');
    await page.waitForFunction(() => document.getElementById('fillBtn').classList.contains('on'));
  });

  await step('open URL adds to recent list', async () => {
    await page.fill('#url', 'namastedev.com/learn/namaste-react/episode-01');
    await page.click('#openForm button[type=submit]');
    await page.waitForSelector('#recent button');
    assert.equal(tv.commands.at(-1).type, 'open');
    await page.screenshot({ path: path.join(shots, 'remote-phone.png'), fullPage: true });
  });

  await step('touchpad drag moves, tap clicks', async () => {
    tv.commands.length = 0;
    await page.locator('#pad').scrollIntoViewIfNeeded();
    const box = await page.locator('#pad').boundingBox();
    const cdp = await phone.newCDPSession(page);
    const x = box.x + box.width / 2, y = box.y + box.height / 2;
    const touch = (type, pts) => cdp.send('Input.dispatchTouchEvent', { type, touchPoints: pts });
    await touch('touchStart', [{ x, y, id: 1 }]);
    for (let i = 1; i <= 8; i++) { await touch('touchMove', [{ x: x + i * 10, y: y + i * 4, id: 1 }]); await wait(16); }
    await touch('touchEnd', []);
    await wait(150);
    await touch('touchStart', [{ x, y, id: 2 }]); await touch('touchEnd', []);
    await wait(250);
    const moves = tv.commands.filter((c) => c.type === 'move');
    assert.ok(moves.length >= 1, 'expected move commands');
    assert.ok(moves.reduce((s, c) => s + c.dx, 0) > 80, 'pointer should travel right');
    assert.equal(tv.commands.at(-1).type, 'click');
  });

  await step('typing and keys', async () => {
    await page.fill('#text', 'azhar@example.com');
    await page.click('#typeForm button');
    await page.click('[data-key="enter"]');
    await wait(200);
    assert.deepEqual(tv.commands.slice(-2), [{ type: 'text', text: 'azhar@example.com' }, { type: 'key', key: 'enter' }]);
  });

  await step('bad PIN shows pair screen, good PIN recovers', async () => {
    const fresh = await browser.newContext({ ...devices['iPhone 13'] });
    const p = await fresh.newPage();
    await p.goto(base + '/');
    await p.waitForSelector('#pair.show');
    await p.fill('#pin', '0000'); await p.click('#pairBtn');
    await p.waitForFunction(() => document.getElementById('err').textContent === 'Wrong PIN');
    await p.screenshot({ path: path.join(shots, 'remote-pair.png') });
    await p.fill('#pin', '1234'); await p.click('#pairBtn');
    await p.waitForSelector('#pair', { state: 'hidden' });
    await fresh.close();
  });

  await step('laptop layout has bookmarklet with pin', async () => {
    const lap = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    const p = await lap.newPage();
    await p.goto(`${base}/?pin=1234`);
    await p.waitForFunction(() => document.getElementById('dot').classList.contains('ok'));
    const href = await p.getAttribute('#bookmarklet', 'href');
    assert.match(href, /^javascript:/); assert.match(href, /\/open\?pin=1234&url=/);
    await p.keyboard.press('Space'); await wait(200);
    assert.deepEqual(tv.commands.at(-1), { type: 'video', action: 'toggle' });
    await p.screenshot({ path: path.join(shots, 'remote-laptop.png'), fullPage: true });
    await lap.close();
  });

  assert.deepEqual(errors, [], 'remote page threw: ' + errors.join('; '));

  // ---------------------------------------------------------- tv-helper.js
  const tvCtx = await browser.newContext({ viewport: { width: 960, height: 540 } });
  const tp = await tvCtx.newPage();
  const run = (expr) => tp.evaluate(`${helper}\n;${expr}`);

  await step('helper controls an inline video', async () => {
    await tp.goto(`http://127.0.0.1:${pagesPort}/inline`);
    await tp.waitForFunction(() => document.getElementById('v').readyState >= 1);
    let s = await run('window.__nc.state()');
    assert.equal(s.hasVideo, true); assert.equal(s.paused, true); assert.ok(s.duration > 25);
    assert.equal((await run("window.__nc.act('toggle',0)")).handled, true);
    await wait(500);
    s = await run('window.__nc.state()');
    assert.equal(s.paused, false);
    await run("window.__nc.act('seek',10)");
    assert.ok((await run('window.__nc.state()')).time >= 10);
    await run("window.__nc.act('rate',1.5)");
    assert.equal((await run('window.__nc.state()')).rate, 1.5);
    await run("window.__nc.act('seekto',0.5)");
    const t = (await run('window.__nc.state()')).time;
    assert.ok(t > 13 && t < 17, 'seekto 50% got ' + t);
    await run("window.__nc.act('mute',0)");
    const filled = await run("window.__nc.act('fill',0)");
    assert.equal(filled.filled, true);
    const r = await tp.evaluate(() => document.getElementById('v').getBoundingClientRect());
    assert.equal(Math.round(r.width), 960); assert.equal(Math.round(r.height), 540);
    await tp.screenshot({ path: path.join(shots, 'tv-mode.png') });
    assert.equal((await run("window.__nc.act('fill',0)")).filled, false);
  });

  await step('helper falls back for cross-origin player iframe', async () => {
    await tp.goto(`http://127.0.0.1:${pagesPort}/framed`);
    await tp.waitForTimeout(500);
    const s = await run('window.__nc.state()');
    assert.equal(s.hasVideo, false); assert.equal(s.player, true);
    const a = await run("window.__nc.act('toggle',0)");
    assert.equal(a.handled, false);
    assert.ok(a.player.x > 0 && a.player.x < 1 && a.player.y > 0 && a.player.y < 1);
    assert.equal((await run("window.__nc.act('fill',0)")).filled, true); // iframe itself can be filled
  });

  await step('helper scrolls nested scroll box and types into inputs', async () => {
    await tp.goto(`http://127.0.0.1:${pagesPort}/scrollbox`);
    await run('window.__nc.scroll(0.1,0.1,400)');
    await tp.waitForFunction(() => document.getElementById('box').scrollTop > 300);
    await tp.setContent('<input id="i">');
    await tp.focus('#i');
    await run("window.__nc.insertText('héllo ✓')");
    assert.equal(await tp.inputValue('#i'), 'héllo ✓');
  });

  console.log(`\n${passed} e2e checks passed`);
} finally {
  await browser.close();
  tvServer.close(); pages.close();
}
