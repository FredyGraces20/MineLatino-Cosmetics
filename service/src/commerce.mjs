import { randomUUID } from 'node:crypto';
import { requireThat, uuid, cosmeticId } from './store.mjs';

export const PAYMENT_PROVIDERS = ['manual', 'paypal', 'binance', 'mercadopago'];

function publicOrder(row) {
  if (!row) return null;
  return {
    id: row.id, cosmeticId: row.cosmetic_id, cosmeticName: row.cosmetic_name ?? null,
    provider: row.provider, amountMinor: row.amount_minor, currency: row.currency,
    status: row.status, paymentReference: row.payment_id ?? null,
    ownerType: row.owner_type,
    createdAt: row.created_at, updatedAt: row.updated_at ?? row.created_at,
    deliveredAt: row.delivered_at ?? null, cancelledAt: row.cancelled_at ?? null,
  };
}

/** Server-only domain. Future adapters must verify signatures and retrieve the
 * approved payment before settleVerified. No production adapters are installed. */
export class Commerce {
  constructor(store) {
    this.store = store;
    store.db.exec(`CREATE TABLE IF NOT EXISTS cosmetic_orders(
      id TEXT PRIMARY KEY, owner TEXT NOT NULL, cosmetic_id TEXT NOT NULL REFERENCES cosmetics(id),
      provider TEXT NOT NULL, amount_minor INTEGER NOT NULL, currency TEXT NOT NULL,
      status TEXT NOT NULL DEFAULT 'pending', idempotency_key TEXT NOT NULL,
      payment_id TEXT, created_at INTEGER NOT NULL, owner_type TEXT NOT NULL DEFAULT 'premium_uuid',
      updated_at INTEGER, delivered_at INTEGER, cancelled_at INTEGER,
      UNIQUE(owner,idempotency_key), UNIQUE(provider,payment_id));`);
    const columns = store.db.prepare("PRAGMA table_info(cosmetic_orders)").all().map(column => column.name);
    if (!columns.includes('owner_type')) store.db.prepare("ALTER TABLE cosmetic_orders ADD COLUMN owner_type TEXT NOT NULL DEFAULT 'premium_uuid'").run();
    if (!columns.includes('updated_at')) store.db.prepare('ALTER TABLE cosmetic_orders ADD COLUMN updated_at INTEGER').run();
    if (!columns.includes('delivered_at')) store.db.prepare('ALTER TABLE cosmetic_orders ADD COLUMN delivered_at INTEGER').run();
    if (!columns.includes('cancelled_at')) store.db.prepare('ALTER TABLE cosmetic_orders ADD COLUMN cancelled_at INTEGER').run();
  }
  providers() {
    return PAYMENT_PROVIDERS.map(id => ({
      id,
      name: id === 'manual' ? 'Pago manual' : id === 'paypal' ? 'PayPal' : id === 'binance' ? 'Binance Pay' : 'Mercado Pago',
      enabled: id === 'manual',
      instructions: id === 'manual' ? 'Crea la orden y entrega la referencia de pago a un administrador de MineLatino.' : null,
    }));
  }
  // identity comes from server authentication, NEVER a request body.
  createOrder(identity, id, provider, idempotencyKey) {
    const accountOwner = typeof identity?.accountId === 'string' && this.store.accountById(identity.accountId, true);
    requireThat(accountOwner || identity?.premiumVerified === true, 'Se requiere una cuenta MineLatino autenticada', 401);
    const ownerType = accountOwner ? 'account' : 'premium_uuid';
    const owner = accountOwner ? accountOwner.account_id : uuid(identity.uuid);
    id = cosmeticId(id);
    requireThat(PAYMENT_PROVIDERS.includes(provider), 'Proveedor inválido');
    requireThat(typeof idempotencyKey === 'string' && /^[a-zA-Z0-9_-]{16,80}$/.test(idempotencyKey), 'Clave de idempotencia inválida');
    return this.store.transaction(() => {
      const old = this.store.db.prepare('SELECT * FROM cosmetic_orders WHERE owner=? AND idempotency_key=?').get(owner, idempotencyKey);
      if (old) {
        requireThat(old.cosmetic_id === id && old.provider === provider, 'Clave utilizada para otra compra', 409);
        return publicOrder(old);
      }
      const item = this.store.cosmetic(id), product = this.store.product(id);
      requireThat(item.status === 'published' && product.amountMinor !== null, 'Producto no disponible', 409);
      const owns = ownerType === 'account'
        ? this.store.db.prepare('SELECT 1 FROM account_entitlements WHERE account_id=? AND cosmetic_id=? AND active=1').get(owner, id)
        : this.store.db.prepare('SELECT 1 FROM entitlements WHERE uuid=? AND cosmetic_id=? AND active=1').get(owner, id);
      requireThat(!owns, 'Ya tienes este cosmético', 409);
      requireThat(!this.store.db.prepare("SELECT 1 FROM cosmetic_orders WHERE owner=? AND cosmetic_id=? AND status='pending'").get(owner, id), 'Ya hay una compra pendiente para este cosmético', 409);
      requireThat(this.providers().some(entry => entry.id === provider && entry.enabled), 'Proveedor todavía no disponible', 503);
      const orderId = randomUUID(), createdAt = Date.now();
      this.store.db.prepare('INSERT INTO cosmetic_orders(id,owner,cosmetic_id,provider,amount_minor,currency,idempotency_key,created_at,updated_at,owner_type) VALUES(?,?,?,?,?,?,?,?,?,?)')
        .run(orderId, owner, id, provider, product.amountMinor, product.currency, idempotencyKey, createdAt, createdAt, ownerType);
      this.store.audit('commerce', 'order.created', { orderId, owner, ownerType, cosmeticId: id, provider });
      return publicOrder(this.store.db.prepare('SELECT o.*,c.name cosmetic_name FROM cosmetic_orders o JOIN cosmetics c ON c.id=o.cosmetic_id WHERE o.id=?').get(orderId));
    });
  }
  listOwner(identity, offset = 0) {
    const accountOwner = typeof identity?.accountId === 'string' && this.store.accountById(identity.accountId, true);
    requireThat(accountOwner || identity?.premiumVerified === true, 'Se requiere una cuenta MineLatino autenticada', 401);
    const ownerType = accountOwner ? 'account' : 'premium_uuid';
    const owner = accountOwner ? accountOwner.account_id : uuid(identity.uuid);
    return this.store.db.prepare(`SELECT o.*,c.name cosmetic_name FROM cosmetic_orders o
      JOIN cosmetics c ON c.id=o.cosmetic_id WHERE o.owner=? AND o.owner_type=?
      ORDER BY o.created_at DESC LIMIT 50 OFFSET ?`).all(owner, ownerType, offset).map(publicOrder);
  }
  listAdmin({ status = '', query = '', offset = 0 } = {}) {
    requireThat(!status || ['pending','paid','cancelled'].includes(status), 'Estado inválido');
    requireThat(Number.isSafeInteger(offset) && offset >= 0, 'Paginación inválida');
    const like = `%${String(query).trim().slice(0, 100)}%`;
    return this.store.db.prepare(`SELECT o.*,c.name cosmetic_name,a.email account_email,a.nick account_nick
      FROM cosmetic_orders o JOIN cosmetics c ON c.id=o.cosmetic_id
      LEFT JOIN player_accounts a ON o.owner_type='account' AND a.account_id=o.owner
      WHERE (?='' OR o.status=?) AND (?='%%' OR o.id LIKE ? OR o.owner LIKE ? OR o.payment_id LIKE ? OR a.email LIKE ? OR a.nick LIKE ?)
      ORDER BY o.created_at DESC LIMIT 50 OFFSET ?`).all(status, status, like, like, like, like, like, like, offset).map(row => ({
        ...publicOrder(row), ownerType: row.owner_type, owner: row.owner,
        accountEmail: row.account_email ?? null, accountNick: row.account_nick ?? null,
      }));
  }
  cancel(identity, orderId) {
    requireThat(typeof orderId === 'string' && /^[0-9a-f-]{36}$/i.test(orderId), 'Orden inválida');
    const accountOwner = typeof identity?.accountId === 'string' && this.store.accountById(identity.accountId, true);
    const ownerType = accountOwner ? 'account' : 'premium_uuid';
    const owner = accountOwner ? accountOwner.account_id : uuid(identity.uuid);
    return this.store.transaction(() => {
      const order = this.store.db.prepare('SELECT * FROM cosmetic_orders WHERE id=? AND owner=? AND owner_type=?').get(orderId, owner, ownerType);
      requireThat(order, 'Orden no encontrada', 404);
      if (order.status === 'cancelled') return publicOrder(order);
      requireThat(order.status === 'pending', 'Solo se puede cancelar una orden pendiente', 409);
      const changedAt = Date.now();
      this.store.db.prepare("UPDATE cosmetic_orders SET status='cancelled',cancelled_at=?,updated_at=? WHERE id=?").run(changedAt, changedAt, orderId);
      this.store.audit('commerce', 'order.cancelled', { orderId, owner, ownerType });
      return publicOrder(this.store.db.prepare('SELECT * FROM cosmetic_orders WHERE id=?').get(orderId));
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
      const deliveredAt = Date.now();
      this.store.db.prepare("UPDATE cosmetic_orders SET status='paid',payment_id=?,delivered_at=?,updated_at=? WHERE id=?").run(paymentId, deliveredAt, deliveredAt, orderId);
      if (order.owner_type === 'account') {
        this.store.db.prepare('INSERT INTO account_entitlements VALUES(?,?,1) ON CONFLICT(account_id,cosmetic_id) DO UPDATE SET active=1').run(order.owner, order.cosmetic_id);
      } else {
        this.store.db.prepare('INSERT INTO entitlements VALUES(?,?,1) ON CONFLICT(uuid,cosmetic_id) DO UPDATE SET active=1').run(order.owner, order.cosmetic_id);
      }
      this.store.audit('commerce', 'order.fulfilled', { orderId, owner: order.owner, ownerType: order.owner_type, cosmeticId: order.cosmetic_id, provider, paymentId });
      return { duplicate: false, orderId };
    });
  }
}
