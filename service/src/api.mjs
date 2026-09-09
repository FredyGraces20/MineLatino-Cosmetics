import { ApiError, requireThat, uuid, cosmeticId } from './store.mjs';
import { PlayerAuth } from './auth.mjs';
import { createHash, randomBytes, pbkdf2Sync } from 'node:crypto';
import { writeFileSync, readFileSync, unlinkSync, existsSync, mkdirSync } from 'node:fs';
import { join, extname, basename } from 'node:path';

const ALLOWED_EXTENSIONS = new Set(['.png', '.json']);
const MAX_RESOURCE_SIZE = 2 * 1024 * 1024; // 2 MB

async function body(request) {
  requireThat(request.headers.get('content-type')?.split(';')[0].trim() === 'application/json', 'Se requiere application/json', 415);
  const reader = request.body?.getReader();
  requireThat(reader, 'Cuerpo requerido');
  let size = 0; const chunks = [];
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > 16_384) { await reader.cancel(); throw new ApiError(413, 'Solicitud demasiado grande'); }
      chunks.push(value);
    }
    const data = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    requireThat(data && typeof data === 'object' && !Array.isArray(data), 'Objeto JSON requerido');
    return data;
  } catch (error) { if (error instanceof ApiError) throw error; throw new ApiError(400, 'JSON inválido'); }
}

async function binaryBody(request, maxSize) {
  const reader = request.body?.getReader();
  requireThat(reader, 'Cuerpo requerido');
  let size = 0; const chunks = [];
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > maxSize) { await reader.cancel(); throw new ApiError(413, 'Archivo demasiado grande'); }
      chunks.push(value);
    }
    return Buffer.concat(chunks);
  } catch (error) { if (error instanceof ApiError) throw error; throw new ApiError(400, 'Cuerpo inválido'); }
}

function offset(url) {
  const value = url.searchParams.get('offset') ?? '0';
  requireThat(/^\d{1,7}$/.test(value), 'Paginación inválida'); return Number(value);
}

function validateResourceFile(buffer, filename) {
  const ext = extname(filename).toLowerCase();
  requireThat(ALLOWED_EXTENSIONS.has(ext), 'Solo se permiten archivos PNG o JSON', 415);
  requireThat(buffer.length > 0, 'Archivo vacío');
  requireThat(buffer.length <= MAX_RESOURCE_SIZE, 'Archivo demasiado grande (máx. 2 MB)', 413);
  // Reject path traversal in filename
  const safe = basename(filename);
  requireThat(safe === filename && !safe.includes('..') && !safe.includes('/') && !safe.includes('\\'), 'Nombre de archivo inválido');
  // PNG magic bytes
  if (ext === '.png') {
    requireThat(buffer[0] === 0x89 && buffer[1] === 0x50 && buffer[2] === 0x4E && buffer[3] === 0x47, 'Archivo PNG inválido');
  }
  // JSON: must parse
  if (ext === '.json') {
    try { JSON.parse(buffer.toString('utf8')); } catch { throw new ApiError(400, 'JSON inválido'); }
  }
  return ext;
}

export function createApi({ store, adminToken, adminAuth, resourceDir, origin = 'http://127.0.0.1:8787', playerAuth = new PlayerAuth(), premiumEnabled = false, now = Date.now }) {
  requireThat(typeof adminToken === 'string' && adminToken.length >= 32, 'Configura una clave administrativa de al menos 32 caracteres');
  if (resourceDir) mkdirSync(resourceDir, { recursive: true });
  const rates = new Map();
  const json = (data, status = 200) => Response.json(data, { status, headers: {
    'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff',
    'Content-Security-Policy': "default-src 'none'; frame-ancestors 'none'", 'Referrer-Policy': 'no-referrer',
  } });
  const binary = (buffer, contentType, cacheable = false) => new Response(buffer, { status: 200, headers: {
    'Content-Type': contentType,
    ...(cacheable ? { 'Cache-Control': 'public, max-age=3600, immutable' } : { 'Cache-Control': 'no-store' }),
    'X-Content-Type-Options': 'nosniff',
  } });
  /** Resolve admin identity from the Authorization header. */
  function requireAdmin(authorization) {
    const identity = adminAuth?.resolve(authorization, adminToken);
    requireThat(identity, 'Autorización administrativa requerida', 401);
    return identity;
  }
  return async (request, remoteAddress = 'local') => {
    try {
      const time = now();
      for (const [ip, bucket] of rates) if (bucket.until <= time) rates.delete(ip);
      requireThat(rates.has(remoteAddress) || rates.size < 5000, 'Servicio ocupado', 429);
      const bucket = rates.get(remoteAddress) ?? { count: 0, until: time + 60_000 };
      bucket.count++; rates.set(remoteAddress, bucket);
      requireThat(bucket.count <= 120, 'Demasiadas solicitudes; espera un minuto', 429);
      const url = new URL(request.url), path = url.pathname, method = request.method;
      // Only published storefront data is cross-origin readable. Admin/auth routes remain same-origin.
      const publicStorefront = method === 'GET' && (path === '/v1/storefront/catalog' || path === '/v1/storefront/payments' || /^\/v1\/resources\/[a-z0-9_-]+$/.test(path));
      requireThat(publicStorefront || !request.headers.get('origin') || request.headers.get('origin') === origin, 'Origen no permitido', 403);
      const authorization = request.headers.get('authorization');

      if (publicStorefront && path === '/v1/storefront/payments') {
        return Response.json({ providers: ['paypal', 'binance', 'mercadopago'].map(id => ({ id, enabled: false })), checkoutEnabled: false },
          { headers: { 'Access-Control-Allow-Origin': '*', 'Cache-Control': 'no-store' } });
      }
      if (publicStorefront && path === '/v1/storefront/catalog') {
        const start = offset(url);
        const items = store.catalog(false, start).map(item => {
          const resource = store.getResource(item.id);
          const files = store.getResourceFiles(item.id);
          const hasTexture = !!resource?.file_path || files.length > 0;
          const petAnimation=store.getPetAnimation(item.id);
          const hashSource = createHash('sha256').update(JSON.stringify([resource?.sha256, resource?.model_sha256,
            files.map(f => [f.name, f.sha256, f.uploaded_at, f.mcmeta_path, f.mcmeta_size]),
            petAnimation?.sha256,petAnimation?.animation_name,petAnimation?.updated_at])).digest('hex');
          return { ...item, ...store.product(item.id), hasTexture, hasModel: !!resource?.model_path,
            textureCount: files.length,
            resourceVersion: hashSource ? hashSource.slice(0, 12) : String(item.revision) };
        });
        return Response.json({ items, nextOffset: items.length === 50 ? start + 50 : null },
          { headers: { 'Access-Control-Allow-Origin': '*', 'Cache-Control': 'no-store' } });
      }
      // Fail closed until a verified payment adapter AND premium checkout authentication are installed.
      if (method === 'POST' && path === '/v1/storefront/checkout') throw new ApiError(503, 'Los pagos todavía no están configurados');

      // ── Resource distribution (public, no auth) ───────────────────────
      const resourceMatch = path.match(/^\/v1\/resources\/([a-z0-9_-]+)$/);
      if (method === 'GET' && resourceMatch) {
        requireThat(resourceDir, 'Recursos no disponibles', 503);
        const id = cosmeticId(resourceMatch[1]);
        const res = store.getResource(id);
        requireThat(res, 'Recurso no encontrado', 404);
        if (request.headers.get('origin') && request.headers.get('origin') !== origin) {
          requireThat(store.cosmetic(id).status === 'published', 'Recurso no publicado', 404);
        }
        const typeParam = url.searchParams.get('type');
        const fileName = url.searchParams.get('file');
        if (typeParam === 'manifest') {
          const files = store.getResourceFiles(id).map(f => ({ name: f.name, hasMcmeta: !!f.mcmeta_path }));
          return Response.json({ files, hasLegacy: !!res.file_path }, { headers: { 'Access-Control-Allow-Origin': '*', 'Cache-Control': 'no-store' } });
        }
        if (typeParam === 'animation-config') {
          const animation = store.getPetAnimation(id);
          return Response.json({ animation: animation?.animation_name ?? null, hasFile: !!animation?.file_path },
            { headers: { 'Access-Control-Allow-Origin': '*', 'Cache-Control': 'no-store' } });
        }
        if (typeParam === 'animation') {
          const animation = store.getPetAnimation(id);
          requireThat(animation?.file_path, 'Animación no disponible', 404);
          const animationFile = join(resourceDir, animation.file_path);
          requireThat(existsSync(animationFile), 'Archivo de animación no encontrado', 404);
          const data = readFileSync(animationFile);
          requireThat(createHash('sha256').update(data).digest('hex') === animation.sha256, 'Integridad de animación comprometida', 500);
          const response = binary(data, 'application/json', true);
          response.headers.set('Access-Control-Allow-Origin', '*');
          return response;
        }
        // Serve model JSON if requested via query param
        if (typeParam === 'model') {
          requireThat(res.model_path, 'Modelo no disponible', 404);
          const modelFile = join(resourceDir, res.model_path);
          requireThat(existsSync(modelFile), 'Archivo de modelo no encontrado', 404);
          const modelData = readFileSync(modelFile);
          if (res.model_sha256) {
            const hash = createHash('sha256').update(modelData).digest('hex');
            requireThat(hash === res.model_sha256, 'Integridad del modelo comprometida', 500);
          }
          const response = binary(modelData, 'application/json', true);
          response.headers.set('Access-Control-Allow-Origin', '*');
          return response;
        }
        // Serve named file (multi-texture support)
        if (fileName) {
          requireThat(/^[a-z0-9_]{1,32}$/.test(fileName), 'Nombre de archivo inválido');
          const file = store.getResourceFile(id, fileName);
          requireThat(file, 'Archivo no encontrado', 404);
          // Serve mcmeta if requested
          if (typeParam === 'mcmeta') {
            requireThat(file.mcmeta_path, 'Mcmeta no disponible', 404);
            const mcmetaFile = join(resourceDir, file.mcmeta_path);
            requireThat(existsSync(mcmetaFile), 'Archivo mcmeta no encontrado', 404);
            const response = binary(readFileSync(mcmetaFile), 'application/json', true);
            response.headers.set('Access-Control-Allow-Origin', '*');
            return response;
          }
          // Serve texture file
          const filePath = join(resourceDir, file.file_path);
          requireThat(existsSync(filePath), 'Archivo no encontrado', 404);
          const fileData = readFileSync(filePath);
          const hash = createHash('sha256').update(fileData).digest('hex');
          requireThat(hash === file.sha256, 'Integridad comprometida', 500);
          const response = binary(fileData, 'image/png', true);
          response.headers.set('Access-Control-Allow-Origin', '*');
          return response;
        }
        // Default: serve primary texture (backward compatible)
        if (res.file_path) {
          const filePath = join(resourceDir, res.file_path);
          requireThat(existsSync(filePath), 'Archivo no encontrado', 404);
          const fileData = readFileSync(filePath);
          const hash = createHash('sha256').update(fileData).digest('hex');
          requireThat(hash === res.sha256, 'Integridad comprometida', 500);
          const response = binary(fileData, res.content_type, true);
          response.headers.set('Access-Control-Allow-Origin', '*');
          return response;
        }
        // Fallback: serve first file from resource_files (multi-texture cosmetics)
        const allFiles = store.getResourceFiles(id);
        const fallback = allFiles.find(f => f.name === 'texture') || allFiles[0];
        requireThat(fallback, 'Textura no disponible', 404);
        const fbPath = join(resourceDir, fallback.file_path);
        requireThat(existsSync(fbPath), 'Archivo no encontrado', 404);
        const fbData = readFileSync(fbPath);
        const fbHash = createHash('sha256').update(fbData).digest('hex');
        requireThat(fbHash === fallback.sha256, 'Integridad comprometida', 500);
        const fbResponse = binary(fbData, 'image/png', true);
        fbResponse.headers.set('Access-Control-Allow-Origin', '*');
        return fbResponse;
      }

      // ── Admin auth (no prior auth required) ─────────────────────────────
      if (method === 'POST' && path === '/v1/admin/auth/bootstrap') {
        const input = await body(request);
        requireThat(adminAuth, 'Autenticación administrativa no configurada', 503);
        return json(adminAuth.bootstrap(input.username, input.password, authorization?.startsWith('Bearer ') ? authorization.slice(7) : null), 201);
      }
      if (method === 'POST' && path === '/v1/admin/auth/login') {
        const input = await body(request);
        requireThat(adminAuth, 'Autenticación administrativa no configurada', 503);
        return json(adminAuth.login(input.username, input.password));
      }
      if (method === 'POST' && path === '/v1/admin/auth/logout') {
        adminAuth?.logout(authorization);
        return json({ ok: true });
      }

      // ── All other /v1/admin/* routes require admin auth ─────────────────
      if (path.startsWith('/v1/admin/')) {
        const admin = requireAdmin(authorization);
        const actor = admin.username;

        // Admin account management
        if (method === 'GET' && path === '/v1/admin/accounts') return json({ items: store.listAdmins() });
        if (method === 'POST' && path === '/v1/admin/accounts') {
          requireThat(admin.role === 'superadmin', 'Permiso requerido', 403);
          const input = await body(request);
          const salt = randomBytes(16).toString('hex');
          const passwordHash = pbkdf2Sync(input.password, salt, 100_000, 32, 'sha256').toString('hex');
          return json(store.createAdmin(input.username, passwordHash, salt, input.role || 'admin'), 201);
        }
        if (method === 'DELETE' && path.startsWith('/v1/admin/accounts/')) {
          requireThat(admin.role === 'superadmin', 'Permiso requerido', 403);
          const username = path.slice('/v1/admin/accounts/'.length);
          return json(store.deleteAdmin(username, actor));
        }

        // Player search
        if (method === 'GET' && path === '/v1/admin/players') {
          const query = url.searchParams.get('q') ?? '';
          return json({ items: store.searchPlayers(query, offset(url)) });
        }

        // Catalog, grants, revocations, owners, audit, menu
        if (method === 'GET' && path === '/v1/admin/cosmetics/catalog') {
          const items = store.catalog(true, offset(url)).map(item => {
            const petAnimation=store.getPetAnimation(item.id);
            let names=[];
            if (petAnimation?.file_path && resourceDir) {
              try { names=Object.keys(JSON.parse(readFileSync(join(resourceDir,petAnimation.file_path),'utf8')).animations||{}); } catch { names=[]; }
            }
            return { ...item, product: store.product(item.id), resource: store.getResource(item.id) || null,
              files: store.getResourceFiles(item.id), petAnimation: petAnimation ? { ...petAnimation,names } : null };
          });
          return json({ items });
        }
        const itemMatch = path.match(/^\/v1\/admin\/cosmetics\/catalog\/([a-z0-9_-]+)$/);
        if (method === 'PUT' && itemMatch) return json(store.saveCosmetic(itemMatch[1], await body(request), actor));

        // Resource upload (texture PNG)
        const resourceUploadMatch = path.match(/^\/v1\/admin\/cosmetics\/catalog\/([a-z0-9_-]+)\/resource$/);
        if (method === 'PUT' && resourceUploadMatch) {
          requireThat(resourceDir, 'Recursos no disponibles', 503);
          const id = cosmeticId(resourceUploadMatch[1]);
          store.cosmetic(id); // verify exists
          const filename = request.headers.get('x-filename') || 'resource.png';
          const buffer = await binaryBody(request, MAX_RESOURCE_SIZE);
          const ext = validateResourceFile(buffer, filename);
          requireThat(ext === '.png', 'La textura debe ser un archivo PNG', 415);
          const sha256 = createHash('sha256').update(buffer).digest('hex');
          const filePath = `${id}${ext}`;
          // Delete old texture file if replacing
          const old = store.getResource(id);
          if (old?.file_path) {
            const oldPath = join(resourceDir, old.file_path);
            if (existsSync(oldPath)) unlinkSync(oldPath);
          }
          writeFileSync(join(resourceDir, filePath), buffer);
          const contentType = 'image/png';
          const resource = store.saveResource(id, filePath, sha256, buffer.length, contentType);
          store.audit(actor, 'resource.upload', { cosmeticId: id, type: 'texture', sha256, fileSize: buffer.length });
          return json(resource);
        }

        // Model upload (JSON)
        const modelUploadMatch = path.match(/^\/v1\/admin\/cosmetics\/catalog\/([a-z0-9_-]+)\/model$/);
        if (method === 'PUT' && modelUploadMatch) {
          requireThat(resourceDir, 'Recursos no disponibles', 503);
          const id = cosmeticId(modelUploadMatch[1]);
          store.cosmetic(id); // verify exists
          const filename = request.headers.get('x-filename') || 'model.json';
          const buffer = await binaryBody(request, MAX_RESOURCE_SIZE);
          const ext = validateResourceFile(buffer, filename);
          requireThat(ext === '.json', 'El modelo debe ser un archivo JSON', 415);
          const modelSha256 = createHash('sha256').update(buffer).digest('hex');
          const modelPath = `${id}_model${ext}`;
          // Delete old model file if replacing
          const old = store.getResource(id);
          if (old?.model_path) {
            const oldModelPath = join(resourceDir, old.model_path);
            if (existsSync(oldModelPath)) unlinkSync(oldModelPath);
          }
          writeFileSync(join(resourceDir, modelPath), buffer);
          // Ensure resource row exists (create minimal one if not)
          const existing = store.getResource(id);
          if (!existing) {
            store.saveResource(id, '', '', 0, 'image/png');
          }
          const resource = store.saveResourceModel(id, modelPath, modelSha256, buffer.length);
          store.audit(actor, 'resource.upload', { cosmeticId: id, type: 'model', sha256: modelSha256, fileSize: buffer.length });
          return json(resource);
        }

        // Resource delete (texture)
        const resourceDeleteMatch = path.match(/^\/v1\/admin\/cosmetics\/catalog\/([a-z0-9_-]+)\/resource$/);
        if (method === 'DELETE' && resourceDeleteMatch) {
          requireThat(resourceDir, 'Recursos no disponibles', 503);
          const id = cosmeticId(resourceDeleteMatch[1]);
          const res = store.getResource(id);
          if (res) {
            const filePath = join(resourceDir, res.file_path);
            if (existsSync(filePath)) unlinkSync(filePath);
            // Only delete texture, keep model
            store.saveResource(id, '', '', 0, 'image/png');
            store.audit(actor, 'resource.delete', { cosmeticId: id, type: 'texture' });
          }
          return json({ deleted: !!res });
        }

        // Model delete
        const modelDeleteMatch = path.match(/^\/v1\/admin\/cosmetics\/catalog\/([a-z0-9_-]+)\/model$/);
        if (method === 'DELETE' && modelDeleteMatch) {
          requireThat(resourceDir, 'Recursos no disponibles', 503);
          const id = cosmeticId(modelDeleteMatch[1]);
          const res = store.getResource(id);
          if (res?.model_path) {
            const modelFile = join(resourceDir, res.model_path);
            if (existsSync(modelFile)) unlinkSync(modelFile);
            store.saveResourceModel(id, null, null, null);
            store.audit(actor, 'resource.delete', { cosmeticId: id, type: 'model' });
          }
          return json({ deleted: !!(res?.model_path) });
        }

        const petAnimationMatch = path.match(/^\/v1\/admin\/cosmetics\/catalog\/([a-z0-9_-]+)\/animation$/);
        if (method === 'PUT' && petAnimationMatch) {
          requireThat(resourceDir, 'Recursos no disponibles', 503);
          const id = cosmeticId(petAnimationMatch[1]);
          requireThat(store.cosmetic(id).slot === 'PET', 'El cosmético debe ser una mascota');
          const filename = request.headers.get('x-filename') || 'pet.animation.json';
          requireThat(/\.json$/i.test(filename), 'La animación debe ser JSON', 415);
          const buffer = await binaryBody(request, MAX_RESOURCE_SIZE);
          let parsed;
          try { parsed = JSON.parse(buffer.toString('utf8')); } catch { throw new ApiError(400, 'JSON de animación inválido'); }
          const names = Object.keys(parsed?.animations || {});
          requireThat(names.length > 0 && names.length <= 128, 'No se encontraron animaciones de Blockbench');
          const requested = request.headers.get('x-animation-name');
          const selected = requested && names.includes(requested) ? requested : names[0];
          const sha256 = createHash('sha256').update(buffer).digest('hex');
          const filePath = `${id}_animation.json`;
          const old = store.getPetAnimation(id);
          if (old?.file_path && old.file_path !== filePath) {
            const oldPath = join(resourceDir, old.file_path); if (existsSync(oldPath)) unlinkSync(oldPath);
          }
          writeFileSync(join(resourceDir, filePath), buffer);
          return json({ ...store.savePetAnimation(id, selected, filePath, sha256, buffer.length, actor), names });
        }
        if (method === 'PATCH' && petAnimationMatch) {
          const id = cosmeticId(petAnimationMatch[1]);
          const current = store.getPetAnimation(id);
          requireThat(current?.file_path, 'Sube primero un archivo de animación', 404);
          const input = await body(request);
          const data = JSON.parse(readFileSync(join(resourceDir, current.file_path), 'utf8'));
          requireThat(Object.hasOwn(data.animations || {}, input.animation), 'La animación elegida no existe');
          return json(store.savePetAnimation(id, input.animation, null, null, null, actor));
        }
        if (method === 'DELETE' && petAnimationMatch) {
          const id = cosmeticId(petAnimationMatch[1]);
          const old = store.getPetAnimation(id);
          if (old?.file_path) { const file = join(resourceDir, old.file_path); if (existsSync(file)) unlinkSync(file); }
          store.deletePetAnimation(id, actor);
          return json({ deleted: !!old });
        }

        // ── Multi-file resource management ──────────────────────────────
        const filesListMatch = path.match(/^\/v1\/admin\/cosmetics\/catalog\/([a-z0-9_-]+)\/files$/);
        const fileMatch = path.match(/^\/v1\/admin\/cosmetics\/catalog\/([a-z0-9_-]+)\/files\/([a-z0-9_]{1,32})$/);
        const fileMcmetaMatch = path.match(/^\/v1\/admin\/cosmetics\/catalog\/([a-z0-9_-]+)\/files\/([a-z0-9_]{1,32})\/mcmeta$/);

        // List all files for a cosmetic
        if (method === 'GET' && filesListMatch) {
          const id = cosmeticId(filesListMatch[1]);
          store.cosmetic(id);
          return json({ items: store.getResourceFiles(id) });
        }

        // Upload a texture file
        if (method === 'PUT' && fileMatch) {
          requireThat(resourceDir, 'Recursos no disponibles', 503);
          const id = cosmeticId(fileMatch[1]);
          const name = fileMatch[2];
          store.cosmetic(id);
          const filename = request.headers.get('x-filename') || `${name}.png`;
          const buffer = await binaryBody(request, MAX_RESOURCE_SIZE);
          const ext = validateResourceFile(buffer, filename);
          requireThat(ext === '.png', 'La textura debe ser un archivo PNG', 415);
          const sha256 = createHash('sha256').update(buffer).digest('hex');
          const filePath = `${id}_${name}.png`;
          // Delete old file if replacing
          const old = store.getResourceFile(id, name);
          if (old?.file_path) {
            const oldPath = join(resourceDir, old.file_path);
            if (existsSync(oldPath)) unlinkSync(oldPath);
          }
          // Delete old mcmeta if replacing
          if (old?.mcmeta_path) {
            const oldMcmeta = join(resourceDir, old.mcmeta_path);
            if (existsSync(oldMcmeta)) unlinkSync(oldMcmeta);
          }
          writeFileSync(join(resourceDir, filePath), buffer);
          store.saveResourceFile(id, name, filePath, sha256, buffer.length);
          // If name is "texture", also update legacy resources table for backward compat
          if (name === 'texture') {
            const legacyRes = store.getResource(id);
            if (legacyRes?.file_path) {
              const legacyPath = join(resourceDir, legacyRes.file_path);
              if (legacyPath !== join(resourceDir, filePath) && existsSync(legacyPath)) unlinkSync(legacyPath);
            }
            store.saveResource(id, filePath, sha256, buffer.length, 'image/png');
          }
          store.audit(actor, 'resource.file.upload', { cosmeticId: id, name, sha256, fileSize: buffer.length });
          return json(store.getResourceFile(id, name));
        }

        // Upload mcmeta for a texture
        if (method === 'PUT' && fileMcmetaMatch) {
          requireThat(resourceDir, 'Recursos no disponibles', 503);
          const id = cosmeticId(fileMcmetaMatch[1]);
          const name = fileMcmetaMatch[2];
          store.cosmetic(id);
          const file = store.getResourceFile(id, name);
          requireThat(file, 'Textura no encontrada, sube la textura primero', 404);
          const buffer = await binaryBody(request, MAX_RESOURCE_SIZE);
          // Validate it's valid JSON
          try { JSON.parse(buffer.toString('utf8')); } catch { throw new ApiError(400, 'JSON inválido'); }
          requireThat(buffer.length > 0, 'Archivo vacío');
          requireThat(buffer.length <= MAX_RESOURCE_SIZE, 'Archivo demasiado grande (máx. 2 MB)', 413);
          const mcmetaPath = `${id}_${name}.png.mcmeta`;
          // Delete old mcmeta if replacing
          if (file?.mcmeta_path) {
            const oldMcmeta = join(resourceDir, file.mcmeta_path);
            if (existsSync(oldMcmeta)) unlinkSync(oldMcmeta);
          }
          writeFileSync(join(resourceDir, mcmetaPath), buffer);
          store.saveResourceFileMcmeta(id, name, mcmetaPath, buffer.length);
          store.audit(actor, 'resource.file.mcmeta', { cosmeticId: id, name, mcmetaSize: buffer.length });
          return json(store.getResourceFile(id, name));
        }

        // Delete a texture file (+ its mcmeta)
        if (method === 'DELETE' && fileMatch) {
          requireThat(resourceDir, 'Recursos no disponibles', 503);
          const id = cosmeticId(fileMatch[1]);
          const name = fileMatch[2];
          const file = store.getResourceFile(id, name);
          if (file) {
            const filePath = join(resourceDir, file.file_path);
            if (existsSync(filePath)) unlinkSync(filePath);
            if (file.mcmeta_path) {
              const mcmetaFile = join(resourceDir, file.mcmeta_path);
              if (existsSync(mcmetaFile)) unlinkSync(mcmetaFile);
            }
            store.deleteResourceFile(id, name);
            // If name is "texture", also clear legacy resources table
            if (name === 'texture') {
              const legacyRes = store.getResource(id);
              if (legacyRes?.file_path) {
                const legacyPath = join(resourceDir, legacyRes.file_path);
                if (existsSync(legacyPath)) unlinkSync(legacyPath);
              }
              store.saveResource(id, '', '', 0, 'image/png');
            }
            store.audit(actor, 'resource.file.delete', { cosmeticId: id, name });
          }
          return json({ deleted: !!file });
        }

        // Delete only the mcmeta
        if (method === 'DELETE' && fileMcmetaMatch) {
          requireThat(resourceDir, 'Recursos no disponibles', 503);
          const id = cosmeticId(fileMcmetaMatch[1]);
          const name = fileMcmetaMatch[2];
          const file = store.getResourceFile(id, name);
          if (file?.mcmeta_path) {
            const mcmetaFile = join(resourceDir, file.mcmeta_path);
            if (existsSync(mcmetaFile)) unlinkSync(mcmetaFile);
            store.deleteResourceFileMcmeta(id, name);
            store.audit(actor, 'resource.file.mcmeta.delete', { cosmeticId: id, name });
          }
          return json({ deleted: !!(file?.mcmeta_path) });
        }

        if (method === 'POST' && ['/v1/admin/cosmetics/grants', '/v1/admin/cosmetics/revocations'].includes(path))
          return json(store.entitlement(await body(request), path.endsWith('/grants'), actor));
        const ownersMatch = path.match(/^\/v1\/admin\/cosmetics\/owners\/([a-z0-9_-]+)$/);
        if (method === 'GET' && ownersMatch) return json({ items: store.owners(ownersMatch[1], offset(url)) });
        if (method === 'GET' && path === '/v1/admin/audit') return json({ items: store.auditPage(offset(url)) });
        if (method === 'PUT' && path === '/v1/admin/pause-menu') return json(store.saveMenu(await body(request), actor));
        if (method === 'GET' && path === '/v1/admin/pause-menu/history') return json({ items: store.menuHistory(offset(url)) });
        if (method === 'POST' && path === '/v1/admin/pause-menu/restore') return json(store.restoreMenu(await body(request), actor));
        if (method === 'DELETE' && path.startsWith('/v1/admin/pause-menu/history/')) {
          const rev = Number(path.slice('/v1/admin/pause-menu/history/'.length));
          return json(store.deleteMenuEntry({ revision: rev }, actor));
        }

        // ── Cosmetic transforms (position/rotation/scale per slot) ────
        const transformsMatch = path.match(/^\/v1\/admin\/cosmetics\/transforms\/([a-z0-9_-]+)$/);
        if (method === 'GET' && transformsMatch) {
          const id = cosmeticId(transformsMatch[1]);
          store.cosmetic(id);
          return json({ transforms: store.getTransforms(id) });
        }
        if (method === 'PUT' && transformsMatch) {
          const id = cosmeticId(transformsMatch[1]);
          const input = await body(request);
          requireThat(input.slot && input.transform, 'Slot y transform requeridos');
          return json({ transforms: store.saveTransform(id, input.slot, input.transform, actor) });
        }
      }

      // ── Public routes ───────────────────────────────────────────────────
      if (method === 'GET' && path === '/health') return json({ ok: true, premiumEnabled, stage: 'development-api' });
      if (method === 'GET' && path === '/v1/cosmetics/catalog') {
        const items = store.catalog(false, offset(url)).map(item => ({ ...item, hasResource: !!store.getResource(item.id) }));
        return json({ items });
      }
      if (method === 'GET' && path === '/v1/client-config/pause-menu') return json(store.menu());
      if (method === 'GET' && path === '/v1/client-config/cosmetic-transforms') return json({ transforms: store.getAllTransforms() });
      if (method === 'GET' && path === '/v1/cosmetics/appearance') {
        const ids = [...new Set((url.searchParams.get('uuids') ?? '').split(',').map(uuid))];
        requireThat(ids.length <= 50, 'Máximo 50 jugadores');
        return json({ players: ids.map(id => ({ uuid: id, equipped: store.appearance(id) })) });
      }
      if (path.startsWith('/v1/auth/') || path.startsWith('/v1/cosmetics/me/')) {
        // Offline auth endpoint: works only when premium is disabled (development mode)
        if (method === 'POST' && path === '/v1/auth/offline') {
          requireThat(!premiumEnabled, 'Auth offline solo disponible en modo desarrollo', 403);
          const input = await body(request);
          requireThat(input.uuid && input.name, 'UUID y nombre requeridos', 400);
          requireThat(/^[0-9a-f]{32}$/i.test(input.uuid) || /^[0-9a-f-]{36}$/i.test(input.uuid), 'UUID inválido', 400);
          const cleanUuid = input.uuid.replace(/-/g, '');
          const offlineSession = playerAuth.createOfflineSession(cleanUuid, input.name);
          store.verifiedPlayer(cleanUuid, input.name);
          return json(offlineSession, 200);
        }
        // Premium-only auth endpoints (challenge/verify)
        if (path.startsWith('/v1/auth/challenge') || path.startsWith('/v1/auth/verify')) {
          requireThat(premiumEnabled, 'Vinculación premium aún deshabilitada en este entorno', 503);
        }
        if (method === 'POST' && path === '/v1/auth/challenge') return json(playerAuth.challenge((await body(request)).username), 201);
        if (method === 'POST' && path === '/v1/auth/verify') {
          const session = await playerAuth.complete((await body(request)).challengeId);
          store.verifiedPlayer(session.uuid, session.name); return json(session);
        }
        // Session-based endpoints: work in both premium and offline mode
        const owner = playerAuth.player(authorization);
        if (method === 'POST' && path === '/v1/auth/logout') { playerAuth.logout(authorization); return json({ ok: true }); }
        if (method === 'GET' && path === '/v1/cosmetics/me/wardrobe') return json(store.wardrobe(owner));
        if (method === 'PUT' && path === '/v1/cosmetics/me/equipment') {
          const input = await body(request);
          return json({ equipped: store.equip(owner, input.slot, input.cosmeticId) });
        }
      }
      throw new ApiError(404, 'Ruta no encontrada');
    } catch (error) {
      if (!(error instanceof ApiError)) console.error('Unexpected error:', error.message, error.stack);
      return json({ error: error instanceof ApiError ? error.message : 'Error interno' }, error instanceof ApiError ? error.status : 500);
    }
  };
}
