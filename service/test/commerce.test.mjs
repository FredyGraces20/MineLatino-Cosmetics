import test from 'node:test';
import assert from 'node:assert/strict';
import { Store } from '../src/store.mjs';
import { Commerce } from '../src/commerce.mjs';
import { createApi } from '../src/api.mjs';
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
  store.saveCosmetic('draft', { name: 'Draft', slot: 'HAT', status: 'draft', expectedRevision: 0 }, 'test');
  const api = createApi({ store, adminToken: 'test-only-key-with-at-least-32-characters' });
  const response = await api(new Request('http://localhost/v1/storefront/catalog', { headers: { Origin: 'http://localhost:3000' } }));
  assert.equal(response.status, 200); assert.equal(response.headers.get('access-control-allow-origin'), '*');
  const page = await response.json(); assert.equal(page.items.length, 1); assert.equal(page.items[0].amountMinor, 999); assert.equal(page.nextOffset, null);
  const config = await (await api(new Request('http://localhost/v1/storefront/payments'))).json();
  assert.equal(config.checkoutEnabled, false); assert.ok(config.providers.every(p => !p.enabled));
  assert.equal((await api(new Request('http://localhost/v1/storefront/checkout', { method: 'POST' }))).status, 503);
  assert.equal((await api(new Request('http://localhost/v1/admin/cosmetics/catalog', { headers: { Origin: 'http://localhost:3000' } }))).status, 403);
});
test('settlement is atomic, idempotent and grants ownership used by the mod', t => {
  const { store, commerce } = fixture(t);
  assert.throws(() => commerce.createOrder({ ...owner, premiumVerified: false }, 'pack', 'paypal', 'unique-request-0001'));
  const order = commerce.createOrder(owner, 'pack', 'paypal', 'unique-request-0001');
  assert.equal(commerce.createOrder(owner, 'pack', 'paypal', 'unique-request-0001').id, order.id);
  assert.throws(() => commerce.createOrder(owner, 'pack', 'binance', 'unique-request-0002'));
  const payment = { orderId: order.id, provider: 'paypal', paymentId: 'provider-transaction-1', amountMinor: 999, currency: 'USD', status: 'approved' };
  for (const patch of [{ amountMinor: 1 }, { currency: 'ARS' }, { status: 'pending' }, { provider: 'binance' }]) assert.throws(() => commerce.settleVerified({ ...payment, ...patch }));
  assert.equal(store.wardrobe(owner.uuid).owned.length, 0);
  assert.equal(commerce.settleVerified(payment).duplicate, false);
  assert.equal(commerce.settleVerified(payment).duplicate, true);
  assert.equal(store.wardrobe(owner.uuid).owned[0].id, 'pack');
  store.equip(owner.uuid, 'BACKPACK', 'pack');
  assert.equal(store.appearance(owner.uuid)[0].cosmeticId, 'pack');
});
