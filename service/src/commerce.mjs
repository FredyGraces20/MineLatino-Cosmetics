import { randomUUID } from 'node:crypto';
import { requireThat, uuid, cosmeticId } from './store.mjs';

export const PAYMENT_PROVIDERS = ['paypal', 'binance', 'mercadopago'];

/** Server-only domain. Future adapters must verify signatures and retrieve the
 * approved payment before settleVerified. No production adapters are installed. */
export class Commerce {
  constructor(store) {
    this.store = store;
    store.db.exec(`CREATE TABLE IF NOT EXISTS cosmetic_orders(
      id TEXT PRIMARY KEY, owner TEXT NOT NULL, cosmetic_id TEXT NOT NULL REFERENCES cosmetics(id),
      provider TEXT NOT NULL, amount_minor INTEGER NOT NULL, currency TEXT NOT NULL,
      status TEXT NOT NULL DEFAULT 'pending', idempotency_key TEXT NOT NULL,
      payment_id TEXT, created_at INTEGER NOT NULL, UNIQUE(owner,idempotency_key), UNIQUE(provider,payment_id));`);
  }
  // identity comes from server premium authentication, NEVER a request body.
  createOrder(identity, id, provider, idempotencyKey) {
    requireThat(identity?.premiumVerified === true, 'Se requiere una cuenta premium verificada', 401);
    const owner = uuid(identity.uuid);
    id = cosmeticId(id);
    requireThat(PAYMENT_PROVIDERS.includes(provider), 'Proveedor inválido');
    requireThat(typeof idempotencyKey === 'string' && /^[a-zA-Z0-9_-]{16,80}$/.test(idempotencyKey), 'Clave de idempotencia inválida');
    return this.store.transaction(() => {
      const old = this.store.db.prepare('SELECT * FROM cosmetic_orders WHERE owner=? AND idempotency_key=?').get(owner, idempotencyKey);
      if (old) {
        requireThat(old.cosmetic_id === id && old.provider === provider, 'Clave utilizada para otra compra', 409);
        return old;
      }
      const item = this.store.cosmetic(id), product = this.store.product(id);
      requireThat(item.status === 'published' && product.amountMinor !== null, 'Producto no disponible', 409);
      requireThat(!this.store.db.prepare('SELECT 1 FROM entitlements WHERE uuid=? AND cosmetic_id=? AND active=1').get(owner, id), 'Ya tienes este cosmético', 409);
      requireThat(!this.store.db.prepare("SELECT 1 FROM cosmetic_orders WHERE owner=? AND cosmetic_id=? AND status='pending'").get(owner, id), 'Ya hay una compra pendiente para este cosmético', 409);
      const orderId = randomUUID();
      this.store.db.prepare('INSERT INTO cosmetic_orders(id,owner,cosmetic_id,provider,amount_minor,currency,idempotency_key,created_at) VALUES(?,?,?,?,?,?,?,?)')
        .run(orderId, owner, id, provider, product.amountMinor, product.currency, idempotencyKey, Date.now());
      this.store.audit('commerce', 'order.created', { orderId, owner, cosmeticId: id, provider });
      return this.store.db.prepare('SELECT * FROM cosmetic_orders WHERE id=?').get(orderId);
    });
  }
  // Internal boundary: NEVER expose this method directly as an HTTP handler.
  settleVerified({ orderId, provider, paymentId, amountMinor, currency, status }) {
    requireThat(PAYMENT_PROVIDERS.includes(provider) && typeof paymentId === 'string' && paymentId.length > 0 && paymentId.length <= 160, 'Pago inválido');
    return this.store.transaction(() => {
      const order = this.store.db.prepare('SELECT * FROM cosmetic_orders WHERE id=?').get(orderId);
      requireThat(order, 'Compra no encontrada', 404);
      requireThat(status === 'approved' && order.provider === provider && order.amount_minor === amountMinor && order.currency === currency, 'El pago no coincide con la compra', 409);
      if (order.status === 'paid') {
        requireThat(order.payment_id === paymentId, 'La compra ya tiene otro pago', 409);
        return { duplicate: true, orderId };
      }
      requireThat(order.status === 'pending', 'Compra no pendiente', 409);
      requireThat(!this.store.db.prepare('SELECT 1 FROM cosmetic_orders WHERE provider=? AND payment_id=?').get(provider, paymentId), 'Pago utilizado en otra compra', 409);
      this.store.db.prepare("UPDATE cosmetic_orders SET status='paid',payment_id=? WHERE id=?").run(paymentId, orderId);
      this.store.db.prepare('INSERT INTO entitlements VALUES(?,?,1) ON CONFLICT(uuid,cosmetic_id) DO UPDATE SET active=1').run(order.owner, order.cosmetic_id);
      this.store.audit('commerce', 'order.fulfilled', { orderId, uuid: order.owner, cosmeticId: order.cosmetic_id, provider, paymentId });
      return { duplicate: false, orderId };
    });
  }
}
