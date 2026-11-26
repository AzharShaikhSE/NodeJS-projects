// Full mirror path in real Chromium:
//   sender.html (laptop) -> relay (this package) -> mock TV API -> receiver.html (TV WebView stand-in)
// getDisplayMedia is stubbed with an animated canvas so it runs headless.
//   node test/e2e.mjs
import { createRequire } from 'node:module';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { mkdir } from 'node:fs/promises';

process.env.NC_ALLOW_ANY_TV = '1'; // CI/container IPs are not always in private ranges
const { chromium } = createRequire(import.meta.url)('playwright');
const { createServer: createRelay, lanIps } = await import('../server.js');
const { createServer: createTv } = await import('../../dev-server/server.js');

const here = path.dirname(fileURLToPath(import.meta.url));
const shots = process.env.SHOTS || path.join(here, 'screenshots');
await mkdir(shots, { recursive: true });
const listen = (s, host) => new Promise((r) => s.listen(0, host, () => r(s.address().port)));
const ip = lanIps()[0];
assert.ok(ip, 'need a non-loopback IPv4 for WebRTC host candidates');

const tvSrv = createTv();
const tvPort = await listen(tvSrv, '0.0.0.0');
const relay = createRelay();
const relayPort = await listen(relay, '127.0.0.1');

const browser = await chromium.launch();
let passed = 0;
const step = async (n, f) => { await f(); passed++; console.log('ok -', n); };
try {
  const ctx = await browser.newContext({ viewport: { width: 1100, height: 900 } });
  const tvPage = await ctx.newPage();     // stands in for the Fire TV WebView
  const laptop = await ctx.newPage();
  const errors = [];
  for (const p of [tvPage, laptop]) p.on('pageerror', (e) => errors.push(e.message));

  let stopped = false;
  tvSrv.tv.onMirrorStart = (id, token) => { tvPage.goto(`http://${ip}:${tvPort}/receiver?t=${token}&id=${id}`); };
  tvSrv.tv.onMirrorStop = () => { stopped = true; };

  await laptop.addInitScript(() => {
    navigator.mediaDevices.getDisplayMedia = async () => {
      const c = document.createElement('canvas'); c.width = 1280; c.height = 720;
      const g = c.getContext('2d'); let n = 0;
      setInterval(() => { g.fillStyle = `hsl(${(n += 7) % 360},80%,50%)`; g.fillRect(0, 0, 1280, 720);
        g.fillStyle = '#fff'; g.font = 'bold 120px sans-serif'; g.fillText('Frame ' + n, 300, 400); }, 33);
      return c.captureStream(30);
    };
  });

  await step('sender page reaches the TV with the PIN', async () => {
    await laptop.goto(`http://localhost:${relayPort}/`);
    await laptop.fill('#tv', `${ip}:${tvPort}`);
    await laptop.fill('#pin', '1234');
    await laptop.click('#check');
    await laptop.waitForFunction(() => /Connected to Fake Fire TV/.test(document.getElementById('checkMsg').textContent));
  });

  await step('wrong PIN is reported', async () => {
    await laptop.fill('#pin', '0000');
    await laptop.click('#go');
    await laptop.waitForFunction(() => /Wrong PIN/.test(document.getElementById('state').textContent));
    await laptop.fill('#pin', '1234');
  });

  await step('video frames reach the TV receiver over WebRTC', async () => {
    await laptop.click('#go');
    await laptop.waitForFunction(() => document.getElementById('state').textContent === 'Mirroring to TV', null, { timeout: 20000 });
    await tvPage.waitForFunction(() => {
      const v = document.getElementById('v');
      return v && v.videoWidth > 0 && v.currentTime > 0.5 && !v.paused;
    }, null, { timeout: 20000 });
    const w = await tvPage.evaluate(() => document.getElementById('v').videoWidth);
    assert.ok(w >= 640, 'receiver width ' + w);
    assert.equal(await tvPage.evaluate(() => document.getElementById('msg').classList.contains('hide')), true);
    await laptop.waitForFunction(() => /Mbps/.test(document.getElementById('stats').textContent), null, { timeout: 10000 });
    console.log('   sender stats:', await laptop.textContent('#stats'));
    await tvPage.screenshot({ path: path.join(shots, 'tv-receiver.png') });
    await laptop.screenshot({ path: path.join(shots, 'laptop-sender.png'), fullPage: true });
  });

  await step('stop on laptop ends the session on the TV', async () => {
    await laptop.click('#go');
    await laptop.waitForFunction(() => document.getElementById('state').textContent === 'Not mirroring');
    for (let i = 0; i < 20 && !stopped; i++) await new Promise((r) => setTimeout(r, 100));
    assert.equal(stopped, true);
    assert.equal(tvSrv.tv.mirror, null);
  });

  assert.deepEqual(errors, [], errors.join('; '));
  console.log(`\n${passed} mirror e2e checks passed`);
} finally {
  await browser.close(); tvSrv.close(); relay.close();
}
