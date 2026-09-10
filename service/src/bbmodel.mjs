import { ApiError, requireThat } from './store.mjs';

const DIRECTIONS = ['north', 'south', 'east', 'west', 'up', 'down'];
const MAX_ELEMENTS = 4096;
const MAX_TEXTURES = 32;
const MAX_ANIMATIONS = 128;

function finite(value, label, limit = 65536) {
  const number = typeof value === 'string' && value.trim() !== '' ? Number(value) : value;
  requireThat(typeof number === 'number' && Number.isFinite(number) && Math.abs(number) <= limit,
    `Valor inválido en ${label}`);
  return number;
}

function vector(value, label, fallback = [0, 0, 0]) {
  if (value === undefined || value === null || value === '') return [...fallback];
  const parts = Array.isArray(value) ? value : typeof value === 'string' ? value.trim().split(/\s+/) : [];
  requireThat(parts.length === 3, `Vector inválido en ${label}`);
  return parts.map((entry, index) => finite(entry, `${label}[${index}]`));
}

function rotateAround(point, origin, rotation) {
  let [x, y, z] = point.map((value, index) => value - origin[index]);
  // Blockbench stores Euler rotations and renders them using ZYX order.
  for (const [axis, degrees] of [['z', rotation[2]], ['y', rotation[1]], ['x', rotation[0]]]) {
    if (!degrees) continue;
    const radians = degrees * Math.PI / 180, sine = Math.sin(radians), cosine = Math.cos(radians);
    if (axis === 'x') [y, z] = [y * cosine - z * sine, y * sine + z * cosine];
    if (axis === 'y') [x, z] = [x * cosine + z * sine, -x * sine + z * cosine];
    if (axis === 'z') [x, y] = [x * cosine - y * sine, x * sine + y * cosine];
  }
  return [x + origin[0], y + origin[1], z + origin[2]].map(value => Number(value.toFixed(8)));
}

function safeTextureName(value, used, fallback) {
  let base = String(value || fallback).replace(/\.png$/i, '').toLowerCase()
    .normalize('NFKD').replace(/[\u0300-\u036f]/g, '').replace(/[^a-z0-9_]+/g, '_')
    .replace(/^_+|_+$/g, '').slice(0, 32) || fallback;
  let name = base, suffix = 2;
  while (used.has(name)) {
    const tail = `_${suffix++}`;
    name = `${base.slice(0, 32 - tail.length)}${tail}`;
  }
  used.add(name);
  return name;
}

function textureTable(source) {
  const raw = Array.isArray(source.textures) ? source.textures : [];
  requireThat(raw.length <= MAX_TEXTURES, `El .bbmodel supera el máximo de ${MAX_TEXTURES} texturas`);
  const used = new Set(), textures = [], lookup = new Map();
  raw.forEach((texture, index) => {
    requireThat(texture && typeof texture === 'object', 'Textura inválida en .bbmodel');
    const name = safeTextureName(index === 0 ? 'texture' : texture.name, used, `texture_${index + 1}`);
    const item = { name, buffer: null };
    if (typeof texture.source === 'string' && texture.source.startsWith('data:image/png;base64,')) {
      try { item.buffer = Buffer.from(texture.source.slice(texture.source.indexOf(',') + 1), 'base64'); }
      catch { throw new ApiError(400, `Textura incrustada inválida: ${texture.name || index}`); }
    }
    textures.push(item);
    for (const key of [index, String(index), texture.id, texture.uuid, texture.name]) {
      if (key !== undefined && key !== null) lookup.set(String(key), name);
    }
  });
  return { textures, lookup };
}

function hierarchy(source) {
  const paths = new Map(), hidden = new Set(), visited = new Set();
  const walk = (node, parents, visible = true) => {
    if (typeof node === 'string') {
      // Some third-party exporters serialize a list of UUIDs as one string.
      const ids = node.trim().split(/\s+/).filter(Boolean);
      for (const id of ids) {
        if (visited.has(id)) continue;
        visited.add(id); paths.set(id, parents);
        if (!visible) hidden.add(id);
      }
      return;
    }
    if (!node || typeof node !== 'object') return;
    const groupVisible = visible && node.visibility !== false && node.export !== false;
    const transform = {
      origin: vector(node.origin, `origen de grupo ${node.name || ''}`),
      rotation: vector(node.rotation, `rotación de grupo ${node.name || ''}`),
    };
    const next = [...parents, transform];
    const children = Array.isArray(node.children) ? node.children : node.children === undefined ? [] : [node.children];
    for (const child of children) walk(child, next, groupVisible);
  };
  for (const node of Array.isArray(source.outliner) ? source.outliner : []) walk(node, []);
  return { paths, hidden };
}

function faceCorners(from, to) {
  const [x, y, z] = from, [X, Y, Z] = to;
  return {
    north: [[X,Y,z],[X,y,z],[x,y,z],[x,Y,z]], south: [[x,Y,Z],[x,y,Z],[X,y,Z],[X,Y,Z]],
    east: [[X,Y,Z],[X,y,Z],[X,y,z],[X,Y,z]], west: [[x,Y,z],[x,y,z],[x,y,Z],[x,Y,Z]],
    up: [[x,Y,z],[x,Y,Z],[X,Y,Z],[X,Y,z]], down: [[x,y,Z],[x,y,z],[X,y,z],[X,y,Z]],
  };
}

function convertAnimation(source) {
  const animations = {};
  const raw = Array.isArray(source.animations) ? source.animations : [];
  requireThat(raw.length <= MAX_ANIMATIONS, `El .bbmodel supera el máximo de ${MAX_ANIMATIONS} animaciones`);
  for (const [animationIndex, animation] of raw.entries()) {
    if (!animation || typeof animation !== 'object') continue;
    const name = String(animation.name || `animation_${animationIndex + 1}`).slice(0, 128);
    const bones = {};
    for (const animator of Object.values(animation.animators || {})) {
      if (!animator || animator.type !== 'bone') continue;
      const boneName = String(animator.name || 'root').slice(0, 128), channels = {};
      for (const keyframe of Array.isArray(animator.keyframes) ? animator.keyframes : []) {
        if (!['position', 'rotation', 'scale'].includes(keyframe?.channel)) continue;
        const point = keyframe.data_points?.[0];
        if (!point) continue;
        const time = Number(keyframe.time ?? 0);
        if (!Number.isFinite(time) || time < 0 || time > 3600) continue;
        const values = ['x', 'y', 'z'].map(axis => Number(point[axis] ?? (keyframe.channel === 'scale' ? 1 : 0)));
        // Molang expressions cannot be evaluated by the lightweight pet runtime. Ignore only
        // that keyframe instead of rejecting an otherwise valid model and its static geometry.
        if (values.some(value => !Number.isFinite(value) || Math.abs(value) > 65536)) continue;
        (channels[keyframe.channel] ||= {})[String(time)] = values;
      }
      if (Object.keys(channels).length) bones[boneName] = channels;
    }
    animations[name] = {
      loop: animation.loop === true || animation.loop === 'loop',
      animation_length: Number.isFinite(Number(animation.length)) && Number(animation.length) >= 0 && Number(animation.length) <= 3600
        ? Number(animation.length) : 0,
      bones,
    };
  }
  if (!Object.keys(animations).length) return null;
  const selected = Object.keys(animations).find(name => /(^|[._ -])idle($|[._ -])/i.test(name)) || Object.keys(animations)[0];
  return { data: { format_version: '1.8.0', animations }, selected };
}

/** Convert an editable Blockbench project into MineLatino's distributable model contract. */
export function convertBbmodel(buffer) {
  let source;
  try { source = JSON.parse(buffer.toString('utf8')); }
  catch { throw new ApiError(400, 'Archivo .bbmodel inválido'); }
  requireThat(source && typeof source === 'object' && source.meta && Array.isArray(source.elements),
    'El archivo no es un proyecto .bbmodel válido');
  requireThat(source.elements.length > 0 && source.elements.length <= MAX_ELEMENTS,
    `El .bbmodel debe contener entre 1 y ${MAX_ELEMENTS} elementos`);

  const width = finite(source.resolution?.width ?? 16, 'ancho de textura', 4096);
  const height = finite(source.resolution?.height ?? 16, 'alto de textura', 4096);
  requireThat(width > 0 && height > 0, 'Resolución de textura inválida');
  const { textures: embeddedTextures, lookup } = textureTable(source);
  const { paths, hidden } = hierarchy(source);
  const elements = [];

  for (const [elementIndex, cube] of source.elements.entries()) {
    if (!cube || cube.type !== 'cube' || cube.visibility === false || cube.export === false || hidden.has(String(cube.uuid))) continue;
    const from = vector(cube.from, `from del elemento ${elementIndex}`);
    const to = vector(cube.to, `to del elemento ${elementIndex}`);
    const origin = vector(cube.origin, `origen del elemento ${elementIndex}`, from.map((value, i) => (value + to[i]) / 2));
    const cubeRotation = vector(cube.rotation, `rotación del elemento ${elementIndex}`);
    const parentTransforms = paths.get(String(cube.uuid)) || [];
    const corners = faceCorners(from, to), faces = {}, explicit = {};
    for (const direction of DIRECTIONS) {
      const face = cube.faces?.[direction];
      if (!face || face.texture === null) continue;
      const texture = lookup.get(String(face.texture));
      requireThat(texture, `La cara ${direction} referencia una textura inexistente (${face.texture})`);
      const uv = Array.isArray(face.uv) ? face.uv.map((value, i) => finite(value, `UV ${direction}[${i}]`)) : [0, 0, 0, 0];
      requireThat(uv.length === 4, `UV inválidas en la cara ${direction}`);
      const turn = finite(face.rotation ?? 0, `rotación UV ${direction}`, 360);
      requireThat([0, 90, 180, 270].includes(turn), `Rotación UV no admitida en ${direction}`);
      faces[direction] = { uv: [uv[0] * 16 / width, uv[1] * 16 / height, uv[2] * 16 / width, uv[3] * 16 / height], texture: `#${texture}`, ...(turn ? { rotation: turn } : {}) };
      explicit[direction] = corners[direction].map(point => {
        let transformed = rotateAround(point, origin, cubeRotation);
        for (let index = parentTransforms.length - 1; index >= 0; index--)
          transformed = rotateAround(transformed, parentTransforms[index].origin, parentTransforms[index].rotation);
        return transformed;
      });
    }
    if (Object.keys(faces).length) elements.push({ from, to, faces, minelatino_vertices: explicit });
  }
  requireThat(elements.length > 0, 'El .bbmodel no contiene cubos con caras visibles y texturizadas');
  const textureAliases = Object.fromEntries(embeddedTextures.map(texture => [texture.name, `minelatino/${texture.name}`]));
  return {
    model: { minelatino_format: 1, source_format: 'bbmodel', texture_size: [width, height], textures: textureAliases, elements },
    textures: embeddedTextures,
    animation: convertAnimation(source),
  };
}
