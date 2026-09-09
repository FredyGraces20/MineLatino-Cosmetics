# Contrato de modelos Blockbench

MineLatino acepta modelos exportados por Blockbench como **Minecraft Java Block/Item**. El launcher, el editor web y el mod aplican las mismas reglas:

- `elements` debe contener entre 1 y 4096 cubos Java con `from`, `to` y `faces`.
- Las coordenadas de cada `face.uv` usan siempre la cuadrícula virtual Java de **16 x 16**, incluso cuando la textura PNG mide 64, 128 o 256 píxeles.
- `texture_size` describe la resolución del lienzo en Blockbench. No multiplica ni divide las UV del modelo.
- `face.rotation` admite 0, 90, 180 o 270 grados.
- `element.rotation` admite los ejes `x`, `y` o `z`; el origen, ángulo y `rescale` se conservan.
- Las referencias `#nombre` se resuelven desde `textures`. Cada textura nombrada debe publicarse junto con el cosmético.
- Para texturas animadas, el PNG vertical y su archivo `.png.mcmeta` deben compartir el mismo nombre.

Por ejemplo, con una textura de 128 x 128, `uv: [0, 0, 16, 16]` cubre la textura completa. No debe convertirse a `[0, 0, 128, 128]`.

Los valores UV fuera de `0..16` se conservan porque Minecraft permite repetición y algunos exportadores generan caras degeneradas. Conviene corregirlos en Blockbench si aparecen en una cara visible.
