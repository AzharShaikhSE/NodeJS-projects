#!/usr/bin/env node
// Laptop side of NamasteCast mirroring.
//
// Why a local server at all: browsers only allow screen capture (getDisplayMedia)
// on secure pages, and http://localhost counts as secure while the TV's
// http://192.168.x.x does not. So the capture page lives here, and this
// process relays the tiny WebRTC handshake to the TV. The video itself goes
// browser -> TV directly, peer to peer, never through this process.
//
//   npm start  ->  opens http://localhost:8766
import http from 'node:http';
import os from 'node:os';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { exec } from 'node:child_process';

const here = path.dirname(fileURLToPath(import.meta.url));
const TV_PORTS = [8765, 8766, 8767, 8768, 8769, 8770];

// ---------------------------------------------------------------- helpers

export function lanIps() {
  return Object.values(os.networkInterfaces()).flat()
    .filter((i) => i && i.family === 'IPv4' && !i.internal).map((i) => i.address);
}

const isPrivate = (ip) => /^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(ip);

/** "192.168.1.42" or "192.168.1.42:8765" -> {host, port}. Only private LAN addresses. */
export function parseTv(input, allowAny = process.env.NC_ALLOW_ANY_TV === '1') {
  const m = /^(\d{1,3}(?:\.\d{1,3}){3})(?::(\d{2,5}))?$/.exec(String(input || '').trim());
  if (!m) return null;
  const host = m[1];
  if (host.split('.').some((n) => Number(n) > 255)) return null;
  if (!allowAny && !isPrivate(host)) return null;
  return { host, port: Number(m[2] || 8765) };
}

/** The laptop IP that shares a /24 with the TV (falls back to the first LAN IP). */
export function myIpFor(tvHost, ips = lanIps()) {
  const pre = tvHost.split('.').slice(0, 3).join('.') + '.';
  return ips.find((ip) => ip.startsWith(pre)) || ips[0] || null;
}

/**
 * Chrome hides real LAN IPs in WebRTC behind random "xxxx.local" names (mDNS).
 * Fire TV's WebView may not resolve those, so we swap them for the real IPs
 * we already know. Only touches candidate addresses.
 */
export function unmaskSdp(sdp, ip) {
  if (!ip) return sdp;
  return sdp.replace(/(a=candidate:\S+ \d+ \S+ \d+ )[0-9a-f-]+\.local( )/gi, `$1${ip}$2`);
}

async function tvFetch(tv, p, { pin, method = 'GET', body, timeout = 4000 } = {}) {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), timeout);
  try {
    const r = await fetch(`http://${tv.host}:${tv.port}${p}`, {
      method, signal: ctrl.signal,
      headers: { 'X-Pin': pin || '', ...(body ? { 'Content-Type': 'application/json' } : {}) },
      body: body ? JSON.stringify(body) : undefined,
    });
    let data = null;
    try { data = await r.json(); } catch { /* not json */ }
    return { status: r.status, data };
  } finally { clearTimeout(t); }
}

/** Ask every address on our /24 subnets for /api/ping. ~1s on a home network. */
export async function discover(ips = lanIps().filter(isPrivate)) {
  const found = [];
  const probes = [];
  for (const ip of ips) {
    const pre = ip.split('.').slice(0, 3).join('.');
    for (let i = 1; i < 255; i++) {
      const host = `${pre}.${i}`;
      probes.push(tvFetch({ host, port: 8765 }, '/api/ping', { timeout: 900 })
        .then((r) => { if (r.data && r.data.app === 'NamasteCast') found.push({ tv: `${host}:8765`, name: r.data.name }); })
        .catch(() => {}));
    }
  }
  await Promise.all(probes);
  return found;
}

function readJson(req, max = 128 * 1024) {
  return new Promise((resolve) => {
    let b = '';
    req.on('data', (d) => { b += d; if (b.length > max) req.destroy(); });
    req.on('end', () => { try { resolve(JSON.parse(b)); } catch { resolve(null); } });
  });
}

// ---------------------------------------------------------------- server

export function createServer() {
  return http.createServer(async (req, res) => {
    const send = (code, obj) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(obj)); };
    try {
      // Only this machine's browser may drive the relay.
      if (req.headers.host && !/^(localhost|127\.0\.0\.1)(:\d+)?$/.test(req.headers.host)) return send(403, { ok: false });

      if (req.method === 'GET' && (req.url === '/' || req.url === '/index.html')) {
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-cache' });
        return res.end(await readFile(path.join(here, 'public/sender.html')));
      }
      if (req.method === 'GET' && req.url === '/relay/discover') return send(200, { ok: true, tvs: await discover() });

      if (req.method !== 'POST') return send(404, { ok: false });
      const body = await readJson(req);
      const tv = parseTv(body && body.tv);
      if (!tv) return send(400, { ok: false, error: 'TV address should look like 192.168.1.42' });
      const pin = String(body.pin || '');

      if (req.url === '/relay/check') {
        const r = await tvFetch(tv, '/api/state', { pin });
        return send(200, { ok: r.status === 200, status: r.status, name: r.data && r.data.name });
      }

      if (req.url === '/relay/offer') {
        if (typeof body.sdp !== 'string' || !body.sdp.startsWith('v=0')) return send(400, { ok: false, error: 'bad sdp' });
        const offer = unmaskSdp(body.sdp, myIpFor(tv.host));
        const o = await tvFetch(tv, '/api/mirror/offer', { pin, method: 'POST', body: { sdp: offer } });
        if (o.status === 401) return send(401, { ok: false, error: 'Wrong PIN' });
        if (o.status !== 200) return send(502, { ok: false, error: 'TV refused (' + o.status + ')' });
        const id = o.data.id;
        const until = Date.now() + 20000;
        while (Date.now() < until) {
          await new Promise((r) => setTimeout(r, 300));
          const a = await tvFetch(tv, '/api/mirror/answer?id=' + encodeURIComponent(id), { pin });
          if (a.status === 200 && a.data.sdp) return send(200, { ok: true, id, sdp: unmaskSdp(a.data.sdp, tv.host) });
          if (a.status !== 200) return send(502, { ok: false, error: 'TV dropped the session' });
        }
        return send(504, { ok: false, error: 'TV did not answer. Is NamasteCast open on the TV?' });
      }

      if (req.url === '/relay/stop') {
        await tvFetch(tv, '/api/mirror/stop', { pin, method: 'POST', body: {} }).catch(() => {});
        return send(200, { ok: true });
      }

      if (req.url === '/relay/cmd') {
        const r = await tvFetch(tv, '/api/cmd', { pin, method: 'POST', body: body.cmd });
        return send(r.status, r.data || { ok: false });
      }
      send(404, { ok: false });
    } catch (e) {
      send(502, { ok: false, error: e.name === 'AbortError' ? 'TV not reachable' : String(e.message || e) });
    }
  });
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  const port = Number(process.env.PORT || 8766);
  createServer().listen(port, '127.0.0.1', () => {
    const url = `http://localhost:${port}`;
    console.log(`NamasteCast mirror: ${url}  (Ctrl+C to quit)`);
    if (!process.env.NO_OPEN) {
      const cmd = process.platform === 'darwin' ? 'open' : process.platform === 'win32' ? 'start ""' : 'xdg-open';
      exec(`${cmd} ${url}`, () => {});
    }
  });
}
