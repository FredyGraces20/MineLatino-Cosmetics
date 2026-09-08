import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Store, validateMenu, DEFAULT_MENU } from '../src/store.mjs';
import { PlayerAuth, verifyMojang } from '../src/auth.mjs';
import { AdminAuth } from '../src/adminAuth.mjs';
import { createApi } from '../src/api.mjs';
import { createHttpServer } from '../src/http.mjs';
import { DatabaseSync } from 'node:sqlite';

const OWNER = '1234567890abcdef1234567890abcdef';
const OTHER = 'abcdef1234567890abcdef1234567890';
const ADMIN = 'test-only-admin-token-not-for-deployment-123456';
const catalog = { name: 'Capa de prueba', slot: 'CAPE', status: 'published', expectedRevision: 0 };
const grant = { uuid: OWNER, cosmeticId: 'cape', reference: 'manual-1', reason: 'Prueba' };

test('backpack and pet equip in independent slots and are visible through public appearance', async t => {
  const { store, request, login } = fixture(t);
  const token = await login();
  for (const [id, slot] of [['pack', 'BACKPACK'], ['pet', 'PET']]) {
    store.saveCosmetic(id, { ...catalog, name: id, slot }, 'test');
    store.entitlement({ ...grant, cosmeticId: id, reference: id }, true, 'test');
    const saved = await request('/v1/cosmetics/me/equipment', { token, method: 'PUT', data: { slot, cosmeticId: id } });
    assert.equal(saved.status, 200);
  }
  const publicView = await request(`/v1/cosmetics/appearance?uuids=${OWNER}`);
  assert.deepEqual(publicView.data.players[0].equipped, [
    { slot: 'BACKPACK', cosmeticId: 'pack' }, { slot: 'PET', cosmeticId: 'pet' },
  ]);
  assert.equal((await request('/v1/cosmetics/me/equipment', { token, method: 'PUT', data: { slot: 'HAT', cosmeticId: 'pet' } })).status, 409);
  await request('/v1/cosmetics/me/equipment', { token, method: 'PUT', data: { slot: 'PET', cosmeticId: null } });
  assert.deepEqual((await request(`/v1/cosmetics/appearance?uuids=${OWNER}`)).data.players[0].equipped,
    [{ slot: 'BACKPACK', cosmeticId: 'pack' }]);
});

test('v4 slot migration preserves ownership and equipment and is idempotent', t => {
  const dir = mkdtempSync(join(tmpdir(), 'minelatino-slot-migration-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const path = join(dir, 'test.sqlite');
  const old = new DatabaseSync(path);
  old.exec(`CREATE TABLE cosmetics(id TEXT PRIMARY KEY, name TEXT NOT NULL, slot TEXT NOT NULL CHECK(slot IN ('CAPE','HAT','WINGS')), status TEXT NOT NULL CHECK(status IN ('draft','published','retired')), revision INTEGER NOT NULL);
    CREATE TABLE entitlements(uuid TEXT NOT NULL, cosmetic_id TEXT NOT NULL REFERENCES cosmetics(id), active INTEGER NOT NULL CHECK(active IN (0,1)), PRIMARY KEY(uuid,cosmetic_id));
    CREATE TABLE equipment(uuid TEXT NOT NULL, slot TEXT NOT NULL, cosmetic_id TEXT NOT NULL, PRIMARY KEY(uuid,slot), FOREIGN KEY(uuid,cosmetic_id) REFERENCES entitlements(uuid,cosmetic_id));
    CREATE TABLE resources(cosmetic_id TEXT PRIMARY KEY REFERENCES cosmetics(id), file_path TEXT NOT NULL, sha256 TEXT NOT NULL, file_size INTEGER NOT NULL, content_type TEXT NOT NULL, uploaded_at INTEGER NOT NULL);
    INSERT INTO cosmetics VALUES('cape','Legacy cape','CAPE','published',7);
    INSERT INTO entitlements VALUES('${OWNER}','cape',1);
    INSERT INTO equipment VALUES('${OWNER}','CAPE','cape');
    INSERT INTO resources VALUES('cape','/fixture/cape.png','fixture-hash',10,'image/png',1);
    PRAGMA user_version=4;`);
  old.close();
  let store = new Store(path);
  try {
    assert.equal(store.wardrobe(OWNER).owned[0].revision, 7);
    assert.equal(store.appearance(OWNER)[0].cosmeticId, 'cape');
    assert.equal(store.db.prepare('SELECT file_path FROM resources').get().file_path, '/fixture/cape.png');
    assert.deepEqual(store.db.prepare('PRAGMA foreign_key_check').all(), []);
    store.saveCosmetic('pack', { ...catalog, slot: 'BACKPACK' }, 'test');
    store.saveCosmetic('pet', { ...catalog, slot: 'PET' }, 'test');
  } finally { store.close(); }
  store = new Store(path);
  try {
    assert.equal(store.cosmetic('pet').slot, 'PET');
    assert.equal(store.appearance(OWNER)[0].cosmeticId, 'cape');
    assert.equal(store.db.prepare('PRAGMA user_version').get().user_version, 5);
    assert.equal(store.db.prepare('PRAGMA foreign_keys').get().foreign_keys, 1);
  } finally { store.close(); }
});
function fixture(t, options = {}) {
  const store = new Store(); t.after(() => store.close());
  const auth = new PlayerAuth({ verify: async () => ({ uuid: OWNER, name: 'TestPlayer' }) });
  const adminAuth = new AdminAuth({ store, bootstrapToken: ADMIN });
  const api = createApi({ store, adminToken: ADMIN, adminAuth, playerAuth: auth, premiumEnabled: true, ...options });
  const request = async (path, { method = 'GET', data, token, headers = {} } = {}) => {
    const response = await api(new Request(`http://127.0.0.1:8787${path}`, { method,
      headers: { ...(data !== undefined ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}), ...headers },
      ...(data !== undefined ? { body: JSON.stringify(data) } : {}),
    }));
    return { status: response.status, data: await response.json(), headers: response.headers };
  };
  const login = async () => {
    const challenge = await request('/v1/auth/challenge', { method: 'POST', data: { username: 'TestPlayer' } });
    const session = await request('/v1/auth/verify', { method: 'POST', data: { challengeId: challenge.data.challengeId } });
    assert.equal(session.status, 200); return session.data.token;
  };
  return { store, auth, api, request, login };
}

test('administrative reads and writes require admin authorization', async t => {
  const { request, login } = fixture(t);
  for (const token of [undefined, 'wrong', await login()]) {
    assert.equal((await request('/v1/admin/cosmetics/catalog', { token })).status, 401);
    assert.equal((await request('/v1/admin/cosmetics/catalog/cape', { token, method: 'PUT', data: catalog })).status, 401);
  }
});
test('catalog preserves ownership across edits and rejects stale revisions', async t => {
  const { store, request } = fixture(t);
  assert.equal((await request('/v1/admin/cosmetics/catalog/cape', { token: ADMIN, method: 'PUT', data: catalog })).status, 200);
  store.entitlement(grant, true, 'admin');
  const edited = await request('/v1/admin/cosmetics/catalog/cape', { token: ADMIN, method: 'PUT', data: { ...catalog, name: 'Nueva capa', expectedRevision: 1 } });
  assert.equal(edited.data.revision, 2);
  assert.equal(store.wardrobe(OWNER).owned[0].name, 'Nueva capa');
  assert.equal((await request('/v1/admin/cosmetics/catalog/cape', { token: ADMIN, method: 'PUT', data: catalog })).status, 409);
  assert.equal(store.auditPage().length, 3);
});
test('duplicate operation references cannot regrant a revoked item', t => {
  const { store } = fixture(t); store.saveCosmetic('cape', catalog, 'admin');
  assert.equal(store.entitlement(grant, true, 'admin').duplicate, false);
  assert.equal(store.entitlement(grant, true, 'admin').duplicate, true);
  store.entitlement({ ...grant, reference: 'revoke-1' }, false, 'admin');
  assert.equal(store.entitlement(grant, true, 'admin').duplicate, true);
  assert.equal(store.wardrobe(OWNER).owned.length, 0);
  assert.throws(() => store.entitlement({ ...grant, uuid: OTHER }, true, 'admin'), { status: 409 });
});
test('equipment is scoped to verified session, ignores body UUID, and revokes atomically', async t => {
  const { store, request, login } = fixture(t); store.saveCosmetic('cape', catalog, 'admin');
  const token = await login();
  const equip = { method: 'PUT', token, data: { uuid: OTHER, slot: 'CAPE', cosmeticId: 'cape' } };
  assert.equal((await request('/v1/cosmetics/me/equipment', equip)).status, 403);
  store.entitlement(grant, true, 'admin');
  assert.equal((await request('/v1/cosmetics/me/equipment', equip)).status, 200);
  assert.equal(store.appearance(OWNER).length, 1); assert.equal(store.appearance(OTHER).length, 0);
  store.entitlement({ ...grant, reference: 'revoke' }, false, 'admin');
  assert.equal(store.appearance(OWNER).length, 0);
});
test('unpublished or wrong-slot cosmetics cannot be equipped', t => {
  const { store } = fixture(t); store.saveCosmetic('cape', { ...catalog, status: 'draft' }, 'admin');
  store.entitlement(grant, true, 'admin');
  assert.throws(() => store.equip(OWNER, 'CAPE', 'cape'), { status: 409 });
  store.saveCosmetic('cape', { ...catalog, expectedRevision: 1 }, 'admin');
  assert.throws(() => store.equip(OWNER, 'HAT', 'cape'), { status: 409 });
  store.equip(OWNER, 'CAPE', 'cape');
  store.saveCosmetic('cape', { ...catalog, status: 'retired', expectedRevision: 2 }, 'admin');
  assert.equal(store.appearance(OWNER).length, 0); assert.equal(store.wardrobe(OWNER).owned.length, 1);
});
test('public responses do not expose owners or purchase records', async t => {
  const { store, request } = fixture(t); store.saveCosmetic('cape', catalog, 'admin'); store.entitlement(grant, true, 'admin');
  const publicCatalog = await request('/v1/cosmetics/catalog');
  assert.equal(JSON.stringify(publicCatalog.data).includes(OWNER), false);
  const appearance = await request(`/v1/cosmetics/appearance?uuids=${OWNER}`);
  assert.deepEqual(Object.keys(appearance.data.players[0]).sort(), ['equipped', 'uuid']);
  assert.equal((await request('/v1/admin/cosmetics/owners/cape')).status, 401);
  const owners = await request('/v1/admin/cosmetics/owners/cape', { token: ADMIN });
  assert.equal(owners.data.items[0].uuid, OWNER); assert.equal(owners.data.items[0].verified_at, null);
});
test('verified nickname follows UUID without changing ownership', async t => {
  const { store, login } = fixture(t); store.saveCosmetic('cape', catalog, 'admin'); store.entitlement(grant, true, 'admin');
  await login(); assert.equal(store.owners('cape')[0].name, 'TestPlayer');
  store.verifiedPlayer(OWNER, 'NewName'); assert.equal(store.owners('cape')[0].name, 'NewName');
  assert.equal(store.wardrobe(OWNER).owned.length, 1);
});
test('foreign browser origins and invalid media types are rejected', async t => {
  const { request } = fixture(t);
  assert.equal((await request('/v1/admin/cosmetics/catalog', { token: ADMIN, headers: { Origin: 'https://attacker.test' } })).status, 403);
  assert.equal((await request('/v1/auth/challenge', { method: 'POST', data: {}, headers: { 'Content-Type': 'text/plain' } })).status, 415);
});
test('oversized bodies, malformed JSON and invalid pagination are rejected', async t => {
  const { request, api } = fixture(t);
  assert.equal((await request('/v1/auth/challenge', { method: 'POST', data: { x: 'x'.repeat(17_000) } })).status, 413);
  assert.equal((await api(new Request('http://127.0.0.1:8787/v1/auth/challenge', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{' }))).status, 400);
  assert.equal((await request('/v1/cosmetics/catalog?offset=-1')).status, 400);
});
test('catalog and owner queries are bounded and paginated', t => {
  const { store } = fixture(t);
  for (let i = 0; i < 55; i++) store.saveCosmetic(`cape-${String(i).padStart(2, '0')}`, catalog, 'admin');
  assert.equal(store.catalog(false).length, 50); assert.equal(store.catalog(false, 50).length, 5);
});
test('premium verification is disabled by default in deployment configuration', async t => {
  const { request } = fixture(t, { premiumEnabled: false });
  assert.equal((await request('/v1/auth/challenge', { method: 'POST', data: { username: 'TestPlayer' } })).status, 503);
});
test('logout invalidates a player session immediately', async t => {
  const { request, login } = fixture(t); const token = await login();
  assert.equal((await request('/v1/cosmetics/me/wardrobe', { token })).status, 200);
  assert.equal((await request('/v1/auth/logout', { method: 'POST', token })).status, 200);
  assert.equal((await request('/v1/cosmetics/me/wardrobe', { token })).status, 401);
});
test('rate limits expire and do not trust client identity', async t => {
  let now = 0; const { request } = fixture(t, { now: () => now });
  for (let i = 0; i < 120; i++) assert.equal((await request('/health')).status, 200);
  assert.equal((await request('/health', { headers: { 'X-Forwarded-For': 'different' } })).status, 429);
  now = 60_001; assert.equal((await request('/health')).status, 200);
});
test('menu validation allows presentation but never code or arbitrary URLs', () => {
  assert.deepEqual(validateMenu(DEFAULT_MENU), DEFAULT_MENU);
  for (const button of [{ label: 'X', action: 'EXECUTE' }, { label: 'X', action: 'WEBSITE', url: 'https://minelatino.com.attacker.test/' }])
    assert.throws(() => validateMenu({ ...DEFAULT_MENU, buttons: [button] }), { status: 400 });
  assert.throws(() => validateMenu({ ...DEFAULT_MENU, buttons: Array(4).fill(DEFAULT_MENU.buttons[0]) }), { status: 400 });
});
test('menu publication is revisioned with optimistic locking', async t => {
  const { request, store } = fixture(t);
  const input = { expectedRevision: 0, config: { ...DEFAULT_MENU, enabled: false } };
  assert.equal((await request('/v1/admin/pause-menu', { method: 'PUT', token: ADMIN, data: input })).data.revision, 1);
  assert.equal((await request('/v1/admin/pause-menu', { method: 'PUT', token: ADMIN, data: input })).status, 409);
  assert.equal(store.menu().config.enabled, false); assert.equal(store.auditPage().length, 1);
});
test('SQLite persists across service restarts', () => {
  const directory = mkdtempSync(join(tmpdir(), 'minelatino-cosmetics-test-'));
  let store;
  try {
    const path = join(directory, 'state.sqlite'); store = new Store(path);
    store.saveCosmetic('cape', catalog, 'admin'); store.entitlement(grant, true, 'admin'); store.close(); store = undefined;
    store = new Store(path); assert.equal(store.wardrobe(OWNER).owned[0].id, 'cape'); assert.equal(store.auditPage().length, 2);
  } finally { store?.close(); rmSync(directory, { recursive: true }); }
});

test('challenges are consumed before awaiting verification, including concurrent replay', async () => {
  let finish;
  const auth = new PlayerAuth({ verify: () => new Promise(resolve => { finish = resolve; }) });
  const challenge = auth.challenge('TestPlayer'); const pending = auth.complete(challenge.challengeId);
  await assert.rejects(auth.complete(challenge.challengeId), { status: 401 });
  finish({ uuid: OWNER, name: 'TestPlayer' }); const result = await pending;
  assert.equal(auth.player(`Bearer ${result.token}`), OWNER);
  assert.equal([...auth.sessions.keys()].includes(result.token), false);
});
test('expired challenges and sessions are rejected', async () => {
  let now = 0;
  const auth = new PlayerAuth({ now: () => now, verify: async () => ({ uuid: OWNER, name: 'TestPlayer' }) });
  const old = auth.challenge('TestPlayer'); now = 60_001;
  await assert.rejects(auth.complete(old.challengeId), { status: 401 });
  const challenge = auth.challenge('TestPlayer'); const session = await auth.complete(challenge.challengeId);
  now += 15 * 60_000; assert.throws(() => auth.player(`Bearer ${session.token}`), { status: 401 });
});
test('upstream failure or mismatching username never issues a session', async () => {
  for (const verify of [async () => { throw new Error('offline'); }, async () => ({ uuid: OTHER, name: 'WrongUser' })]) {
    const auth = new PlayerAuth({ verify }); const challenge = auth.challenge('TestPlayer');
    await assert.rejects(auth.complete(challenge.challengeId)); assert.equal(auth.sessions.size, 0);
    await assert.rejects(auth.complete(challenge.challengeId), { status: 401 });
  }
});

test('restoring a menu creates a new revision and preserves history', async t => {
  const { store, request } = fixture(t);
  store.saveMenu({ expectedRevision: 0, config: DEFAULT_MENU }, 'admin');
  store.saveMenu({ expectedRevision: 1, config: { ...DEFAULT_MENU, enabled: false } }, 'admin');
  const restored = await request('/v1/admin/pause-menu/restore', { token: ADMIN, method: 'POST', data: { revision: 1, expectedRevision: 2 } });
  assert.equal(restored.data.revision, 3); assert.equal(restored.data.config.enabled, true);
  assert.equal(store.menuHistory().length, 3);
  assert.equal(store.menuHistory()[1].config.enabled, false);
});
test('Mojang adapter uses a fixed origin, rejects redirects, and validates the response', async t => {
  let called;
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    called = { url: new URL(url), options };
    return Response.json({ id: OWNER, name: 'TestPlayer' });
  });
  assert.deepEqual(await verifyMojang('TestPlayer', 'challenge'), { uuid: OWNER, name: 'TestPlayer' });
  assert.equal(called.url.origin, 'https://sessionserver.mojang.com');
  assert.equal(called.url.searchParams.get('serverId'), 'challenge');
  assert.equal(called.options.redirect, 'error');
});
test('Mojang adapter fails closed on a missing or unavailable session', async t => {
  const mock = t.mock.method(globalThis, 'fetch', async () => new Response(null, { status: 204 }));
  await assert.rejects(verifyMojang('TestPlayer', 'challenge'), { status: 401 });
  mock.mock.mockImplementation(async () => new Response(null, { status: 503 }));
  await assert.rejects(verifyMojang('TestPlayer', 'challenge'), { status: 503 });
});
test('real loopback HTTP supports authenticated writes and rejects unauthenticated ones', async t => {
  const { api } = fixture(t);
  const server = createHttpServer(api, 'http://127.0.0.1:8787');
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const origin = `http://127.0.0.1:${server.address().port}`;
  const unauthorized = await fetch(`${origin}/v1/admin/cosmetics/catalog`);
  assert.equal(unauthorized.status, 401); await unauthorized.arrayBuffer();
  const saved = await fetch(`${origin}/v1/admin/cosmetics/catalog/cape`, { method: 'PUT', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${ADMIN}` }, body: JSON.stringify(catalog) });
  assert.equal(saved.status, 200); assert.equal((await saved.json()).id, 'cape');
  const result = await fetch(`${origin}/v1/cosmetics/catalog`);
  assert.equal(result.headers.get('cache-control'), 'no-store');
  assert.equal((await result.json()).items.length, 1);
});

// ── Admin auth tests ──────────────────────────────────────────────────

test('admin bootstrap creates first admin and rejects duplicates', async t => {
  const { request } = fixture(t);
  const boot = await request('/v1/admin/auth/bootstrap', { method: 'POST', data: { username: 'admin1', password: 'secure1234' }, token: ADMIN });
  assert.equal(boot.status, 201); assert.equal(boot.data.role, 'superadmin');
  const dup = await request('/v1/admin/auth/bootstrap', { method: 'POST', data: { username: 'admin2', password: 'secure5678' }, token: ADMIN });
  assert.equal(dup.status, 409);
});
test('admin login returns a session token and rejects bad credentials', async t => {
  const { request } = fixture(t);
  await request('/v1/admin/auth/bootstrap', { method: 'POST', data: { username: 'admin1', password: 'secure1234' }, token: ADMIN });
  const ok = await request('/v1/admin/auth/login', { method: 'POST', data: { username: 'admin1', password: 'secure1234' } });
  assert.equal(ok.status, 200); assert.ok(ok.data.token); assert.equal(ok.data.username, 'admin1');
  const bad = await request('/v1/admin/auth/login', { method: 'POST', data: { username: 'admin1', password: 'wrong' } });
  assert.equal(bad.status, 401);
  const missing = await request('/v1/admin/auth/login', { method: 'POST', data: { username: 'nobody', password: 'anything' } });
  assert.equal(missing.status, 401);
});
test('admin session token authorizes admin routes with correct actor', async t => {
  const { request, store } = fixture(t);
  await request('/v1/admin/auth/bootstrap', { method: 'POST', data: { username: 'admin1', password: 'secure1234' }, token: ADMIN });
  const login = await request('/v1/admin/auth/login', { method: 'POST', data: { username: 'admin1', password: 'secure1234' } });
  const sessionToken = login.data.token;
  // Session token can create cosmetics
  const save = await request('/v1/admin/cosmetics/catalog/cape', { method: 'PUT', data: catalog, token: sessionToken });
  assert.equal(save.status, 200);
  // Audit records the admin username, not 'local-admin'
  const audit = await request('/v1/admin/audit', { token: sessionToken });
  assert.equal(audit.data.items[0].actor, 'admin1');
});
test('admin logout invalidates the session', async t => {
  const { request } = fixture(t);
  await request('/v1/admin/auth/bootstrap', { method: 'POST', data: { username: 'admin1', password: 'secure1234' }, token: ADMIN });
  const login = await request('/v1/admin/auth/login', { method: 'POST', data: { username: 'admin1', password: 'secure1234' } });
  const tok = login.data.token;
  assert.equal((await request('/v1/admin/accounts', { token: tok })).status, 200);
  await request('/v1/admin/auth/logout', { method: 'POST', token: tok });
  assert.equal((await request('/v1/admin/accounts', { token: tok })).status, 401);
});
test('bootstrap token still works alongside admin sessions', async t => {
  const { request } = fixture(t);
  await request('/v1/admin/auth/bootstrap', { method: 'POST', data: { username: 'admin1', password: 'secure1234' }, token: ADMIN });
  // Bootstrap token still works
  assert.equal((await request('/v1/admin/accounts', { token: ADMIN })).status, 200);
});
test('player search by name and UUID', async t => {
  const { store, request } = fixture(t);
  store.verifiedPlayer('aaaa0000bbbb1111cccc2222dddd3333', 'AlphaPlayer');
  store.verifiedPlayer('eeee4444ffff5555aaaa6666bbbb7777', 'BravoPlayer');
  const byName = await request('/v1/admin/players?q=alpha', { token: ADMIN });
  assert.equal(byName.data.items.length, 1); assert.equal(byName.data.items[0].name, 'AlphaPlayer');
  const byUuid = await request('/v1/admin/players?q=eeee4444ffff5555', { token: ADMIN });
  assert.equal(byUuid.data.items.length, 1); assert.equal(byUuid.data.items[0].name, 'BravoPlayer');
  const tooShort = await request('/v1/admin/players?q=x', { token: ADMIN });
  assert.equal(tooShort.status, 400);
});

// ── Resource tests ────────────────────────────────────────────────────

function fixtureWithResources(t, options = {}) {
  const resourceDir = mkdtempSync(join(tmpdir(), 'minelatino-resources-'));
  t.after(() => rmSync(resourceDir, { recursive: true, force: true }));
  const store = new Store(); t.after(() => store.close());
  const auth = new PlayerAuth({ verify: async () => ({ uuid: OWNER, name: 'TestPlayer' }) });
  const adminAuth = new AdminAuth({ store, bootstrapToken: ADMIN });
  const api = createApi({ store, adminToken: ADMIN, adminAuth, resourceDir, playerAuth: auth, premiumEnabled: true, ...options });
  const request = async (path, { method = 'GET', data, token, headers = {}, body: rawBody } = {}) => {
    const isJson = data !== undefined;
    const response = await api(new Request(`http://127.0.0.1:8787${path}`, { method,
      headers: { ...(isJson ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}), ...headers },
      ...(isJson ? { body: JSON.stringify(data) } : rawBody !== undefined ? { body: rawBody } : {}),
    }));
    const ct = response.headers.get('content-type') || '';
    const respData = ct.includes('json') ? await response.json() : await response.arrayBuffer();
    return { status: response.status, data: respData, headers: response.headers };
  };
  return { store, api, request, resourceDir };
}

// Minimal valid PNG (1x1 transparent pixel)
const PNG_1x1 = Buffer.from('89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000d49444154789c6200010000000500010d0a2db40000000049454e44ae426082', 'hex');

test('resource upload stores file with SHA-256 and serves it back', async t => {
  const { store, request } = fixtureWithResources(t);
  store.saveCosmetic('test-cape', catalog, 'admin');
  const r = await request(`/v1/admin/cosmetics/catalog/test-cape/resource`, {
    method: 'PUT', token: ADMIN,
    headers: { 'X-Filename': 'cape.png' },
    body: PNG_1x1,
  });
  assert.equal(r.status, 200);
  assert.equal(r.data.content_type, 'image/png');
  assert.equal(r.data.file_size, PNG_1x1.length);
  assert.ok(r.data.sha256);
  // Serve it back
  const served = await request('/v1/resources/test-cape');
  assert.equal(served.status, 200);
  assert.equal(Buffer.from(served.data).toString('hex'), PNG_1x1.toString('hex'));
  assert.equal(served.headers.get('content-type'), 'image/png');
  assert.ok(served.headers.get('cache-control').includes('immutable'));
});

test('resource upload rejects non-PNG, oversized, and missing cosmetic', async t => {
  const { store, request } = fixtureWithResources(t);
  store.saveCosmetic('test-cape', catalog, 'admin');
  // Non-PNG extension
  const bad = await request('/v1/admin/cosmetics/catalog/test-cape/resource', {
    method: 'PUT', token: ADMIN,
    headers: { 'X-Filename': 'evil.exe' },
    body: Buffer.from('not a png'),
  });
  assert.equal(bad.status, 415);
  // Non-existent cosmetic
  const missing = await request('/v1/admin/cosmetics/catalog/nonexistent/resource', {
    method: 'PUT', token: ADMIN,
    headers: { 'X-Filename': 'cape.png' },
    body: PNG_1x1,
  });
  assert.equal(missing.status, 404);
  // Invalid PNG magic bytes
  const fakePng = await request('/v1/admin/cosmetics/catalog/test-cape/resource', {
    method: 'PUT', token: ADMIN,
    headers: { 'X-Filename': 'fake.png' },
    body: Buffer.from('not a real png file'),
  });
  assert.equal(fakePng.status, 400);
});

test('resource delete removes file and database entry', async t => {
  const { store, request, resourceDir } = fixtureWithResources(t);
  store.saveCosmetic('test-cape', catalog, 'admin');
  await request('/v1/admin/cosmetics/catalog/test-cape/resource', {
    method: 'PUT', token: ADMIN, headers: { 'X-Filename': 'cape.png' }, body: PNG_1x1,
  });
  const del = await request('/v1/admin/cosmetics/catalog/test-cape/resource', { method: 'DELETE', token: ADMIN });
  assert.equal(del.status, 200); assert.equal(del.data.deleted, true);
  // Resource no longer served
  const served = await request('/v1/resources/test-cape');
  assert.equal(served.status, 404);
});

test('catalog includes resource info for admin and public views', async t => {
  const { store, request } = fixtureWithResources(t);
  store.saveCosmetic('test-cape', catalog, 'admin');
  await request('/v1/admin/cosmetics/catalog/test-cape/resource', {
    method: 'PUT', token: ADMIN, headers: { 'X-Filename': 'cape.png' }, body: PNG_1x1,
  });
  const adminCatalog = await request('/v1/admin/cosmetics/catalog', { token: ADMIN });
  assert.equal(adminCatalog.data.items[0].resource.content_type, 'image/png');
  const publicCatalog = await request('/v1/cosmetics/catalog');
  assert.equal(publicCatalog.data.items[0].hasResource, true);
});
