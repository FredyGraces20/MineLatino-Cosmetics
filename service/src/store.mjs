import { DatabaseSync } from 'node:sqlite';

export class ApiError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}
export const requireThat = (condition, message, status = 400) => { if (!condition) throw new ApiError(status, message); };
export function text(value, max = 80) {
  requireThat(typeof value === 'string' && value.trim().length > 0 && value.length <= max && !/[\x00-\x1f\x7f]/.test(value), 'Texto inválido');
  return value.trim();
}
export function uuid(value) {
  requireThat(typeof value === 'string' && /^(?:[a-f\d]{32}|[a-f\d]{8}-[a-f\d]{4}-[a-f\d]{4}-[a-f\d]{4}-[a-f\d]{12})$/i.test(value), 'UUID inválido');
  return value.replaceAll('-', '').toLowerCase();
}
export function cosmeticId(value) {
  requireThat(typeof value === 'string' && /^[a-z0-9][a-z0-9_-]{0,63}$/.test(value), 'ID de cosmético inválido');
  return value;
}
export const SLOTS = ['CAPE', 'HAT', 'WINGS', 'BACKPACK', 'PET'];

/** Check if hostname matches an allowed domain or any of its subdomains. */
function isAllowedHost(hostname, allowedDomains) {
  return allowedDomains.some(domain => hostname === domain || hostname.endsWith('.' + domain));
}
export const DEFAULT_MENU = { schemaVersion: 1, enabled: true, buttons: [{ label: 'Cosméticos MineLatino', action: 'WARDROBE' }], labels: {}, vanillaUrls: {} };
export function validateMenu(config) {
  requireThat(config && config.schemaVersion === 1 && typeof config.enabled === 'boolean' && Array.isArray(config.buttons) && config.buttons.length <= 3, 'Menú inválido');
  const allowed = ['menu.returnToGame', 'menu.options', 'menu.disconnect', 'menu.returnToMenu', 'gui.advancements', 'gui.stats', 'menu.sendFeedback', 'menu.reportBugs', 'menu.shareToLan'];
  requireThat(config.labels && typeof config.labels === 'object' && !Array.isArray(config.labels) && Object.keys(config.labels).length <= 9, 'Etiquetas inválidas');
  const labels = Object.fromEntries(Object.entries(config.labels).map(([key, value]) => {
    requireThat(allowed.includes(key), 'Botón original desconocido');
    return [key, text(value, 40)];
  }));
  // vanillaUrls: maps vanilla button keys to custom URLs (Discord, store, etc.)
  const rawUrls = config.vanillaUrls || {};
  requireThat(typeof rawUrls === 'object' && !Array.isArray(rawUrls), 'URLs vanilla inválidas');
  const vanillaUrls = Object.fromEntries(Object.entries(rawUrls).map(([key, url]) => {
    requireThat(allowed.includes(key), 'Botón original desconocido para URL');
    let parsed;
    try { parsed = new URL(url); } catch { throw new ApiError(400, 'URL inválida'); }
    requireThat(parsed.protocol === 'https:' && isAllowedHost(parsed.hostname, ['minelatino.com', 'minelatino.shop', 'discord.com']) && !parsed.username && !parsed.password && !parsed.port, 'URL no permitida');
    return [key, parsed.href];
  }));
  const buttons = config.buttons.map(button => {
    requireThat(button && ['WARDROBE', 'WEBSITE'].includes(button.action), 'Acción no permitida');
    const result = { label: text(button.label, 40), action: button.action };
    if (button.action === 'WEBSITE') {
      let url;
      try { url = new URL(button.url); } catch { throw new ApiError(400, 'URL inválida'); }
      requireThat(url.protocol === 'https:' && isAllowedHost(url.hostname, ['minelatino.com', 'minelatino.shop']) && !url.username && !url.password && !url.port, 'URL no permitida');
      result.url = url.href;
    }
    return result;
  });
  return { schemaVersion: 1, enabled: config.enabled, buttons, labels, vanillaUrls };
}

export class Store {
  constructor(path = ':memory:') {
    this.db = new DatabaseSync(path);
    this.db.exec(`PRAGMA foreign_keys=ON; PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000;
      CREATE TABLE IF NOT EXISTS cosmetics(id TEXT PRIMARY KEY, name TEXT NOT NULL, slot TEXT NOT NULL CHECK(slot IN ('CAPE','HAT','WINGS','BACKPACK','PET')), status TEXT NOT NULL CHECK(status IN ('draft','published','retired')), revision INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS players(uuid TEXT PRIMARY KEY, name TEXT NOT NULL, verified_at INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS entitlements(uuid TEXT NOT NULL, cosmetic_id TEXT NOT NULL REFERENCES cosmetics(id), active INTEGER NOT NULL CHECK(active IN (0,1)), PRIMARY KEY(uuid,cosmetic_id));
      CREATE TABLE IF NOT EXISTS operations(reference TEXT PRIMARY KEY, payload TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS equipment(uuid TEXT NOT NULL, slot TEXT NOT NULL, cosmetic_id TEXT NOT NULL, PRIMARY KEY(uuid,slot), FOREIGN KEY(uuid,cosmetic_id) REFERENCES entitlements(uuid,cosmetic_id));
      CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY AUTOINCREMENT, actor TEXT NOT NULL, action TEXT NOT NULL, payload TEXT NOT NULL, at INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS menus(revision INTEGER PRIMARY KEY AUTOINCREMENT, config TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS cosmetic_products(cosmetic_id TEXT PRIMARY KEY REFERENCES cosmetics(id), description TEXT NOT NULL, amount_minor INTEGER, currency TEXT NOT NULL DEFAULT 'USD');
      CREATE INDEX IF NOT EXISTS entitlement_owners ON entitlements(cosmetic_id,active,uuid);
      CREATE TABLE IF NOT EXISTS admins(username TEXT PRIMARY KEY, password_hash TEXT NOT NULL, password_salt TEXT NOT NULL, role TEXT NOT NULL DEFAULT 'admin', created_at INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS resources(cosmetic_id TEXT PRIMARY KEY REFERENCES cosmetics(id), file_path TEXT NOT NULL, sha256 TEXT NOT NULL, file_size INTEGER NOT NULL, content_type TEXT NOT NULL, uploaded_at INTEGER NOT NULL, model_path TEXT, model_sha256 TEXT, model_size INTEGER);
      CREATE TABLE IF NOT EXISTS resource_files(cosmetic_id TEXT NOT NULL REFERENCES cosmetics(id), name TEXT NOT NULL, file_path TEXT NOT NULL, sha256 TEXT NOT NULL, file_size INTEGER NOT NULL, mcmeta_path TEXT, mcmeta_size INTEGER, uploaded_at INTEGER NOT NULL, PRIMARY KEY(cosmetic_id, name));
      CREATE TABLE IF NOT EXISTS cosmetic_transforms(cosmetic_id TEXT NOT NULL REFERENCES cosmetics(id), slot TEXT NOT NULL CHECK(slot IN ('head','backpack')), translation_x REAL DEFAULT 0, translation_y REAL DEFAULT 0, translation_z REAL DEFAULT 0, rotation_x REAL DEFAULT 0, rotation_y REAL DEFAULT 0, rotation_z REAL DEFAULT 0, scale_x REAL DEFAULT 1, scale_y REAL DEFAULT 1, scale_z REAL DEFAULT 1, updated_at INTEGER, PRIMARY KEY(cosmetic_id, slot));
      CREATE TABLE IF NOT EXISTS pet_animations(cosmetic_id TEXT PRIMARY KEY REFERENCES cosmetics(id), animation_name TEXT NOT NULL, file_path TEXT, sha256 TEXT, file_size INTEGER, updated_at INTEGER NOT NULL);
      `);
    // Migration: ensure model columns exist (for databases that may have incomplete migration)
    const columns = this.db.prepare("PRAGMA table_info(resources)").all().map(c => c.name);
    if (!columns.includes('model_path')) {
      this.db.prepare('ALTER TABLE resources ADD COLUMN model_path TEXT').run();
    }
    if (!columns.includes('model_sha256')) {
      this.db.prepare('ALTER TABLE resources ADD COLUMN model_sha256 TEXT').run();
    }
    if (!columns.includes('model_size')) {
      this.db.prepare('ALTER TABLE resources ADD COLUMN model_size INTEGER').run();
    }
    this.migrateCosmeticSlots();
  }
  migrateCosmeticSlots() {
    const schema = this.db.prepare("SELECT sql FROM sqlite_schema WHERE type='table' AND name='cosmetics'").get().sql;
    if (schema.includes("'BACKPACK'") && schema.includes("'PET'")) {
      if (this.db.prepare('PRAGMA user_version').get().user_version < 5) this.db.exec('PRAGMA user_version=5');
      return;
    }
    // SQLite cannot ALTER a CHECK constraint. Rebuild only this table in a transaction,
    // keeping IDs/revisions and all referencing ownership, equipment and resource rows.
    this.db.exec('PRAGMA foreign_keys=OFF');
    try {
      this.transaction(() => {
        this.db.exec(`CREATE TABLE cosmetics_slots_v5(id TEXT PRIMARY KEY, name TEXT NOT NULL,
          slot TEXT NOT NULL CHECK(slot IN ('CAPE','HAT','WINGS','BACKPACK','PET')),
          status TEXT NOT NULL CHECK(status IN ('draft','published','retired')), revision INTEGER NOT NULL);
          INSERT INTO cosmetics_slots_v5 SELECT id,name,slot,status,revision FROM cosmetics;
          DROP TABLE cosmetics;
          ALTER TABLE cosmetics_slots_v5 RENAME TO cosmetics;`);
        requireThat(this.db.prepare('PRAGMA foreign_key_check').all().length === 0, 'Migración de categorías: referencias inválidas', 500);
        this.db.exec('PRAGMA user_version=5');
      });
    } finally { this.db.exec('PRAGMA foreign_keys=ON'); }
  }
  close() { this.db.close(); }
  transaction(work) {
    this.db.exec('BEGIN IMMEDIATE');
    try { const value = work(); this.db.exec('COMMIT'); return value; }
    catch (error) { this.db.exec('ROLLBACK'); throw error; }
  }
  audit(actor, action, payload) {
    this.db.prepare('INSERT INTO audit(actor,action,payload,at) VALUES(?,?,?,?)').run(actor, action, JSON.stringify(payload), Date.now());
  }
  catalog(admin, offset = 0) {
    return this.db.prepare(`SELECT * FROM cosmetics ${admin ? '' : "WHERE status='published'"} ORDER BY id LIMIT 50 OFFSET ?`).all(offset);
  }
  cosmetic(id) {
    const item = this.db.prepare('SELECT * FROM cosmetics WHERE id=?').get(cosmeticId(id));
    requireThat(item, 'Cosmético no encontrado', 404); return item;
  }
  saveCosmetic(id, input, actor) {
    id = cosmeticId(id);
    const name = text(input.name);
    requireThat(SLOTS.includes(input.slot) && ['draft', 'published', 'retired'].includes(input.status), 'Categoría o estado inválido');
    requireThat(Number.isSafeInteger(input.expectedRevision) && input.expectedRevision >= 0, 'Revisión requerida');
    return this.transaction(() => {
      const old = this.db.prepare('SELECT * FROM cosmetics WHERE id=?').get(id);
      requireThat((old?.revision ?? 0) === input.expectedRevision, 'Otro administrador modificó este cosmético', 409);
      requireThat(!old || old.slot === input.slot, 'La categoría de un ID existente no se puede cambiar', 409);
      const revision = input.expectedRevision + 1;
      this.db.prepare('INSERT INTO cosmetics VALUES(?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,status=excluded.status,revision=excluded.revision').run(id, name, input.slot, input.status, revision);
      if (input.product !== undefined) {
        const p = input.product;
        requireThat(p && typeof p.description === 'string' && p.description.length <= 2000, 'Descripción inválida (máximo 2000 caracteres)');
        requireThat(p.amountMinor === null || (Number.isSafeInteger(p.amountMinor) && p.amountMinor > 0 && p.amountMinor <= 100000000), 'Precio inválido; usa unidades menores enteras o null');
        requireThat(['USD', 'EUR', 'UYU', 'ARS', 'BRL', 'MXN'].includes(p.currency), 'Moneda no admitida');
        this.db.prepare('INSERT INTO cosmetic_products VALUES(?,?,?,?) ON CONFLICT(cosmetic_id) DO UPDATE SET description=excluded.description,amount_minor=excluded.amount_minor,currency=excluded.currency')
          .run(id, p.description.trim(), p.amountMinor, p.currency);
      }
      if (input.status !== 'published') this.db.prepare('DELETE FROM equipment WHERE cosmetic_id=?').run(id);
      this.audit(actor, 'catalog.save', { id, name, slot: input.slot, status: input.status, revision });
      return this.cosmetic(id);
    });
  }
  entitlement(input, active, actor) {
    const owner = uuid(input.uuid), id = cosmeticId(input.cosmeticId);
    const reason = (input.reason && input.reason.trim()) ? text(input.reason, 240) : (active ? 'grant' : 'revoke');
    const reference = (input.reference && input.reference.trim()) ? text(input.reference, 100) : `${active ? 'grant' : 'revoke'}-${Date.now()}`;
    const payload = JSON.stringify({ owner, id, active, reason });
    return this.transaction(() => {
      this.cosmetic(id);
      const old = this.db.prepare('SELECT payload FROM operations WHERE reference=?').get(reference);
      if (old) { requireThat(old.payload === payload, 'Referencia ya usada para otra operación', 409); return { duplicate: true }; }
      this.db.prepare('INSERT INTO operations VALUES(?,?)').run(reference, payload);
      this.db.prepare('INSERT INTO entitlements VALUES(?,?,?) ON CONFLICT(uuid,cosmetic_id) DO UPDATE SET active=excluded.active').run(owner, id, active ? 1 : 0);
      if (!active) this.db.prepare('DELETE FROM equipment WHERE uuid=? AND cosmetic_id=?').run(owner, id);
      this.audit(actor, active ? 'entitlement.grant' : 'entitlement.revoke', { uuid: owner, cosmeticId: id, reason, reference });
      return { duplicate: false };
    });
  }
  owners(id, offset = 0) {
    this.cosmetic(id);
    return this.db.prepare('SELECT e.uuid,p.name,p.verified_at FROM entitlements e LEFT JOIN players p ON p.uuid=e.uuid WHERE cosmetic_id=? AND active=1 ORDER BY e.uuid LIMIT 50 OFFSET ?').all(id, offset);
  }
  verifiedPlayer(owner, name) {
    this.db.prepare('INSERT INTO players VALUES(?,?,?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name,verified_at=excluded.verified_at').run(uuid(owner), text(name, 16), Date.now());
  }
  wardrobe(owner) {
    owner = uuid(owner);
    return {
      uuid: owner,
      owned: this.db.prepare('SELECT c.* FROM entitlements e JOIN cosmetics c ON c.id=e.cosmetic_id WHERE e.uuid=? AND e.active=1 ORDER BY c.id').all(owner),
      equipped: this.appearance(owner),
    };
  }
  appearance(owner) {
    return this.db.prepare("SELECT q.slot,q.cosmetic_id AS cosmeticId FROM equipment q JOIN entitlements e ON e.uuid=q.uuid AND e.cosmetic_id=q.cosmetic_id JOIN cosmetics c ON c.id=q.cosmetic_id WHERE q.uuid=? AND e.active=1 AND c.status='published' ORDER BY q.slot").all(uuid(owner));
  }
  equip(owner, slot, id) {
    owner = uuid(owner);
    requireThat(SLOTS.includes(slot), 'Categoría inválida');
    return this.transaction(() => {
      if (id === null) this.db.prepare('DELETE FROM equipment WHERE uuid=? AND slot=?').run(owner, slot);
      else {
        const cosmetic = this.cosmetic(id);
        requireThat(cosmetic.slot === slot && cosmetic.status === 'published', 'Cosmético no equipable', 409);
        requireThat(this.db.prepare('SELECT 1 FROM entitlements WHERE uuid=? AND cosmetic_id=? AND active=1').get(owner, id), 'No posees este cosmético', 403);
        this.db.prepare('INSERT INTO equipment VALUES(?,?,?) ON CONFLICT(uuid,slot) DO UPDATE SET cosmetic_id=excluded.cosmetic_id').run(owner, slot, id);
      }
      return this.appearance(owner);
    });
  }
  menu() {
    const row = this.db.prepare('SELECT * FROM menus ORDER BY revision DESC LIMIT 1').get();
    return { revision: row?.revision ?? 0, config: row ? JSON.parse(row.config) : structuredClone(DEFAULT_MENU) };
  }
  saveMenu(input, actor) {
    const config = validateMenu(input.config);
    return this.transaction(() => {
      requireThat(input.expectedRevision === this.menu().revision, 'Revisión de menú desactualizada', 409);
      this.db.prepare('INSERT INTO menus(config) VALUES(?)').run(JSON.stringify(config));
      const result = this.menu();
      this.audit(actor, 'menu.publish', result);
      return result;
    });
  }
  menuHistory(offset = 0) {
    return this.db.prepare('SELECT revision,config FROM menus ORDER BY revision DESC LIMIT 50 OFFSET ?').all(offset)
      .map(row => ({ revision: row.revision, config: JSON.parse(row.config) }));
  }
  restoreMenu(input, actor) {
    requireThat(Number.isSafeInteger(input.revision) && input.revision > 0, 'Revisión inválida');
    const old = this.db.prepare('SELECT config FROM menus WHERE revision=?').get(input.revision);
    requireThat(old, 'Revisión no encontrada', 404);
    // Restoration publishes a new revision: never deletes intervening history.
    return this.saveMenu({ expectedRevision: input.expectedRevision, config: JSON.parse(old.config) }, actor);
  }
  deleteMenuEntry(input, actor) {
    requireThat(Number.isSafeInteger(input.revision) && input.revision > 0, 'Revisión inválida');
    const current = this.menu().revision;
    requireThat(input.revision !== current, 'No se puede eliminar la configuración activa', 409);
    const row = this.db.prepare('SELECT revision FROM menus WHERE revision=?').get(input.revision);
    requireThat(row, 'Revisión no encontrada', 404);
    this.db.prepare('DELETE FROM menus WHERE revision=?').run(input.revision);
    this.audit(actor, 'menu.delete', { revision: input.revision });
    return { deleted: input.revision };
  }
  auditPage(offset = 0) { return this.db.prepare('SELECT * FROM audit ORDER BY id DESC LIMIT 50 OFFSET ?').all(offset); }

  // ── Admin accounts ──────────────────────────────────────────────────────

  adminCount() { return this.db.prepare('SELECT COUNT(*) AS count FROM admins').get().count; }

  createAdmin(username, passwordHash, passwordSalt, role = 'admin') {
    requireThat(/^[a-zA-Z0-9_]{2,32}$/.test(username), 'Nombre de administrador inválido');
    requireThat(typeof passwordHash === 'string' && passwordHash.length === 64, 'Hash inválido');
    requireThat(typeof passwordSalt === 'string' && passwordSalt.length === 32, 'Salt inválido');
    requireThat(['admin', 'superadmin'].includes(role), 'Rol inválido');
    requireThat(this.adminCount() < 50, 'Demasiados administradores', 429);
    this.db.prepare('INSERT INTO admins VALUES(?,?,?,?,?)').run(username, passwordHash, passwordSalt, role, Date.now());
    this.audit(username, 'admin.create', { username, role });
    return { username, role, created_at: Date.now() };
  }

  getAdmin(username) { return this.db.prepare('SELECT username,role,created_at FROM admins WHERE username=?').get(username); }

  listAdmins() { return this.db.prepare('SELECT username,role,created_at FROM admins ORDER BY username').all(); }

  deleteAdmin(username, actor) {
    requireThat(this.adminCount() > 1, 'No se puede eliminar el último administrador');
    const admin = this.getAdmin(username);
    requireThat(admin, 'Administrador no encontrado', 404);
    this.db.prepare('DELETE FROM admins WHERE username=?').run(username);
    this.audit(actor, 'admin.delete', { username });
    return { deleted: true };
  }

  // ── Player search ───────────────────────────────────────────────────────

  searchPlayers(query, offset = 0) {
    const q = query.trim().toLowerCase();
    requireThat(q.length >= 2, 'Búsqueda demasiado corta');
    if (/^[a-f0-9]{6,32}$/.test(q)) {
      return this.db.prepare('SELECT uuid,name,verified_at FROM players WHERE uuid LIKE ? ORDER BY name LIMIT 50 OFFSET ?').all(`%${q}%`, offset);
    }
    return this.db.prepare('SELECT uuid,name,verified_at FROM players WHERE name LIKE ? ORDER BY name LIMIT 50 OFFSET ?').all(`%${q}%`, offset);
  }

  // ── Cosmetic resources ─────────────────────────────────────────────

  saveResource(id, filePath, sha256, fileSize, contentType) {
    id = cosmeticId(id);
    this.cosmetic(id); // verify exists
    this.db.prepare('INSERT INTO resources(cosmetic_id,file_path,sha256,file_size,content_type,uploaded_at) VALUES(?,?,?,?,?,?) ON CONFLICT(cosmetic_id) DO UPDATE SET file_path=excluded.file_path,sha256=excluded.sha256,file_size=excluded.file_size,content_type=excluded.content_type,uploaded_at=excluded.uploaded_at')
      .run(id, filePath, sha256, fileSize, contentType, Date.now());
    return this.getResource(id);
  }

  saveResourceModel(id, modelPath, modelSha256, modelSize) {
    id = cosmeticId(id);
    this.cosmetic(id); // verify exists
    this.db.prepare('UPDATE resources SET model_path=?, model_sha256=?, model_size=? WHERE cosmetic_id=?')
      .run(modelPath, modelSha256, modelSize, id);
    return this.getResource(id);
  }

  savePetAnimation(id, animationName, filePath, sha256, fileSize, actor) {
    id = cosmeticId(id);
    const item = this.cosmetic(id);
    requireThat(item.slot === 'PET', 'Las animaciones solo se pueden asignar a mascotas');
    requireThat(typeof animationName === 'string' && /^[a-zA-Z0-9_.:-]{1,128}$/.test(animationName), 'Nombre de animación inválido');
    this.db.prepare(`INSERT INTO pet_animations(cosmetic_id,animation_name,file_path,sha256,file_size,updated_at)
      VALUES(?,?,?,?,?,?) ON CONFLICT(cosmetic_id) DO UPDATE SET animation_name=excluded.animation_name,
      file_path=COALESCE(excluded.file_path,pet_animations.file_path),sha256=COALESCE(excluded.sha256,pet_animations.sha256),
      file_size=COALESCE(excluded.file_size,pet_animations.file_size),updated_at=excluded.updated_at`)
      .run(id, animationName, filePath, sha256, fileSize, Date.now());
    this.audit(actor, 'pet.animation.save', { cosmeticId: id, animationName, fileSize });
    return this.getPetAnimation(id);
  }

  getPetAnimation(id) {
    return this.db.prepare('SELECT * FROM pet_animations WHERE cosmetic_id=?').get(cosmeticId(id));
  }

  deletePetAnimation(id, actor) {
    const old = this.getPetAnimation(id);
    if (old) this.db.prepare('DELETE FROM pet_animations WHERE cosmetic_id=?').run(cosmeticId(id));
    if (old) this.audit(actor, 'pet.animation.delete', { cosmeticId: id });
    return old;
  }

  getResource(id) {
    return this.db.prepare('SELECT * FROM resources WHERE cosmetic_id=?').get(cosmeticId(id));
  }

  product(id) {
    const row = this.db.prepare('SELECT * FROM cosmetic_products WHERE cosmetic_id=?').get(cosmeticId(id));
    return { description: row?.description ?? '', amountMinor: row?.amount_minor ?? null, currency: row?.currency ?? 'USD' };
  }

  deleteResource(id) {
    const res = this.getResource(id);
    if (res) this.db.prepare('DELETE FROM resources WHERE cosmetic_id=?').run(cosmeticId(id));
    return res;
  }

  // ── Multi-file resources (textures + mcmeta) ─────────────────────

  saveResourceFile(id, name, filePath, sha256, fileSize) {
    id = cosmeticId(id);
    this.cosmetic(id);
    requireThat(/^[a-z0-9_]{1,32}$/.test(name), 'Nombre de archivo inválido');
    this.db.prepare('INSERT INTO resource_files(cosmetic_id,name,file_path,sha256,file_size,uploaded_at) VALUES(?,?,?,?,?,?) ON CONFLICT(cosmetic_id,name) DO UPDATE SET file_path=excluded.file_path,sha256=excluded.sha256,file_size=excluded.file_size,uploaded_at=excluded.uploaded_at')
      .run(id, name, filePath, sha256, fileSize, Date.now());
    return this.getResourceFile(id, name);
  }

  saveResourceFileMcmeta(id, name, mcmetaPath, mcmetaSize) {
    id = cosmeticId(id);
    requireThat(/^[a-z0-9_]{1,32}$/.test(name), 'Nombre de archivo inválido');
    this.db.prepare('UPDATE resource_files SET mcmeta_path=?, mcmeta_size=?, uploaded_at=? WHERE cosmetic_id=? AND name=?')
      .run(mcmetaPath, mcmetaSize, Date.now(), id, name);
    return this.getResourceFile(id, name);
  }

  getResourceFile(id, name) {
    return this.db.prepare('SELECT * FROM resource_files WHERE cosmetic_id=? AND name=?').get(cosmeticId(id), name);
  }

  getResourceFiles(id) {
    return this.db.prepare('SELECT * FROM resource_files WHERE cosmetic_id=? ORDER BY name').all(cosmeticId(id));
  }

  resourceFileCount(id) {
    return this.db.prepare('SELECT COUNT(*) AS count FROM resource_files WHERE cosmetic_id=?').get(cosmeticId(id)).count;
  }

  deleteResourceFile(id, name) {
    const file = this.getResourceFile(id, name);
    if (file) this.db.prepare('DELETE FROM resource_files WHERE cosmetic_id=? AND name=?').run(cosmeticId(id), name);
    return file;
  }

  deleteResourceFileMcmeta(id, name) {
    this.db.prepare('UPDATE resource_files SET mcmeta_path=NULL, mcmeta_size=NULL WHERE cosmetic_id=? AND name=?')
      .run(cosmeticId(id), name);
    return this.getResourceFile(id, name);
  }

  // ── Cosmetic transforms (position/rotation/scale per slot) ─────────

  getTransforms(id) {
    id = cosmeticId(id);
    const rows = this.db.prepare('SELECT * FROM cosmetic_transforms WHERE cosmetic_id=?').all(id);
    const result = {};
    for (const row of rows) {
      result[row.slot] = {
        translation: [row.translation_x, row.translation_y, row.translation_z],
        rotation: [row.rotation_x, row.rotation_y, row.rotation_z],
        scale: [row.scale_x, row.scale_y, row.scale_z],
        updatedAt: row.updated_at
      };
    }
    return result;
  }

  saveTransform(id, slot, transform, actor) {
    id = cosmeticId(id);
    this.cosmetic(id); // verify exists
    requireThat(['head', 'backpack'].includes(slot), 'Slot inválido (debe ser head o backpack)');
    requireThat(transform && typeof transform === 'object', 'Transform inválido');
    const t = Array.isArray(transform.translation) ? transform.translation : [0, 0, 0];
    const r = Array.isArray(transform.rotation) ? transform.rotation : [0, 0, 0];
    const s = Array.isArray(transform.scale) ? transform.scale : [1, 1, 1];
    requireThat(t.length === 3 && r.length === 3 && s.length === 3, 'Transform debe tener 3 valores por eje');
    for (const v of [...t, ...r, ...s]) requireThat(Number.isFinite(v), 'Valores de transform deben ser números finitos');
    this.db.prepare(`INSERT INTO cosmetic_transforms(cosmetic_id, slot, translation_x, translation_y, translation_z, rotation_x, rotation_y, rotation_z, scale_x, scale_y, scale_z, updated_at)
      VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(cosmetic_id, slot) DO UPDATE SET
      translation_x=excluded.translation_x, translation_y=excluded.translation_y, translation_z=excluded.translation_z,
      rotation_x=excluded.rotation_x, rotation_y=excluded.rotation_y, rotation_z=excluded.rotation_z,
      scale_x=excluded.scale_x, scale_y=excluded.scale_y, scale_z=excluded.scale_z, updated_at=excluded.updated_at`)
      .run(id, slot, t[0], t[1], t[2], r[0], r[1], r[2], s[0], s[1], s[2], Date.now());
    this.audit(actor, 'transform.save', { id, slot, transform: { translation: t, rotation: r, scale: s } });
    return this.getTransforms(id);
  }

  getAllTransforms() {
    const rows = this.db.prepare('SELECT cosmetic_id, slot, translation_x, translation_y, translation_z, rotation_x, rotation_y, rotation_z, scale_x, scale_y, scale_z FROM cosmetic_transforms').all();
    const result = {};
    for (const row of rows) {
      if (!result[row.cosmetic_id]) result[row.cosmetic_id] = {};
      result[row.cosmetic_id][row.slot] = {
        translation: [row.translation_x, row.translation_y, row.translation_z],
        rotation: [row.rotation_x, row.rotation_y, row.rotation_z],
        scale: [row.scale_x, row.scale_y, row.scale_z]
      };
    }
    return result;
  }
}
