// Generated from MineLatino Launcher cosmeticGeometry.ts and cosmeticMaterials.ts. Do not edit by hand.
var MineLatinoCosmetics = (() => {
  var __defProp = Object.defineProperty;
  var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
  var __getOwnPropNames = Object.getOwnPropertyNames;
  var __hasOwnProp = Object.prototype.hasOwnProperty;
  var __export = (target, all) => {
    for (var name in all)
      __defProp(target, name, { get: all[name], enumerable: true });
  };
  var __copyProps = (to, from, except, desc) => {
    if (from && typeof from === "object" || typeof from === "function") {
      for (let key of __getOwnPropNames(from))
        if (!__hasOwnProp.call(to, key) && key !== except)
          __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
    }
    return to;
  };
  var __toCommonJS = (mod) => __copyProps(__defProp({}, "__esModule", { value: true }), mod);

  // <stdin>
  var stdin_exports = {};
  __export(stdin_exports, {
    animationFrames: () => animationFrames,
    cosmeticGeometry: () => cosmeticGeometry,
    createCosmeticMesh: () => createCosmeticMesh,
    disposeCosmeticMesh: () => disposeCosmeticMesh,
    textureName: () => textureName
  });

  // admin:three
  var { BufferGeometry, Float32BufferAttribute, Vector3, Mesh, MeshStandardMaterial, NearestFilter, SRGBColorSpace, Texture } = globalThis.THREE;

  // xmcl-keystone-ui/src/util/cosmeticGeometry.ts
  function textureName(model, reference = "") {
    const visited = /* @__PURE__ */ new Set();
    while (reference.startsWith("#")) {
      if (visited.has(reference) || typeof model.textures?.[reference.slice(1)] !== "string") throw new Error(`Textura no resuelta: ${reference}`);
      visited.add(reference);
      reference = model.textures[reference.slice(1)];
    }
    const name = reference.slice(reference.lastIndexOf("/") + 1).replace(/\.png$/, "");
    if (name && !/^[a-z0-9_]{1,32}$/.test(name)) throw new Error("Nombre de textura no admitido");
    return name;
  }
  var vector = (v, n) => Array.isArray(v) && v.length === n && v.every((x) => typeof x === "number" && Number.isFinite(x) && Math.abs(x) <= 65536);
  function cosmeticGeometry(model) {
    if (!Array.isArray(model?.elements) || !model.elements.length || model.elements.length > 4096) throw new Error("Se requiere un modelo Minecraft Java con 1\u20134096 elementos");
    const positions = [], uv = [];
    const names = [], groups = [];
    for (const e of model.elements) {
      if (!vector(e.from, 3) || !vector(e.to, 3) || !e.faces) throw new Error("Elemento inv\xE1lido");
      const [x, y, z] = e.from, [X, Y, Z] = e.to;
      const faces = {
        north: [[X, Y, z], [X, y, z], [x, y, z], [x, Y, z]],
        south: [[x, Y, Z], [x, y, Z], [X, y, Z], [X, Y, Z]],
        east: [[X, Y, Z], [X, y, Z], [X, y, z], [X, Y, z]],
        west: [[x, Y, z], [x, y, z], [x, y, Z], [x, Y, Z]],
        up: [[x, Y, z], [x, Y, Z], [X, Y, Z], [X, Y, z]],
        down: [[x, y, Z], [x, y, z], [X, y, z], [X, y, Z]]
      };
      const defaults = {
        north: [16 - X, 16 - Y, 16 - x, 16 - y],
        south: [x, 16 - Y, X, 16 - y],
        east: [16 - Z, 16 - Y, 16 - z, 16 - y],
        west: [z, 16 - Y, Z, 16 - y],
        up: [x, z, X, Z],
        down: [x, 16 - Z, X, 16 - z]
      };
      for (const [direction, corners] of Object.entries(faces)) {
        const face = e.faces[direction];
        if (!face || face.texture === null) continue;
        const name = textureName(model, face.texture);
        if (!names.includes(name)) names.push(name);
        groups.push({ start: positions.length / 3, count: 6, materialIndex: names.indexOf(name) });
        const rect = face.uv ?? defaults[direction], turn = face.rotation ?? 0;
        if (!vector(rect, 4) || ![0, 90, 180, 270].includes(turn)) throw new Error("UV inv\xE1lidas");
        const vertices = corners.map((c) => {
          const p = new Vector3(c[0], c[1], c[2]), r = e.rotation;
          if (r) {
            if (!["x", "y", "z"].includes(r.axis) || !vector(r.origin, 3) || !Number.isFinite(r.angle)) throw new Error("Rotaci\xF3n inv\xE1lida");
            const origin = new Vector3(...r.origin), angle = r.angle * Math.PI / 180;
            p.sub(origin).applyAxisAngle(new Vector3(r.axis === "x" ? 1 : 0, r.axis === "y" ? 1 : 0, r.axis === "z" ? 1 : 0), angle);
            const factor = r.rescale ? 1 / Math.abs(Math.cos(angle)) : 1;
            if (!Number.isFinite(factor) || factor > 100) throw new Error("Escala inv\xE1lida");
            for (const axis of ["x", "y", "z"]) if (axis !== r.axis) p[axis] *= factor;
            p.add(origin);
          }
          return p.subScalar(8);
        });
        for (const i of [0, 1, 2, 0, 2, 3]) {
          positions.push(...vertices[i].toArray());
          const index = (i + turn / 90) % 4;
          uv.push(rect[index < 2 ? 0 : 2] / 16, 1 - rect[index === 0 || index === 3 ? 1 : 3] / 16);
        }
      }
    }
    const head = model.display?.head;
    for (const v of [head?.translation, head?.rotation, head?.scale]) if (v !== void 0 && !vector(v, 3)) throw new Error("Transformaci\xF3n head inv\xE1lida");
    const backpack = model.display?.minelatino_backpack;
    for (const v of [backpack?.translation, backpack?.rotation, backpack?.scale]) if (v !== void 0 && !vector(v, 3)) throw new Error("Transformaci\xF3n backpack inv\xE1lida");
    if (!positions.length) throw new Error("Modelo sin caras visibles");
    const geometry = new BufferGeometry();
    for (const group of groups) geometry.addGroup(group.start, group.count, group.materialIndex);
    geometry.userData.textureNames = names;
    geometry.setAttribute("position", new Float32BufferAttribute(positions, 3));
    geometry.setAttribute("uv", new Float32BufferAttribute(uv, 2));
    geometry.computeVertexNormals();
    return geometry;
  }

  // admin:resources
  function resourceUrl(product) {
    return `/v1/resources/${encodeURIComponent(product.id)}?v=${encodeURIComponent(product.resourceVersion || "")}`;
  }

  // xmcl-keystone-ui/src/util/cosmeticMaterials.ts
  function animationFrames(meta, width, height) {
    const a = meta?.animation;
    if (!a) return { columns: 1, rows: 1, frame: (_ticks) => 0 };
    const fw = a.width ?? (a.height ? width : Math.min(width, height)), fh = a.height ?? (a.width ? height : fw);
    if (!Number.isInteger(fw) || !Number.isInteger(fh) || fw <= 0 || fh <= 0 || width % fw || height % fh) throw new Error("Dimensiones de animaci\xF3n inv\xE1lidas");
    const columns = width / fw, rows = height / fh, count = columns * rows, time = a.frametime ?? 1;
    if (count > 4096 || !Number.isFinite(time) || time <= 0 || a.interpolate) throw new Error("Animaci\xF3n no admitida: comprueba frametime e interpolate");
    const source = a.frames?.length ? a.frames : Array.from({ length: count }, (_, i) => i);
    if (source.length > 4096) throw new Error("Demasiados fotogramas");
    const frames = source.map((f) => typeof f === "number" ? { index: f, time } : { index: f.index, time: f.time ?? time });
    if (frames.some((f) => !Number.isInteger(f.index) || f.index < 0 || f.index >= count || !Number.isFinite(f.time) || f.time <= 0)) throw new Error("Fotograma inv\xE1lido");
    const total = frames.reduce((sum, f) => sum + f.time, 0);
    return { columns, rows, frame(ticks) {
      let t = (ticks % total + total) % total;
      for (const f of frames) {
        if (t < f.time) return f.index;
        t -= f.time;
      }
      return frames[0].index;
    } };
  }
  function disposeCosmeticMesh(mesh) {
    mesh.geometry.dispose();
    for (const material of Array.isArray(mesh.material) ? mesh.material : [mesh.material]) {
      if (material instanceof MeshStandardMaterial) material.map?.dispose();
      material.dispose();
    }
  }
  async function createCosmeticMesh(product, model, signal) {
    const geometry = cosmeticGeometry(model), names = geometry.userData.textureNames;
    const materials = [], updates = [];
    try {
      if (names.length > 32) throw new Error("Demasiadas texturas");
      const base = resourceUrl(product);
      const response = await fetch(`${base}&type=manifest`, { signal, credentials: "omit" });
      let files = [];
      try {
        files = (await response.json()).files;
        if (!Array.isArray(files)) throw new Error();
      } catch {
        if (names.length > 1) throw new Error("Actualiza el servicio: falta el manifiesto de texturas");
        files = [];
      }
      for (const name of names) {
        const file = files.find((f) => f.name === name);
        if (!file && names.length > 1) throw new Error(`Sube la textura con el nombre ${name}`);
        const url = file ? `${base}&file=${encodeURIComponent(name)}` : base;
        const png = await fetch(url, { signal, credentials: "omit" });
        if (!png.ok) throw new Error(`No se pudo descargar ${name} (${png.status})`);
        const blob = await png.blob();
        if (blob.size > 2 * 1024 * 1024) throw new Error("Textura demasiado grande");
        let meta = null;
        if (file?.hasMcmeta) {
          const response2 = await fetch(`${url}&type=mcmeta`, { signal, credentials: "omit" });
          if (!response2.ok) throw new Error(`No se pudo descargar la animaci\xF3n de ${name}`);
          meta = await response2.json();
        }
        const bitmap = await createImageBitmap(blob, { imageOrientation: "flipY" });
        let animation;
        try {
          if (bitmap.width > 4096 || bitmap.height > 4096 || signal.aborted) throw new Error("Carga cancelada o textura demasiado grande");
          animation = animationFrames(meta, bitmap.width, bitmap.height);
        } catch (e) {
          bitmap.close();
          throw e;
        }
        const texture = new Texture(bitmap);
        texture.minFilter = NearestFilter;
        texture.magFilter = NearestFilter;
        texture.colorSpace = SRGBColorSpace;
        texture.generateMipmaps = false;
        texture.needsUpdate = true;
        texture.addEventListener("dispose", () => bitmap.close());
        const material = new MeshStandardMaterial({ map: texture, alphaTest: 0.1, roughness: 1 });
        materials.push(material);
        updates.push(() => {
          const frame = animation.frame(performance.now() / 50);
          texture.repeat.set(1 / animation.columns, 1 / animation.rows);
          texture.offset.set(frame % animation.columns / animation.columns, 1 - (Math.floor(frame / animation.columns) + 1) / animation.rows);
        });
      }
      const mesh = new Mesh(geometry, materials);
      mesh.onBeforeRender = () => updates.forEach((update) => update());
      return mesh;
    } catch (e) {
      geometry.dispose();
      for (const m of materials) {
        m.map?.dispose();
        m.dispose();
      }
      throw e;
    }
  }
  return __toCommonJS(stdin_exports);
})();
