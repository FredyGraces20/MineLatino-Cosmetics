function htmlEscape(value) {
  return String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');
}

export function createPasswordRecoveryFromEnv(env = process.env, request = fetch) {
  const apiKey = env.COSMETICS_RESEND_API_KEY?.trim();
  const from = env.COSMETICS_EMAIL_FROM?.trim();
  if (!apiKey || !from) return { enabled: false, async send() {} };
  return {
    enabled: true,
    async send({ email, nick, code, expiresAt }) {
      const minutes = Math.max(1, Math.ceil((expiresAt - Date.now()) / 60_000));
      const response = await request('https://api.resend.com/emails', {
        method: 'POST',
        headers: { Authorization: `Bearer ${apiKey}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({
          from, to: [email], subject: 'Recupera tu cuenta MineLatino',
          text: `Hola ${nick}. Tu código de recuperación es ${code}. Caduca en ${minutes} minutos. Si no lo solicitaste, ignora este correo.`,
          html: `<p>Hola ${htmlEscape(nick)}.</p><p>Tu código de recuperación de MineLatino es:</p><p style="font-size:24px;font-weight:700;letter-spacing:3px">${htmlEscape(code)}</p><p>Caduca en ${minutes} minutos. Si no lo solicitaste, ignora este correo.</p>`,
        }),
      });
      if (!response.ok) throw new Error(`Resend HTTP ${response.status}`);
    },
  };
}
