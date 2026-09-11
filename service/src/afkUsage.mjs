import { randomUUID } from 'node:crypto';
import { requireThat } from './store.mjs';

/** Server-authoritative AFK time leases. Clients never submit consumed time. */
export class AfkUsageService {
  constructor({ store, now = Date.now } = {}) { this.store = store; this.now = now; }

  status(accountId) {
    const active = this.store.activeAfkSession(accountId);
    if (active) {
      const age = this.now() - active.last_heartbeat_at;
      const settled = this.store.settleAfkSession(accountId, active.id, this.now(), age > 90_000);
      return this.view(settled.remainingSeconds, settled.session);
    }
    return this.view(this.store.afkBalance(accountId).remainingSeconds, null);
  }

  start(accountId) {
    const previous = this.store.activeAfkSession(accountId);
    if (previous) this.store.settleAfkSession(accountId, previous.id, this.now(), true);
    const balance = this.store.afkBalance(accountId).remainingSeconds;
    requireThat(balance > 0, 'No tienes tiempo disponible para AFK Farm', 402);
    return this.view(balance, this.store.createAfkSession(accountId, randomUUID(), this.now()));
  }

  heartbeat(accountId, sessionId) {
    requireThat(typeof sessionId === 'string' && /^[0-9a-f-]{36}$/i.test(sessionId), 'Sesión AFK inválida');
    const settled = this.store.settleAfkSession(accountId, sessionId, this.now(), false);
    return this.view(settled.remainingSeconds, settled.session);
  }

  stop(accountId, sessionId) {
    requireThat(typeof sessionId === 'string' && /^[0-9a-f-]{36}$/i.test(sessionId), 'Sesión AFK inválida');
    const settled = this.store.settleAfkSession(accountId, sessionId, this.now(), true);
    return this.view(settled.remainingSeconds, settled.session);
  }

  adminChange(accountId, input, actor) {
    const active = this.store.activeAfkSession(accountId);
    if (active) this.store.settleAfkSession(accountId, active.id, this.now(), false);
    return this.store.changeAfkBalance(accountId, input.seconds, input.mode, actor, input.reason ?? '', this.now());
  }

  view(remainingSeconds, session) {
    return { remainingSeconds, allowed: remainingSeconds > 0,
      active: session?.status === 'active', sessionId: session?.status === 'active' ? session.id : null,
      exhausted: remainingSeconds === 0 };
  }
}
