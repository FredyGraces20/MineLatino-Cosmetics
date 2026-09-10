import { createHash, randomBytes, randomUUID, scrypt, timingSafeEqual } from 'node:crypto';
import { promisify } from 'node:util';
import { ApiError, requireThat } from './store.mjs';

const SESSION_TTL = 30 * 24 * 60 * 60 * 1000;
const GAME_TTL = 24 * 60 * 60 * 1000;
const RESET_TTL = 15 * 60 * 1000;
const tokenHash = token => createHash('sha256').update(token).digest('hex');
const RESET_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

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

const scryptAsync = promisify(scrypt);
async function derivePassword(value, salt) {
  return (await scryptAsync(value, Buffer.from(salt, 'hex'), 32, { N: 16384, r: 8, p: 1 })).toString('hex');
}

function resetCode(value) {
  requireThat(typeof value === 'string', 'Código de recuperación inválido o caducado');
  const normalized = value.replaceAll('-', '').replaceAll(' ', '').toUpperCase();
  requireThat(new RegExp(`^[${RESET_ALPHABET}]{12}$`).test(normalized), 'Código de recuperación inválido o caducado');
  return normalized;
}

function newResetCode() {
  const bytes = randomBytes(12);
  const raw = Array.from(bytes, value => RESET_ALPHABET[value & 31]).join('');
  return `${raw.slice(0, 4)}-${raw.slice(4, 8)}-${raw.slice(8)}`;
}

export class AccountAuth {
  constructor({ store, now = Date.now, recovery } = {}) {
    this.store = store; this.now = now; this.recovery = recovery;
    this.recoveryCooldowns = new Map();
    this.loginFailures = new Map();
  }

  async register(input) {
    const normalizedEmail = email(input.email), playerNick = nick(input.nick), secret = password(input.password);
    requireThat(!this.store.accountByEmail(normalizedEmail, true), 'Ya existe una cuenta con ese correo', 409);
    const salt = randomBytes(16).toString('hex');
    const account = this.store.createPlayerAccount({
      accountId: randomUUID().replaceAll('-', ''), email: normalizedEmail, nick: playerNick,
      passwordHash: await derivePassword(secret, salt), passwordSalt: salt,
    });
    return { account, ...this.issue(account.accountId, 'account', SESSION_TTL) };
  }

  async login(input) {
    const normalizedEmail = email(input.email), secret = password(input.password);
    const failureKey = tokenHash(normalizedEmail), failure = this.loginFailures.get(failureKey);
    if (failure?.until > this.now() && failure.count >= 10) throw new ApiError(429, 'Demasiados intentos; espera unos minutos');
    const row = this.store.accountByEmail(normalizedEmail, true);
    if (!row) {
      await derivePassword(secret, '0'.repeat(32)); this.recordLoginFailure(failureKey);
      throw new ApiError(401, 'Correo o contraseña incorrectos');
    }
    requireThat(row.status === 'active', 'La cuenta no está activa', 403);
    const candidate = Buffer.from(await derivePassword(secret, row.password_salt), 'hex');
    const expected = Buffer.from(row.password_hash, 'hex');
    if (candidate.length !== expected.length || !timingSafeEqual(candidate, expected)) {
      this.recordLoginFailure(failureKey); throw new ApiError(401, 'Correo o contraseña incorrectos');
    }
    this.loginFailures.delete(failureKey);
    return { account: this.store.publicPlayerAccount(row), ...this.issue(row.account_id, 'account', SESSION_TTL) };
  }

  recordLoginFailure(key) {
    const now = this.now(), previous = this.loginFailures.get(key);
    const current = previous?.until > now ? previous : { count: 0, until: now + 15 * 60_000 };
    current.count++; this.loginFailures.set(key, current);
  }

  async verifyPassword(row, value) {
    const secret = password(value);
    const candidate = Buffer.from(await derivePassword(secret, row.password_salt), 'hex');
    const expected = Buffer.from(row.password_hash, 'hex');
    return candidate.length === expected.length && timingSafeEqual(candidate, expected);
  }

  async requirePassword(accountId, value) {
    const row = this.store.accountById(accountId, true);
    requireThat(row && await this.verifyPassword(row, value), 'La contraseña actual es incorrecta', 401);
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

  async updatePassword(accountId, input) {
    const row = this.store.accountById(accountId, true);
    requireThat(row && await this.verifyPassword(row, input.currentPassword), 'La contraseña actual es incorrecta', 401);
    const secret = password(input.password);
    requireThat(input.currentPassword !== secret, 'La contraseña nueva debe ser diferente');
    const salt = randomBytes(16).toString('hex');
    this.store.updatePlayerAccount(accountId, { passwordHash: await derivePassword(secret, salt), passwordSalt: salt });
    this.store.deleteAccountSessions(accountId);
  }

  issuePasswordReset(accountId, actor = 'system') {
    const code = newResetCode(), expiresAt = this.now() + RESET_TTL;
    this.store.createPasswordReset(accountId, tokenHash(resetCode(code)), expiresAt, actor, this.now());
    return { code, expiresAt };
  }

  async requestPasswordReset(input) {
    const normalizedEmail = email(input.email);
    const row = this.store.accountByEmail(normalizedEmail, true);
    const delivery = this.recovery?.enabled ? 'email' : 'support';
    if (row?.status === 'active') {
      const cooldownKey = tokenHash(normalizedEmail);
      if ((this.recoveryCooldowns.get(cooldownKey) ?? 0) > this.now()) return { ok: true, delivery };
      this.recoveryCooldowns.set(cooldownKey, this.now() + 60_000);
      const reset = this.issuePasswordReset(row.account_id);
      if (this.recovery?.enabled) {
        try { await this.recovery.send({ email: normalizedEmail, nick: row.nick, ...reset }); }
        catch (error) { console.error(`[password-reset] delivery failed: ${error instanceof Error ? error.message : 'unknown error'}`); }
      }
    }
    return { ok: true, delivery };
  }

  async resetPassword(input) {
    const normalizedEmail = email(input.email), code = resetCode(input.code), secret = password(input.password);
    const salt = randomBytes(16).toString('hex');
    return this.store.consumePasswordReset(normalizedEmail, tokenHash(code), {
      passwordHash: await derivePassword(secret, salt), passwordSalt: salt,
    }, this.now());
  }
}
