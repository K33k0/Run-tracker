import http from 'node:http';
import { randomBytes, createHash, timingSafeEqual } from 'node:crypto';

export const providers = {
  strava: { authorize: 'https://www.strava.com/oauth/mobile/authorize', token: 'https://www.strava.com/oauth/token', scope: 'activity:write', revoke: 'https://www.strava.com/oauth/revoke' },
  smashrun: { authorize: 'https://secure.smashrun.com/oauth2/authenticate', token: 'https://secure.smashrun.com/oauth2/token', scope: 'write_activity' },
  runalyze: { authorize: 'https://runalyze.com/oauth/v2/auth', token: 'https://runalyze.com/oauth/v2/token', scope: 'activity_push', revoke: 'https://runalyze.com/oauth/v2/revoke' },
};
export function createBroker({ baseUrl, clients = {}, fetchImpl = fetch, now = Date.now }) {
  const base = new URL(baseUrl);
  if (base.protocol !== 'https:' && !['localhost', '127.0.0.1'].includes(base.hostname)) throw new Error('Public OAuth URL must use HTTPS');
  if (base.pathname !== '/' || base.search || base.hash || base.username || base.password) throw new Error('Public OAuth URL must be an origin without a path or credentials');
  const states = new Map(), tickets = new Map(), rates = new Map();
  const ttl = 10 * 60 * 1000;
  const id = () => randomBytes(32).toString('base64url');
  const challenge = verifier => createHash('sha256').update(verifier).digest('base64url');
  function json(res, code, value) { res.writeHead(code, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer' }); res.end(JSON.stringify(value)); }
  function redirect(res, provider, parameters) { const url = new URL(`runzone://accounts/${provider}`); for (const [key, value] of Object.entries(parameters)) url.searchParams.set(key, value); res.writeHead(302, { Location: url.href, 'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer' }); res.end(); }
  async function body(req) { if (!(req.headers['content-type'] || '').startsWith('application/json')) throw new Error('json'); let text = ''; for await (const chunk of req) { text += chunk; if (text.length > 32768) throw new Error('large'); } return JSON.parse(text); }
  async function exchange(provider, fields) {
    const client = clients[provider], config = providers[provider];
    const form = new URLSearchParams({ ...fields, client_id: client.id, client_secret: client.secret });
    const response = await fetchImpl(config.token, { method: 'POST', body: form, signal: AbortSignal.timeout(20000), headers: { Accept: 'application/json' }, redirect: 'error' });
    if (!response.ok) { const error = new Error('exchange'); error.status = response.status; throw error; }
    const result = await response.json();
    if (typeof result.access_token !== 'string' || !result.access_token) throw new Error('token');
    return { access_token: result.access_token, refresh_token: result.refresh_token || fields.refresh_token || '', expires_at: result.expires_at || Math.floor(now() / 1000) + (result.expires_in || 3600), scope: result.scope || '' };
  }
  return http.createServer(async (req, res) => {
    try {
      for (const store of [states, tickets, rates]) for (const [key, value] of store) if (value.until < now()) store.delete(key);
      const url = new URL(req.url, base);
      if (req.method === 'GET' && url.pathname === '/health') return json(res, 200, { service: 'RunZone accounts', providers: Object.fromEntries(Object.keys(providers).map(p => [p, !!(clients[p]?.id && clients[p]?.secret)])) });
      const address = req.socket.remoteAddress || 'unknown';
      const rate = rates.get(address) || { count: 0, until: now() + 60000 };
      rate.count++; rates.set(address, rate);
      if (rate.count > 120 || rates.size > 10000) return json(res, 429, { error: 'Please try again shortly' });
      if (req.method === 'GET' && url.pathname.startsWith('/oauth/callback/')) {
        const provider = url.pathname.split('/').pop(), state = url.searchParams.get('state'), pending = states.get(state);
        if (!pending || pending.provider !== provider) return json(res, 400, { error: 'Authorization expired or invalid' });
        states.delete(state);
        if (url.searchParams.has('error')) return redirect(res, provider, { error: 'access_denied' });
        if (!url.searchParams.get('code')) return redirect(res, provider, { error: 'authorization_failed' });
        if (provider === 'strava' && !(url.searchParams.get('scope') || '').split(/[ ,]+/).includes('activity:write')) return redirect(res, provider, { error: 'upload_permission_required' });
        try {
          const tokens = await exchange(provider, { grant_type: 'authorization_code', code: url.searchParams.get('code'), redirect_uri: `${base.origin}/oauth/callback/${provider}` });
          const ticket = id(); tickets.set(ticket, { ...pending, tokens, until: now() + ttl });
          return redirect(res, provider, { ticket });
        } catch { return redirect(res, provider, { error: 'authorization_failed' }); }
      }
      if (req.method !== 'POST') return json(res, 404, { error: 'Not found' });
      const input = await body(req), provider = input.provider;
      if (!providers[provider] || !clients[provider]?.id || !clients[provider]?.secret) return json(res, 503, { error: 'This account connection is not configured yet' });
      if (url.pathname === '/v1/oauth/start') {
        if (typeof input.challenge !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(input.challenge)) return json(res, 400, { error: 'Invalid proof challenge' });
        if (states.size + tickets.size >= 10000) return json(res, 503, { error: 'Please try again shortly' });
        const state = id(); states.set(state, { provider, challenge: input.challenge, until: now() + ttl });
        const authorize = new URL(providers[provider].authorize);
        for (const [key, value] of Object.entries({ client_id: clients[provider].id, redirect_uri: `${base.origin}/oauth/callback/${provider}`, response_type: 'code', scope: providers[provider].scope, state })) authorize.searchParams.set(key, value);
        return json(res, 200, { authorize_url: authorize.href });
      }
      if (url.pathname === '/v1/oauth/finish') {
        const pending = tickets.get(input.ticket);
        if (!pending || pending.provider !== provider || typeof input.verifier !== 'string' || !/^[A-Za-z0-9_-]{43,128}$/.test(input.verifier)) return json(res, 400, { error: 'Authorization expired or invalid' });
        const a = Buffer.from(challenge(input.verifier)), b = Buffer.from(pending.challenge);
        if (a.length !== b.length || !timingSafeEqual(a, b)) return json(res, 400, { error: 'Invalid authorization proof' });
        tickets.delete(input.ticket); return json(res, 200, pending.tokens);
      }
      if (url.pathname === '/v1/oauth/refresh') {
        if (typeof input.refresh_token !== 'string' || !input.refresh_token || input.refresh_token.length > 8192) return json(res, 400, { error: 'Reconnect this account' });
        try { return json(res, 200, await exchange(provider, { grant_type: 'refresh_token', refresh_token: input.refresh_token })); }
        catch (error) { return json(res, [400, 401, 403].includes(error.status) ? 401 : 502, { error: [400, 401, 403].includes(error.status) ? 'Reconnect this account' : 'Account service temporarily unavailable' }); }
      }
      if (url.pathname === '/v1/oauth/revoke') {
        if (typeof input.token !== 'string' || !input.token || input.token.length > 8192) return json(res, 400, { error: 'Missing token' });
        const config = providers[provider];
        if (!config.revoke) return json(res, 200, { revoke_in_account_settings: true });
        const headers = { 'Content-Type': 'application/x-www-form-urlencoded' }, fields = { token: input.token, token_type_hint: 'refresh_token' };
        if (provider === 'strava') headers.Authorization = `Basic ${Buffer.from(`${clients[provider].id}:${clients[provider].secret}`).toString('base64')}`;
        else { fields.client_id = clients[provider].id; fields.client_secret = clients[provider].secret; }
        try { const response = await fetchImpl(config.revoke, { method: 'POST', headers, body: new URLSearchParams(fields), signal: AbortSignal.timeout(20000), redirect: 'error' }); return json(res, response.ok ? 200 : 502, { revoked: response.ok }); }
        catch { return json(res, 502, { revoked: false }); }
      }
      return json(res, 404, { error: 'Not found' });
    } catch { if (!res.headersSent) json(res, 400, { error: 'Invalid request' }); else res.end(); }
  });
}
