import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from '../server.js';

async function withServer(fn) {
  const s = createServer();
  await new Promise((r) => s.listen(0, r));
  const base = `http://127.0.0.1:${s.address().port}`;
  try { await fn(base, s.tv); } finally { s.close(); }
}

test('mock TV mirrors the real API contract', () => withServer(async (base, tv) => {
  assert.equal((await fetch(base + '/api/state')).status, 401);
  assert.equal((await fetch(base + '/api/state', { headers: { 'X-Pin': '1234' } })).status, 200);
  const r = await fetch(base + '/api/cmd', {
    method: 'POST', headers: { 'X-Pin': '1234', 'Content-Type': 'application/json' },
    body: JSON.stringify({ type: 'video', action: 'toggle' }),
  });
  assert.equal(r.status, 200);
  assert.equal(tv.state.video.paused, false);
  const html = await (await fetch(base + '/')).text();
  assert.match(html, /NamasteCast/);
}));
