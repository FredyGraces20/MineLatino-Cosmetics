import { createHash, randomBytes, timingSafeEqual, pbkdf2Sync } from 'node:crypto';
import { ApiError, requireThat } from './store.mjs';

const hash = token => createHash('sha256').update(token).digest();
const SESSION_DURATION = 8 * 60 * 60_000; // 8 hours

function hashPassword(password, salt) {
  return pbkdf2Sync(password, salt, 100_000, 32, 'sha256').toString('hex');
}

export class AdminAuth {
  constructor({ store, bootstrapToken, now = Date.now } = {}) {
    this.store = store;
    this.bootstrapToken = bootstrapToken || null;
    this.now = now;
    this.sessions = new Map(); // tokenHash -> { username, role, expiresAt }
  }

  prune() {
    const now = this.now();
    for (const [key, value] of this.sessions) if (value.expiresAt <= now) this.sessions.delete(key);
  }

  /**
   * Bootstrap: create the first admin using the COSMETICS_ADMIN_TOKEN.
   * Only works when no admin accounts exist yet.
   */
  bootstrap(username, password, adminToken) {
    requireThat(this.bootstrapToken, 'Token administrativo no configurado', 503);
    requireThat(typeof adminToken === 'string' && adminToken.length >= 32, 'Token administrativo requerido', 401);
    // Compare the provided token against the configured COSMETICS_ADMIN_TOKEN (timing-safe)
    const provided = Buffer.from(adminToken);
    const expected = Buffer.from(this.bootstrapToken);
    requireThat(provided.length === expected.length && timingSafeEqual(provided, expected), 'Token administrativo inválido', 401);
    requireThat(this.store.adminCount() === 0, 'Ya existe un administrador; use /v1/admin/auth/login', 409);
    requireThat(typeof username === 'string' && /^[a-zA-Z0-9_]{2,32}$/.test(username), 'Nombre de administrador inválido');
    requireThat(typeof password === 'string' && password.length >= 8 && password.length <= 128, 'Contraseña: 8 a 128 caracteres');
    const salt = randomBytes(16).toString('hex');
    const passwordHash = hashPassword(password, salt);
    const admin = this.store.createAdmin(username, passwordHash, salt, 'superadmin');
    return admin;
  }

  /**
   * Login with username + password. Returns a session token.
   */
  login(username, password) {
    this.prune();
    requireThat(typeof username === 'string' && typeof password === 'string', 'Credenciales requeridas', 401);
    const row = this.store.db.prepare('SELECT * FROM admins WHERE username=?').get(username);
    if (!row) {
      // Constant-time work to prevent timing attacks on username enumeration
      hashPassword(password, '0'.repeat(32));
      throw new ApiError(401, 'Credenciales inválidas');
    }
    const candidateHash = hashPassword(password, row.password_salt);
    const storedBuffer = Buffer.from(row.password_hash, 'hex');
    const candidateBuffer = Buffer.from(candidateHash, 'hex');
    if (storedBuffer.length !== candidateBuffer.length || !timingSafeEqual(storedBuffer, candidateBuffer)) {
      throw new ApiError(401, 'Credenciales inválidas');
    }
    requireThat(this.sessions.size < 100, 'Demasiadas sesiones activas', 503);
    const token = randomBytes(32).toString('base64url');
    const expiresAt = this.now() + SESSION_DURATION;
    this.sessions.set(hash(token).toString('hex'), { username: row.username, role: row.role, expiresAt });
    return { token, expiresAt, username: row.username, role: row.role };
  }

  /**
   * Resolve a Bearer header to an admin identity.
   * Accepts either a session token or the bootstrap admin token.
   * Returns { username, role } or null if not recognized.
   */
  resolve(header, bootstrapToken) {
    if (!header || !header.startsWith('Bearer ')) return null;
    const value = header.slice(7);
    // Check session tokens first
    this.prune();
    const session = this.sessions.get(hash(value).toString('hex'));
    if (session) return { username: session.username, role: session.role };
    // Fall back to bootstrap token
    if (bootstrapToken && timingSafeEqual(hash(value), hash(bootstrapToken))) {
      return { username: 'local-admin', role: 'superadmin' };
    }
    return null;
  }

  logout(header) {
    if (!header?.startsWith('Bearer ')) return;
    this.sessions.delete(hash(header.slice(7)).toString('hex'));
  }
}
