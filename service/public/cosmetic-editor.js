(function (root) {
  'use strict';

  const UNITS_PER_BLOCK = 16;
  const BASES = Object.freeze({
    // The mannequin uses Minecraft blocks and has its shoulders at y=.5.
    head: Object.freeze({ position: [0, .75, 0], scale: [1, 1, 1], yaw: 0 }),
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

  // Same editor-to-render conversion used by CosmeticRenderer.applyDisplayTransform.
  function toSceneTransform(transform) {
    const value = normalizeTransform(transform);
    return {
      position: [-value.translation[0] / UNITS_PER_BLOCK, value.translation[1] / UNITS_PER_BLOCK, -value.translation[2] / UNITS_PER_BLOCK],
      rotation: [-value.rotation[0] * Math.PI / 180, value.rotation[1] * Math.PI / 180, -value.rotation[2] * Math.PI / 180],
      scale: [...value.scale],
    };
  }

  function fromSceneTransform(position, rotation, scale) {
    return {
      translation: [-position[0] * UNITS_PER_BLOCK, position[1] * UNITS_PER_BLOCK, -position[2] * UNITS_PER_BLOCK],
      rotation: [-rotation[0] * 180 / Math.PI, rotation[1] * 180 / Math.PI, -rotation[2] * 180 / Math.PI],
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
