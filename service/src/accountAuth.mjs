import { createHash, randomBytes, randomUUID, scryptSync, timingSafeEqual } from 'node:crypto';
import { ApiError, requireThat } from './store.mjs';

const SESSION_TTL = 30 * 24 * 60 * 60 * 1000;
const GAME_TTL = 24 * 60 * 60 * 1000;
const tokenHash = token => createHash('sha256').update(token).digest('hex');

function email(value) {
  requireThat(typeof value === 'string' && value.length <= 254, 'Correo inválido');
  const normalized = value.trim().toLowerCase();
  requireThat(/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(normalized), 'Correo inválido');
  return normalized;
}

function nick(value) {
  requireThat(typeof value === 'string' && /^[A-Za-z0-9_]{3,16}$/.test(value), 'Nick inválido (3 a 16 letras, números o _)');
  return value;
}

function password(value) {
  requireThat(typeof value === 'string' && value.length >= 10 && value.length <= 128, 'La contraseña debe tener entre 10 y 128 caracteres');
  return value;
}

function derivePassword(value, salt) {
  return scryptSync(value, Buffer.from(salt, 'hex'), 32, { N: 16384, r: 8, p: 1 }).toString('hex');
}

export class AccountAuth {
  constructor({ store, now = Date.now } = {}) { this.store = store; this.now = now; }

  register(input) {
    const normalizedEmail = email(input.email), playerNick = nick(input.nick), secret = password(input.password);
    requireThat(!this.store.accountByEmail(normalizedEmail, true), 'Ya existe una cuenta con ese correo', 409);
    const salt = randomBytes(16).toString('hex');
    const account = this.store.createPlayerAccount({
      accountId: randomUUID().replaceAll('-', ''), email: normalizedEmail, nick: playerNick,
      passwordHash: derivePassword(secret, salt), passwordSalt: salt,
    });
    return { account, ...this.issue(account.accountId, 'account', SESSION_TTL) };
  }

  login(input) {
    const normalizedEmail = email(input.email), secret = password(input.password);
    const row = this.store.accountByEmail(normalizedEmail, true);
    if (!row) { derivePassword(secret, '0'.repeat(32)); throw new ApiError(401, 'Correo o contraseña incorrectos'); }
    requireThat(row.status === 'active', 'La cuenta no está activa', 403);
    const candidate = Buffer.from(derivePassword(secret, row.password_salt), 'hex');
    const expected = Buffer.from(row.password_hash, 'hex');
    requireThat(candidate.length === expected.length && timingSafeEqual(candidate, expected), 'Correo o contraseña incorrectos', 401);
    return { account: this.store.publicPlayerAccount(row), ...this.issue(row.account_id, 'account', SESSION_TTL) };
  }

  issue(accountId, scope, ttl = GAME_TTL) {
    const token = randomBytes(32).toString('base64url'), expiresAt = this.now() + ttl;
    this.store.createAccountSession(tokenHash(token), accountId, scope, expiresAt, this.now());
    return { token, tokenType: 'Bearer', scope, expiresAt };
  }

  authenticate(header, allowedScopes = ['account', 'game']) {
    requireThat(typeof header === 'string' && header.startsWith('Bearer '), 'Sesión requerida', 401);
    const session = this.store.accountSession(tokenHash(header.slice(7)));
    requireThat(session && session.expires_at > this.now(), 'Sesión inválida o caducada', 401);
    requireThat(allowedScopes.includes(session.scope), 'Permiso de sesión insuficiente', 403);
    const account = this.store.accountById(session.account_id, true);
    requireThat(account?.status === 'active', 'La cuenta no está activa', 403);
    this.store.touchAccountSession(session.token_hash, this.now());
    return { session, account };
  }

  logout(header) {
    const { session } = this.authenticate(header);
    this.store.deleteAccountSession(session.token_hash);
  }

  gameToken(header) {
    const { account } = this.authenticate(header, ['account']);
    return this.issue(account.account_id, 'game', GAME_TTL);
  }

  updatePassword(accountId, value) {
    const secret = password(value), salt = randomBytes(16).toString('hex');
    this.store.updatePlayerAccount(accountId, { passwordHash: derivePassword(secret, salt), passwordSalt: salt });
    this.store.deleteAccountSessions(accountId);
  }
}
