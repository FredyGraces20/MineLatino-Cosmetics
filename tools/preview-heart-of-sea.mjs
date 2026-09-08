// Local, in-memory preview fixture. Does not change production data or the supplied assets.
import { mkdtempSync, copyFileSync, statSync, readFileSync, unlinkSync, rmdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve, basename } from 'node:path';
import { createHash } from 'node:crypto';
import { Store } from '../service/src/store.mjs';
import { createApi } from '../service/src/api.mjs';
import { createHttpServer } from '../service/src/http.mjs';
const root = process.env.HEART_OF_SEA_ASSETS;
if (!root) throw new Error('Set HEART_OF_SEA_ASSETS to the supplied assets/minecraft folder');
const dir = mkdtempSync(join(tmpdir(), 'minelatino-heart-preview-'));
const store = new Store();
store.saveCosmetic('heart-of-sea-preview', { name: 'Heart of Sea · prueba local', slot: 'BACKPACK', status: 'published', expectedRevision: 0,
  product: { description: 'Modelo original con dos texturas y burbujas animadas.', amountMinor: null, currency: 'USD' } }, 'local-test');
const pack = 'elitecreatures/heart_of_sea_animated_weapon_set';
const copied = [];
function copy(source, name) {
  const target = join(dir, name); copyFileSync(source,target); copied.push(target);
  return { size: statSync(target).size, hash: createHash('sha256').update(readFileSync(target)).digest('hex') };
}
const model = copy(process.env.HEART_OF_SEA_MODEL || join(root,'models',pack,'backpack.json'),'model.json');
store.saveResource('heart-of-sea-preview','','',0,'image/png');
store.saveResourceModel('heart-of-sea-preview','model.json',model.hash,model.size);
for (const name of ['heart_of_the_sea_texture','heart_of_the_sea_animation2']) {
  const file = copy(join(root,'textures',pack,`${name}.png`),`${name}.png`);
  store.saveResourceFile('heart-of-sea-preview',name,`${name}.png`,file.hash,file.size);
}
const meta = copy(join(root,'textures',pack,'heart_of_the_sea_animation2.png.mcmeta'),'animation.mcmeta');
store.saveResourceFileMcmeta('heart-of-sea-preview','heart_of_the_sea_animation2','animation.mcmeta',meta.size);
const origin = 'http://127.0.0.1:8788';
const server = createHttpServer(createApi({ store, resourceDir: dir, origin, adminToken: createHash('sha256').update(dir).digest('hex') }),origin);
server.listen(8788,'127.0.0.1',()=>console.log('Heart of Sea preview fixture: '+origin));
function close() {
  server.close(()=>{
    store.close();
    // Remove only exact files created in this unique temporary leaf, never source assets.
    if (resolve(dir).startsWith(resolve(tmpdir()) + '/') || resolve(dir).startsWith(resolve(tmpdir()) + '\\')) {
      if (basename(dir).startsWith('minelatino-heart-preview-')) { for (const file of copied) unlinkSync(file); rmdirSync(dir); }
    }
    process.exit(0);
  });
}
process.once('SIGINT',close); process.once('SIGTERM',close);
