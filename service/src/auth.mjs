import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';
import { ApiError, requireThat, uuid } from './store.mjs';

const hash = token => createHash('sha256').update(token).digest();
export function adminMatches(header, secret) {
  return typeof header === 'string' && header.startsWith('Bearer ') && timingSafeEqual(hash(header.slice(7)), hash(secret));
}
export async function verifyMojang(username, serverId) {
  const url = new URL('https://sessionserver.mojang.com/session/minecraft/hasJoined');
  url.searchParams.set('username', username);
  url.searchParams.set('serverId', serverId);
  let response;
  try { response = await fetch(url, { signal: AbortSignal.timeout(8000), redirect: 'error' }); }
  catch { throw new ApiError(503, 'La verificación premium no está disponible'); }
  if (response.status === 204 || response.status === 404) throw new ApiError(401, 'Sesión de Minecraft no verificada');
  requireThat(response.ok, 'La verificación premium no está disponible', 503);
  let result;
  try { result = await response.json(); } catch { throw new ApiError(503, 'Respuesta de verificación inválida'); }
  requireThat(result && typeof result.name === 'string' && result.name.toLowerCase() === username.toLowerCase(), 'Identidad no verificada', 401);
  return { uuid: uuid(result.id), name: result.name };
}

export class PlayerAuth {
  constructor({ verify = verifyMojang, now = Date.now } = {}) {
    this.verify = verify; this.now = now;
    this.challenges = new Map(); this.sessions = new Map();
  }
  prune() {
    const now = this.now();
    for (const map of [this.challenges, this.sessions]) for (const [key, value] of map) if (value.expiresAt <= now) map.delete(key);
  }
  challenge(username) {
    requireThat(typeof username === 'string' && /^[a-zA-Z0-9_]{3,16}$/.test(username), 'Nombre de Minecraft inválido');
    this.prune();
    requireThat(this.challenges.size < 1000, 'Demasiadas verificaciones pendientes', 429);
    const challengeId = randomBytes(24).toString('hex'), serverId = randomBytes(20).toString('hex');
    const expiresAt = this.now() + 60_000;
    this.challenges.set(challengeId, { username, serverId, expiresAt });
    return { challengeId, serverId, expiresAt };
  }
  async complete(id) {
    this.prune();
    const challenge = this.challenges.get(id);
    requireThat(challenge, 'Desafío caducado o utilizado', 401);
    // Consume before awaiting the network, preventing concurrent replay.
    this.challenges.delete(id);
    const identity = await this.verify(challenge.username, challenge.serverId);
    requireThat(challenge.expiresAt > this.now(), 'Desafío caducado', 401);
    requireThat(identity?.name?.toLowerCase() === challenge.username.toLowerCase(), 'Identidad no verificada', 401);
    const owner = uuid(identity.uuid);
    requireThat(this.sessions.size < 10_000, 'Demasiadas sesiones', 503);
    const token = randomBytes(32).toString('base64url'), expiresAt = this.now() + 15 * 60_000;
    this.sessions.set(hash(token).toString('hex'), { uuid: owner, expiresAt });
    return { token, expiresAt, uuid: owner, name: identity.name };
  }
  player(header) {
    this.prune();
    requireThat(typeof header === 'string' && header.startsWith('Bearer '), 'Sesión requerida', 401);
    const session = this.sessions.get(hash(header.slice(7)).toString('hex'));
    requireThat(session, 'Sesión inválida o caducada', 401);
    return session.uuid;
  }
  logout(header) {
    this.player(header);
    this.sessions.delete(hash(header.slice(7)).toString('hex'));
  }
}
