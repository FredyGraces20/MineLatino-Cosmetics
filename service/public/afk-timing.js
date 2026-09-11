(function (global) {
  'use strict';

  const LIMITS = Object.freeze({ commands: 10, postJoin: 300, between: 60, movement: 300 });

  function seconds(value, name, maximum) {
    const parsed = Number(value);
    if (!Number.isInteger(parsed) || parsed < 0 || parsed > maximum)
      throw new Error(`${name} debe estar entre 0 y ${maximum} segundos.`);
    return parsed;
  }

  function build(input = {}) {
    const source = Array.isArray(input.commands) ? input.commands : String(input.commands || '').split(/\r?\n/);
    const commands = source.map(value => String(value).trim().replace(/^\/+/, '')).filter(Boolean);
    if (commands.length > LIMITS.commands) throw new Error(`El máximo razonable es de ${LIMITS.commands} comandos.`);
    if (commands.some(command => command.length > 256)) throw new Error('Ningún comando puede superar 256 caracteres.');

    const postJoin = seconds(input.postJoin, 'La espera después de entrar', LIMITS.postJoin);
    const between = seconds(input.between, 'La espera entre comandos', LIMITS.between);
    const movement = seconds(input.movement, 'La espera antes del recorrido', LIMITS.movement);
    const events = [{ at: 0, text: 'Mundo y jugador completamente cargados', kind: 'world' }];
    let at = 0;
    if (commands.length) commands.forEach((command, index) => {
      at += index === 0 ? postJoin : between;
      events.push({ at, text: `Ejecutar /${command}`, kind: 'command' });
    });
    at += movement;
    events.push({ at, text: 'Comenzar recorrido grabado', kind: 'movement' });
    return { commands, events, total: at };
  }

  global.MineLatinoAfkTiming = Object.freeze({ LIMITS, build });
})(typeof globalThis !== 'undefined' ? globalThis : window);
