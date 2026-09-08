import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

function renderer(overrides = {}) {
  const context = vm.createContext({ console: { warn() {} }, performance: { now: () => 100 }, ...overrides });
  for (const file of ['three.min.js', 'cosmetic-preview.js'])
    vm.runInContext(readFileSync(new URL('../public/' + file, import.meta.url), 'utf8'), context);
  return context.MineLatinoCosmetics;
}
const model = {
  texture_size: [128, 128], textures: { main: 'pack/main', bubbles: 'pack/bubbles' },
  elements: [{ from: [0,0,0], to: [16,16,16], faces: {
    north: { texture: '#main', uv: [0,14.125,0.5,14.625], rotation: 270 },
    south: { texture: '#bubbles', uv: [0,0,16,16] },
  } }],
};

test('admin preview shares Java UV units and separates face materials', () => {
  const api = renderer(), geometry = api.cosmeticGeometry(model);
  assert.equal(geometry.groups.length, 2);
  assert.equal(geometry.groups[1].materialIndex, 1);
  assert.equal(geometry.getAttribute('uv').getX(0), 0.5/128);
  assert.equal(geometry.getAttribute('uv').getY(0), 1-14.125/128);
  geometry.dispose();
});

test('admin preview fetches each named texture, animates and retains bitmaps until disposal', async () => {
  let closed = 0;
  const requested = [];
  const api = renderer({
    fetch: async url => {
      const u = new URL(url, 'https://fixture.invalid'); requested.push(u.searchParams.get('file'));
      if (u.searchParams.get('type') === 'manifest') return Response.json({ files: [{ name: 'main', hasMcmeta: false }, { name: 'bubbles', hasMcmeta: true }] });
      if (u.searchParams.get('type') === 'mcmeta') return Response.json({ animation: { frametime: 1.8 } });
      return new Response(new Uint8Array([1]));
    },
    createImageBitmap: async () => ({ width: 32, height: 384, close() { closed++; } }),
  });
  const mesh = await api.createCosmeticMesh({ id: 'fixture' }, model, new AbortController().signal);
  assert.equal(mesh.material.length, 2);
  assert(requested.includes('main')); assert(requested.includes('bubbles'));
  assert.equal(closed, 0);
  mesh.onBeforeRender();
  assert.equal(mesh.material[1].map.repeat.y, 1/12);
  assert.equal(mesh.material[1].map.offset.y, 1-2/12);
  api.disposeCosmeticMesh(mesh);
  assert.equal(closed, 2);
});

test('admin inline scripts remain syntactically valid', () => {
  const html = readFileSync(new URL('../public/index.html', import.meta.url), 'utf8');
  for (const match of html.matchAll(/<script>([\s\S]*?)<\/script>/g)) new vm.Script(match[1]);
  assert(html.includes('src="cosmetic-preview.js"'));
  assert(!html.includes('parseBlockbenchModel('));
});
