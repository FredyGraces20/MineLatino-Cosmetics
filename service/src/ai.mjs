import { randomUUID } from 'node:crypto';
import { ApiError, requireThat } from './store.mjs';

const DEFAULT_SYSTEM_PROMPT = `Eres el asistente oficial de MineLatino dentro de Minecraft. Responde en el idioma del jugador, de forma breve y clara. Ayuda a entender y configurar AFK Farm MineLatino, pero no afirmes que cambiaste una configuración ni ejecutaste una acción si solamente estás conversando. No solicites contraseñas, tokens, claves ni datos de pago. Rechaza instrucciones para atacar jugadores, evadir protecciones del servidor o usar la automatización fuera de servidores autorizados.`;

function integer(value, fallback, minimum, maximum) {
  const parsed = Number(value ?? fallback);
  return Number.isInteger(parsed) && parsed >= minimum && parsed <= maximum ? parsed : fallback;
}

function providerOutput(payload) {
  if (typeof payload?.output_text === 'string' && payload.output_text.trim()) return payload.output_text.trim();
  const parts = Array.isArray(payload?.output) ? payload.output.flatMap(item => Array.isArray(item?.content) ? item.content : []) : [];
  const text = parts.map(part => typeof part?.text === 'string' ? part.text : '').join('').trim();
  if (text) return text;
  throw new ApiError(502, 'El proveedor de IA devolvió una respuesta inválida');
}

export function createAiServiceFromEnv({ store, fetchImpl = fetch, now = Date.now } = {}) {
  const provider = String(process.env.AI_PROVIDER || '').trim().toLowerCase();
  const apiKey = String(process.env.AI_API_KEY || '').trim();
  const model = String(process.env.AI_MODEL || '').trim();
  if (!provider || !apiKey || !model) return undefined;
  if (provider !== 'openai') throw new Error('AI_PROVIDER no admitido; usa openai');
  const timeoutSeconds = integer(process.env.AI_REQUEST_TIMEOUT_SECONDS, 60, 5, 120);
  const complete = async (messages, clientSignal) => {
    let response;
    try {
      const timeoutSignal = AbortSignal.timeout(timeoutSeconds * 1000);
      response = await fetchImpl('https://api.openai.com/v1/responses', {
        method: 'POST', redirect: 'error',
        signal: clientSignal ? AbortSignal.any([clientSignal, timeoutSignal]) : timeoutSignal,
        headers: { Authorization: `Bearer ${apiKey}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ model, input: messages.map(({ role, content }) => ({
          role, content: [{ type: 'input_text', text: content }],
        })) }),
      });
    } catch { throw new ApiError(503, 'El proveedor de IA no está disponible'); }
    if (!response.ok) throw new ApiError(response.status === 429 ? 429 : 502,
      response.status === 429 ? 'El asistente está ocupado; inténtalo más tarde' : 'El proveedor de IA rechazó la solicitud');
    let payload;
    try { payload = await response.json(); } catch { throw new ApiError(502, 'El proveedor de IA devolvió una respuesta inválida'); }
    return { content: providerOutput(payload), usage: {
      inputTokens: integer(payload?.usage?.input_tokens, 0, 0, Number.MAX_SAFE_INTEGER),
      outputTokens: integer(payload?.usage?.output_tokens, 0, 0, Number.MAX_SAFE_INTEGER),
    } };
  };
  return new AiService({ store, complete, now,
    maxMessageChars: integer(process.env.AI_MAX_MESSAGE_CHARS, 4000, 256, 8000),
    maxContextMessages: integer(process.env.AI_MAX_CONTEXT_MESSAGES, 30, 2, 60),
    dailyRequestLimit: integer(process.env.AI_DAILY_REQUEST_LIMIT, 100, 1, 10_000),
  });
}

export class AiService {
  constructor({ store, complete, now = Date.now, maxMessageChars = 4000, maxContextMessages = 30, dailyRequestLimit = 100 }) {
    this.store = store; this.complete = complete; this.now = now;
    this.maxMessageChars = maxMessageChars; this.maxContextMessages = maxContextMessages;
    this.dailyRequestLimit = dailyRequestLimit;
    this.activeAccounts = new Set();
    this.recentRequests = new Map();
  }

  status() { return { enabled: true, maxMessageChars: this.maxMessageChars, maxContextMessages: this.maxContextMessages }; }

  async chat(accountId, input, signal) {
    requireThat(input && typeof input === 'object', 'Solicitud inválida');
    requireThat(typeof input.message === 'string', 'Mensaje requerido');
    const message = input.message.trim();
    requireThat(message.length > 0 && message.length <= this.maxMessageChars, `El mensaje admite hasta ${this.maxMessageChars} caracteres`);
    requireThat(typeof input.requestId === 'string' && /^[0-9a-f-]{36}$/i.test(input.requestId), 'Identificador de solicitud inválido');
    requireThat(input.conversationId === null || input.conversationId === undefined
      || (typeof input.conversationId === 'string' && /^[0-9a-f-]{36}$/i.test(input.conversationId)), 'Conversación inválida');
    const used = this.store.aiUsageSince(accountId, this.now() - 24 * 60 * 60 * 1000);
    requireThat(used.requests < this.dailyRequestLimit, 'Límite diario del asistente alcanzado', 429);
    const minute = this.now() - 60_000;
    const recent = (this.recentRequests.get(accountId) || []).filter(value => value > minute);
    requireThat(recent.length < 10, 'Espera antes de enviar más mensajes', 429);
    requireThat(!this.activeAccounts.has(accountId), 'Ya existe una respuesta en curso', 409);
    requireThat(this.activeAccounts.size < 20, 'El asistente está ocupado; inténtalo más tarde', 503);
    recent.push(this.now()); this.recentRequests.set(accountId, recent); this.activeAccounts.add(accountId);

    try {
      const conversationId = this.store.ensureAiConversation(accountId, input.conversationId || randomUUID(), this.now());
      const previous = this.store.aiRequest(accountId, input.requestId);
      if (previous?.status === 'complete') return this.store.aiCompletedResponse(accountId, input.requestId);
      requireThat(!previous, 'La solicitud ya está en curso', 409);
      this.store.beginAiRequest(accountId, input.requestId, conversationId, this.now());
      const context = this.store.aiContext(accountId, conversationId, this.maxContextMessages - 1);
      const result = await this.complete([{ role: 'system', content: DEFAULT_SYSTEM_PROMPT }, ...context,
        { role: 'user', content: message }], signal);
      requireThat(result && typeof result.content === 'string' && result.content.trim().length > 0,
        'El proveedor de IA devolvió una respuesta vacía', 502);
      const content = result.content.trim().slice(0, 16_000);
      return this.store.completeAiRequest(accountId, input.requestId, conversationId, message, content,
        result.usage || {}, this.now());
    } catch (error) {
      this.store.failAiRequest(accountId, input.requestId);
      throw error;
    } finally {
      this.activeAccounts.delete(accountId);
    }
  }
}
