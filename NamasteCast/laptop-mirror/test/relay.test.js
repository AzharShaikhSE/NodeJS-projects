import { test } from 'node:test';
import http from 'node:http';
import assert from 'node:assert/strict';
import { parseTv, unmaskSdp, myIpFor, createServer } from '../server.js';

test('parseTv only accepts private LAN IPv4', () => {
  assert.deepEqual(parseTv('192.168.1.42'), { host: '192.168.1.42', port: 8765 });
  assert.deepEqual(parseTv(' 10.0.0.7:8766 '), { host: '10.0.0.7', port: 8766 });
  assert.deepEqual(parseTv('172.20.1.2'), { host: '172.20.1.2', port: 8765 });
  for (const bad of ['8.8.8.8', '127.0.0.1', '169.254.1.1', '172.32.0.1', 'evil.com', '192.168.1.300', '', null, 'http://192.168.1.2'])
    assert.equal(parseTv(bad, false), null, String(bad));
});

test('unmaskSdp swaps mDNS candidate names for the real IP only', () => {
  const sdp = 'v=0\r\na=candidate:1 1 udp 2122260223 3f2a9b1c-1111-2222-3333-444455556666.local 54321 typ host generation 0\r\n' +
    'a=candidate:2 1 udp 2122260223 192.168.1.9 50000 typ host\r\na=note abc.local x\r\n';
  const out = unmaskSdp(sdp, '192.168.1.20');
  assert.match(out, /udp 2122260223 192\.168\.1\.20 54321 typ host/);
  assert.match(out, /192\.168\.1\.9 50000/);
  assert.match(out, /a=note abc\.local x/);
  assert.equal(unmaskSdp(sdp, null), sdp);
});

test('myIpFor picks the laptop IP on the TV subnet', () => {
  assert.equal(myIpFor('192.168.1.42', ['10.0.0.5', '192.168.1.20']), '192.168.1.20');
  assert.equal(myIpFor('192.168.9.42', ['10.0.0.5']), '10.0.0.5');
});

test('relay refuses requests not addressed to localhost (DNS rebinding guard)', async () => {
  const s = createServer(); await new Promise((r) => s.listen(0, '127.0.0.1', r));
  const port = s.address().port;
  try {
    // fetch() drops custom Host headers, so use raw http to simulate a rebinding page.
    const badStatus = await new Promise((resolve, reject) => {
      http.get({ host: '127.0.0.1', port, path: '/', headers: { Host: 'evil.example:80' } }, (r) => { r.resume(); resolve(r.statusCode); }).on('error', reject);
    });
    assert.equal(badStatus, 403);
    const ok = await fetch(`http://127.0.0.1:${port}/`);
    assert.equal(ok.status, 200);
    const noTv = await fetch(`http://127.0.0.1:${port}/relay/offer`, { method: 'POST', body: JSON.stringify({ tv: '8.8.8.8', pin: '1', sdp: 'v=0' }) });
    assert.equal(noTv.status, 400);
  } finally { s.close(); }
});
