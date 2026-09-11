import test from 'node:test';
import assert from 'node:assert/strict';
import { Store } from '../src/store.mjs';
import { Commerce } from '../src/commerce.mjs';
import { createApi } from '../src/api.mjs';
import { AccountAuth } from '../src/accountAuth.mjs';
const owner = { uuid: '1234567890abcdef1234567890abcdef', premiumVerified: true };
const product = { description: 'Una mochila', amountMinor: 999, currency: 'USD' };
function fixture(t) {
  const store = new Store(); t.after(() => store.close());
  store.saveCosmetic('pack', { name: 'Mochila', slot: 'BACKPACK', status: 'published', expectedRevision: 0, product }, 'test');
  return { store, commerce: new Commerce(store) };
}
test('product metadata preserves old clients and rejects invalid prices', t => {
  const { store } = fixture(t);
  store.saveCosmetic('pack', { name: 'Nueva', slot: 'BACKPACK', status: 'published', expectedRevision: 1 }, 'old-client');
  assert.deepEqual(store.product('pack'), product);
  for (const amountMinor of [-1, 1.5, 0, '999']) assert.throws(() => store.saveCosmetic('pack', { name: 'Bad', slot: 'BACKPACK', status: 'published', expectedRevision: 2, product: { ...product, amountMinor } }, 'test'));
  assert.equal(store.cosmetic('pack').revision, 2);
});
test('public storefront exposes only published products and disables unconfigured providers', async t => {
  const { store } = fixture(t);
  store.saveTransform('pack', 'backpack', { translation: [8, 4, -2], rotation: [0, 15, 0], scale: [1.2, 1.2, 1.2] }, 'test');
  store.saveCosmetic('draft', { name: 'Draft', slot: 'HAT', status: 'draft', expectedRevision: 0 }, 'test');
  const commerce = new Commerce(store);
  const api = createApi({ store, commerce, adminToken: 'test-only-key-with-at-least-32-characters' });
  const response = await api(new Request('http://localhost/v1/storefront/catalog', { headers: { Origin: 'http://localhost:3000' } }));
  assert.equal(response.status, 200); assert.equal(response.headers.get('access-control-allow-origin'), '*');
  const page = await response.json(); assert.equal(page.items.length, 1); assert.equal(page.items[0].amountMinor, 999); assert.equal(page.nextOffset, null);
  assert.deepEqual(page.items[0].transform.translation, [8, 4, -2]);
  const config = await (await api(new Request('http://localhost/v1/storefront/payments'))).json();
  assert.equal(config.checkoutEnabled, true); assert.equal(config.providers.find(p => p.id === 'manual').enabled, true);
  assert.ok(config.providers.filter(p => p.id !== 'manual').every(p => !p.enabled));
  assert.equal((await api(new Request('http://localhost/v1/storefront/checkout', { method: 'POST' }))).status, 410);
  assert.equal((await api(new Request('http://localhost/v1/admin/cosmetics/catalog', { headers: { Origin: 'http://localhost:3000' } }))).status, 403);
});
test('settlement is atomic, idempotent and grants ownership used by the mod', t => {
  const { store, commerce } = fixture(t);
  assert.throws(() => commerce.createOrder({ ...owner, premiumVerified: false }, 'pack', 'paypal', 'unique-request-0001'));
  const order = commerce.createOrder(owner, 'pack', 'manual', 'unique-request-0001');
  assert.equal(commerce.createOrder(owner, 'pack', 'manual', 'unique-request-0001').id, order.id);
  assert.throws(() => commerce.createOrder(owner, 'pack', 'paypal', 'unique-request-0002'));
  const payment = { orderId: order.id, provider: 'manual', paymentId: 'provider-transaction-1', amountMinor: 999, currency: 'USD', status: 'approved' };
  for (const patch of [{ amountMinor: 1 }, { currency: 'ARS' }, { status: 'pending' }, { provider: 'binance' }]) assert.throws(() => commerce.settleVerified({ ...payment, ...patch }));
  assert.equal(store.wardrobe(owner.uuid).owned.length, 0);
  assert.equal(commerce.settleVerified(payment).duplicate, false);
  assert.equal(commerce.settleVerified(payment).duplicate, true);
  assert.equal(store.wardrobe(owner.uuid).owned[0].id, 'pack');
  store.equip(owner.uuid, 'BACKPACK', 'pack');
  assert.equal(store.appearance(owner.uuid)[0].cosmeticId, 'pack');
});

test('products with order history must be retired instead of deleted', t => {
  const { store, commerce } = fixture(t);
  commerce.createOrder(owner, 'pack', 'manual', 'delete-protected-order');
  assert.throws(() => store.deleteCosmetic('pack', 1, 'admin'), { status: 409 });
  assert.equal(store.cosmetic('pack').name, 'Mochila');
});

test('pending legacy skin orders cannot deliver removed products', t => {
  const { store, commerce } = fixture(t);
  const order = commerce.createOrder(owner, 'pack', 'manual', 'legacy-skin-order-01');
  store.db.prepare("UPDATE cosmetics SET slot='SKIN' WHERE id='pack'").run();
  assert.throws(() => commerce.settleVerified({ orderId: order.id, provider: 'manual', paymentId: 'legacy-payment', amountMinor: 999, currency: 'USD', status: 'approved' }), { status: 404 });
  assert.equal(store.db.prepare('SELECT status FROM cosmetic_orders WHERE id=?').get(order.id).status, 'pending');
  assert.equal(store.db.prepare('SELECT COUNT(*) AS n FROM entitlements').get().n, 0);
});

test('offline MineLatino accounts can purchase without a premium UUID', t => {
  const { store, commerce } = fixture(t);
  const accountId = 'abcdefabcdefabcdefabcdefabcdefab';
  store.createPlayerAccount({ accountId, email: 'offline@example.com', nick: 'OfflineBuyer',
    passwordHash: 'a'.repeat(64), passwordSalt: 'b'.repeat(32) });
  const order = commerce.createOrder({ accountId }, 'pack', 'manual', 'offline-purchase-001');
  assert.equal(order.ownerType, 'account');
  commerce.settleVerified({ orderId: order.id, provider: 'manual', paymentId: 'offline-payment-1', amountMinor: 999, currency: 'USD', status: 'approved' });
  assert.equal(store.accountWardrobe(accountId).owned[0].id, 'pack');
});

test('authenticated order API lists, cancels and delivers an offline account order', async t => {
  const { store, commerce } = fixture(t);
  const accountAuth = new AccountAuth({ store });
  const session = await accountAuth.register({ email: 'buyer@example.com', password: 'A-secure-password-123', nick: 'Buyer' });
  const api = createApi({ store, commerce, accountAuth, adminToken: 'test-only-key-with-at-least-32-characters' });
  const auth = { Authorization: `Bearer ${session.token}`, 'Content-Type': 'application/json' };
  const create = await api(new Request('http://localhost/v1/account/orders', { method: 'POST', headers: auth,
    body: JSON.stringify({ cosmeticId: 'pack', provider: 'manual', idempotencyKey: 'launcher-request-0001' }) }));
  assert.equal(create.status, 201);
  const first = (await create.json()).order;
  assert.equal(first.status, 'pending');
  const listed = await (await api(new Request('http://localhost/v1/account/orders', { headers: auth }))).json();
  assert.equal(listed.items[0].id, first.id);
  const cancelled = await api(new Request(`http://localhost/v1/account/orders/${first.id}/cancel`, { method: 'POST', headers: auth, body: '{}' }));
  assert.equal((await cancelled.json()).order.status, 'cancelled');

  const create2 = await api(new Request('http://localhost/v1/account/orders', { method: 'POST', headers: auth,
    body: JSON.stringify({ cosmeticId: 'pack', provider: 'manual', idempotencyKey: 'launcher-request-0002' }) }));
  const second = (await create2.json()).order;
  const delivered = commerce.settleVerified({ orderId: second.id, provider: 'manual', paymentId: 'receipt-0002', amountMinor: 999, currency: 'USD', status: 'approved' });
  assert.equal(delivered.duplicate, false);
  assert.equal(store.accountWardrobe(session.account.accountId).owned[0].id, 'pack');
  assert.equal(commerce.listOwner({ accountId: session.account.accountId })[0].status, 'paid');
});
