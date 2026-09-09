(function (root) {
  'use strict';

  const UNITS_PER_BLOCK = 16;
  const BASES = Object.freeze({
    // Head transform is applied after head bone translateAndRotate (Y-up space, no flip).
    // Anchor at origin so stored values map directly to the mod's head bone coordinates.
    head: Object.freeze({ position: [0, 0, 0], scale: [1, 1, 1], yaw: 0 }),
    // Mirrors CosmeticRenderer: body anchor + (0, .3, .30), Y/Z flip and 180° yaw.
    backpack: Object.freeze({ position: [0, .3, .30], scale: [1, -1, -1], yaw: Math.PI }),
  });

  const triple = (value, fallback) => Array.isArray(value) && value.length === 3
    ? value.map((entry, index) => Number.isFinite(Number(entry)) ? Number(entry) : fallback[index])
    : [...fallback];

  function normalizeSlot(slot) {
    return slot === 'head' ? 'head' : 'backpack';
  }

  function neutralTransform() {
    return { translation: [0, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1] };
  }

  function normalizeTransform(transform) {
    return {
      translation: triple(transform?.translation, [0, 0, 0]),
      rotation: triple(transform?.rotation, [0, 0, 0]),
      scale: triple(transform?.scale, [1, 1, 1]),
    };
  }

  // Same editor-to-render conversion used by CosmeticRenderer.
  // Head slot: no X/Z negation (head bone already in Y-up space).
  // Backpack slot: negate X/Z to match applyDisplayTransform convention.
  function toSceneTransform(transform, slot) {
    const value = normalizeTransform(transform);
    const negateXZ = normalizeSlot(slot) !== 'head';
    const sx = negateXZ ? -1 : 1, sz = negateXZ ? -1 : 1;
    return {
      position: [sx * value.translation[0] / UNITS_PER_BLOCK, value.translation[1] / UNITS_PER_BLOCK, sz * value.translation[2] / UNITS_PER_BLOCK],
      rotation: [sx * value.rotation[0] * Math.PI / 180, value.rotation[1] * Math.PI / 180, sz * value.rotation[2] * Math.PI / 180],
      scale: [...value.scale],
    };
  }

  function fromSceneTransform(position, rotation, scale, slot) {
    const negateXZ = normalizeSlot(slot) !== 'head';
    const sx = negateXZ ? -1 : 1, sz = negateXZ ? -1 : 1;
    return {
      translation: [sx * position[0] * UNITS_PER_BLOCK, position[1] * UNITS_PER_BLOCK, sz * position[2] * UNITS_PER_BLOCK],
      rotation: [sx * rotation[0] * 180 / Math.PI, rotation[1] * 180 / Math.PI, sz * rotation[2] * 180 / Math.PI],
      scale: [...scale],
    };
  }

  function slotBase(slot) {
    const value = BASES[normalizeSlot(slot)];
    return { position: [...value.position], scale: [...value.scale], yaw: value.yaw };
  }

  root.MineLatinoCosmeticEditor = Object.freeze({
    UNITS_PER_BLOCK,
    normalizeSlot,
    neutralTransform,
    normalizeTransform,
    toSceneTransform,
    fromSceneTransform,
    slotBase,
  });
})(globalThis);
