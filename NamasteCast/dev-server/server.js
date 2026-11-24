// Fake TV: same endpoints as RemoteServer.kt, with a simulated video so the
// remote UI has something to show. No dependencies.
//   npm start  ->  http://localhost:8765/?pin=1234
import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const REMOTE_HTML = path.join(here, '../firetv-app/app/src/main/assets/remote.html');
const PIN = process.env.PIN || '1234';

export function createTv() {
  const tv = {
    commands: [],
    state: {
      ok: true, name: 'Fake Fire TV', url: 'https://namastedev.com/', title: 'NamasteDev',
      progress: 100, canGoBack: false, desktop: true, fullscreen: false, panel: false,
      home: 'https://namastedev.com',
      video: { hasVideo: true, player: true, filled: false, paused: true, time: 0, duration: 1830, rate: 1, muted: false },
      history: [],
    },
  };
  const v = tv.state.video;
  tv.apply = (c) => {
    tv.commands.push(c);
    switch (c.type) {
      case 'open':
        c.url = /^https?:\/\//i.test(c.url || '') ? c.url : 'https://' + c.url; // same rule as CommandParser.normalizeUrl
        tv.state.url = c.url; tv.state.title = 'Lesson: ' + new URL(c.url).pathname;
        tv.state.history = [{ url: c.url, title: tv.state.title }, ...tv.state.history.filter((h) => h.url !== c.url)].slice(0, 20);
        break;
      case 'video':
        if (c.action === 'toggle') v.paused = !v.paused;
        if (c.action === 'seek') v.time = Math.max(0, Math.min(v.duration, v.time + c.value));
        if (c.action === 'seekto') v.time = v.duration * c.value;
        if (c.action === 'rate') v.rate = c.value;
        if (c.action === 'mute') v.muted = !v.muted;
        if (c.action === 'fill') v.filled = !v.filled;
        break;
      case 'mode': tv.state.desktop = !!c.desktop; break;
      case 'panel': tv.state.panel = !tv.state.panel; break;
    }
  };
  tv.tick = setInterval(() => { if (!v.paused) v.time = Math.min(v.duration, v.time + v.rate); }, 1000);
  tv.tick.unref();
  return tv;
}

function readBody(req) {
  return new Promise((resolve) => {
    let b = '';
    req.on('data', (d) => { b += d; if (b.length > 8192) req.destroy(); });
    req.on('end', () => resolve(b));
  });
}

export function createServer(tv = createTv()) {
  const server = http.createServer(async (req, res) => {
    const u = new URL(req.url, 'http://x');
    const pin = req.headers['x-pin'] || u.searchParams.get('pin');
    const json = (code, obj) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(obj)); };
    if (u.pathname === '/' ) {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-cache' });
      return res.end(await readFile(REMOTE_HTML));
    }
    if (u.pathname === '/api/ping') return json(200, { ok: true, name: tv.state.name, app: 'NamasteCast' });
    if (pin !== PIN) return json(401, { ok: false, error: 'pin' });
    if (u.pathname === '/api/state') return json(200, tv.state);
    if (u.pathname === '/api/cmd' && req.method === 'POST') {
      let c; try { c = JSON.parse(await readBody(req)); } catch { return json(400, { ok: false }); }
      if (!c || typeof c.type !== 'string') return json(400, { ok: false });
      tv.apply(c);
      console.log('[tv] cmd', JSON.stringify(c));
      return json(200, { ok: true });
    }
    if (u.pathname === '/open') {
      tv.apply({ type: 'open', url: u.searchParams.get('url') });
      res.writeHead(200, { 'Content-Type': 'text/html' });
      return res.end('<p>Sent to your TV</p>');
    }
    json(404, { ok: false });
  });
  server.tv = tv;
  return server;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const port = Number(process.env.PORT || 8765);
  createServer().listen(port, () => console.log(`fake TV on http://localhost:${port}/?pin=${PIN}`));
}
