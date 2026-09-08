import { createServer } from 'node:http';
import { Readable } from 'node:stream';
import { readFileSync, existsSync, statSync } from 'node:fs';
import { join, extname } from 'node:path';

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'application/javascript', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png' };

/** Transport only; injectable API keeps HTTP smoke tests network-isolated. */
export function createHttpServer(api, origin, publicDir = null) {
  return createServer({ maxHeaderSize: 8192, requestTimeout: 15_000, headersTimeout: 10_000 }, async (req, res) => {
    // Serve static files for the admin panel (only GET, only from publicDir)
    if (publicDir && req.method === 'GET' && !req.url.startsWith('/v1/') && !req.url.startsWith('/health')) {
      let filePath = join(publicDir, req.url === '/' ? 'index.html' : req.url.split('?')[0]);
      // Prevent path traversal
      if (!filePath.startsWith(publicDir) || !existsSync(filePath) || !statSync(filePath).isFile()) {
        filePath = join(publicDir, 'index.html');
      }
      if (existsSync(filePath)) {
        const ext = extname(filePath);
        res.writeHead(200, { 'Content-Type': MIME[ext] || 'application/octet-stream', 'Cache-Control': 'no-store' });
        res.end(readFileSync(filePath));
        return;
      }
    }
    try {
      const headers = new Headers();
      for (let i = 0; i < req.rawHeaders.length; i += 2) headers.append(req.rawHeaders[i], req.rawHeaders[i + 1]);
      const request = new Request(`${origin}${req.url}`, {
        method: req.method, headers,
        ...(['GET', 'HEAD'].includes(req.method) ? {} : { body: Readable.toWeb(req), duplex: 'half' }),
      });
      const response = await api(request, req.socket.remoteAddress);
      res.writeHead(response.status, Object.fromEntries(response.headers));
      res.end(Buffer.from(await response.arrayBuffer()));
    } catch { res.writeHead(400, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }); res.end('{"error":"Solicitud inválida"}'); }
  });
}
