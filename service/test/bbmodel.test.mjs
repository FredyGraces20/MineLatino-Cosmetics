import test from 'node:test';
import assert from 'node:assert/strict';
import { convertBbmodel } from '../src/bbmodel.mjs';

const PNG = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGIAAQAAAAUAAQ0KLbQAAAAASUVORK5CYII=';

function fixture() {
  return Buffer.from(JSON.stringify({
    meta: { format_version: '4.5', model_format: 'free' }, resolution: { width: 64, height: 32 },
    textures: [{ id: '0', uuid: 'texture-uuid', name: 'pet.png', source: `data:image/png;base64,${PNG}` }],
    elements: [{ type: 'cube', uuid: 'cube-1', from: [0, 0, 0], to: [2, 2, 2], origin: [0, 0, 0],
      faces: { north: { uv: [0, 0, 64, 32], texture: 0 } } }],
    outliner: [{ name: 'body', uuid: 'body-1', origin: [0, 0, 0], rotation: [0, 0, 90], children: ['cube-1'] }],
    animations: [{ name: 'idle', loop: 'loop', length: 2, animators: { body: { name: 'body', type: 'bone', keyframes: [
      { channel: 'position', time: 0, data_points: [{ x: '0', y: 1, z: 0 }] },
      { channel: 'position', time: 2, data_points: [{ x: 0, y: -1, z: 0 }] },
    ] } } }],
  }));
}

test('converts bbmodel hierarchy, pixel UVs, embedded textures and animations', () => {
  const result = convertBbmodel(fixture());
  assert.equal(result.model.source_format, 'bbmodel');
  assert.deepEqual(result.model.textures, { texture: 'minelatino/texture' });
  assert.deepEqual(result.model.elements[0].faces.north.uv, [0, 0, 16, 16]);
  assert.deepEqual(result.model.elements[0].minelatino_vertices.north, [[-2, 2, 0], [0, 2, 0], [0, 0, 0], [-2, 0, 0]]);
  assert.equal(result.textures[0].name, 'texture');
  assert.equal(result.textures[0].buffer.toString('base64'), PNG);
  assert.equal(result.animation.selected, 'idle');
  assert.deepEqual(result.animation.data.animations.idle.bones.body.position['2'], [0, -1, 0]);
});

test('rejects malformed and untextured bbmodel projects', () => {
  assert.throws(() => convertBbmodel(Buffer.from('{}')), /bbmodel válido/);
  const source = JSON.parse(fixture()); source.elements[0].faces.north.texture = null;
  assert.throws(() => convertBbmodel(Buffer.from(JSON.stringify(source))), /caras visibles/);
});
