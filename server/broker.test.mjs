import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createBroker } from './broker.mjs';

const proof = verifier => createHash('sha256').update(verifier).digest('base64url');
async function setup(t, overrides = {}) {
  const calls = [];
  const server = createBroker({ baseUrl: 'https://accounts.runzone.example', clients: { strava: { id: 'app-client', secret: 'server-only-secret' }, smashrun: { id: 'smash-client', secret: 'smash-secret' }, runalyze: { id: 'run-client', secret: 'run-secret' } }, fetchImpl: async (url, options) => { calls.push({ url, fields: Object.fromEntries(options.body) }); return new Response(JSON.stringify({ access_token: `access-${calls.length}`, refresh_token: `refresh-${calls.length}`, expires_in: 3600 }), { status: 200 }); }, ...overrides });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const base = `http://127.0.0.1:${server.address().port}`;
  const post = (path, body) => fetch(base + path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  return { server, base, post, calls };
}
test('different users have isolated proof-bound, one-use sign-ins', async t => {
  const { base, post, calls } = await setup(t);
  const alice = 'a'.repeat(43), bob = 'b'.repeat(43);
  const first = await (await post('/v1/oauth/start', { provider: 'strava', challenge: proof(alice) })).json();
  const second = await (await post('/v1/oauth/start', { provider: 'strava', challenge: proof(bob) })).json();
  const a = new URL(first.authorize_url), b = new URL(second.authorize_url);
  assert.notEqual(a.searchParams.get('state'), b.searchParams.get('state'));
  assert.equal(a.searchParams.get('redirect_uri'), 'https://accounts.runzone.example/oauth/callback/strava');
  assert.ok(!first.authorize_url.includes('server-only-secret'));
  const callback = await fetch(`${base}/oauth/callback/strava?state=${a.searchParams.get('state')}&code=alice-code&scope=activity:write`, { redirect: 'manual' });
  const location = callback.headers.get('location');
  assert.ok(location.startsWith('runzone://accounts/strava?ticket='));
  assert.ok(!location.includes('access-'));
  const ticket = new URL(location).searchParams.get('ticket');
  assert.equal((await post('/v1/oauth/finish', { provider: 'strava', ticket, verifier: bob })).status, 400);
  const tokens = await (await post('/v1/oauth/finish', { provider: 'strava', ticket, verifier: alice })).json();
  assert.equal(tokens.access_token, 'access-1');
  assert.equal(calls[0].fields.client_secret, 'server-only-secret');
  assert.equal((await post('/v1/oauth/finish', { provider: 'strava', ticket, verifier: alice })).status, 400);
  assert.equal((await fetch(`${base}/oauth/callback/strava?state=${a.searchParams.get('state')}&code=replay`)).status, 400);
});
test('forged state and wrong-provider callbacks are rejected without token exchange', async t => {
  const { base, post, calls } = await setup(t);
  const auth = await (await post('/v1/oauth/start', { provider: 'runalyze', challenge: proof('c'.repeat(43)) })).json();
  const state = new URL(auth.authorize_url).searchParams.get('state');
  assert.equal((await fetch(`${base}/oauth/callback/runalyze?state=forged&code=bad`)).status, 400);
  assert.equal((await fetch(`${base}/oauth/callback/strava?state=${state}&code=bad`)).status, 400);
  assert.equal(calls.length, 0);
});
test('authorization expires and public health data contains no credentials', async t => {
  let now = 1000;
  const { base, post } = await setup(t, { now: () => now });
  const auth = await (await post('/v1/oauth/start', { provider: 'smashrun', challenge: proof('d'.repeat(43)) })).json();
  now += 601000;
  assert.equal((await fetch(`${base}/oauth/callback/smashrun?state=${new URL(auth.authorize_url).searchParams.get('state')}&code=expired`)).status, 400);
  const health = await (await fetch(base + '/health')).text();assert.ok(!health.includes('secret'));assert.ok(!health.includes('app-client'));
});
test('refresh rotation returns only account tokens, never the app secret', async t => {
  const { post, calls } = await setup(t);
  const response = await post('/v1/oauth/refresh', { provider: 'runalyze', refresh_token: 'users-refresh-token' });
  assert.equal(response.status, 200);const data = await response.json();assert.equal(data.refresh_token, 'refresh-1');assert.ok(!JSON.stringify(data).includes('run-secret'));assert.equal(calls[0].fields.refresh_token, 'users-refresh-token');
});
test('a shared build can report provider setup pending instead of offering fake sign-in', async t => {
  const { post } = await setup(t, { clients: {} });
  assert.equal((await post('/v1/oauth/start', { provider: 'strava', challenge: proof('a'.repeat(43)) })).status, 503);
});
test('denied upload scope does not obtain a Strava token', async t => {
  const { base, post, calls } = await setup(t);
  const auth = await (await post('/v1/oauth/start', { provider: 'strava', challenge: proof('a'.repeat(43)) })).json();
  const result = await fetch(`${base}/oauth/callback/strava?state=${new URL(auth.authorize_url).searchParams.get('state')}&code=code&scope=read`, { redirect: 'manual' });
  assert.ok(result.headers.get('location').includes('upload_permission_required'));assert.equal(calls.length, 0);
});
test('temporary token service failure is retryable, invalid refresh token needs reconnect', async t => {
  let status = 503;
  const { post } = await setup(t, { fetchImpl: async () => new Response('{}', { status }) });
  assert.equal((await post('/v1/oauth/refresh', { provider: 'strava', refresh_token: 'refresh' })).status, 502);
  status = 400;
  assert.equal((await post('/v1/oauth/refresh', { provider: 'strava', refresh_token: 'refresh' })).status, 401);
});
