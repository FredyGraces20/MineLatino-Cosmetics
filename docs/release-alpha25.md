# MineLatino Cosmetics 0.1.0-alpha.25

- Sustituye el render crudo de cubos por un horneado compatible con Bedrock,
  GeckoLib y los paquetes convertidos desde YSM.
- Corrige lateralidad, pivotes, orientación individual de piezas y rotaciones de
  huesos/cubos (`-X`, `+Y`, `+Z`; rotaciones `-X`, `-Y`, `+Z`).
- Corrige el atlas box-UV, UV por cara, tamaños UV negativos y normales.
- Añade soporte para `mirror`, `inflate` y `uv_rotation`.
- Añade keyframes `pre`/`post`, Catmull-Rom, easings y semántica play-once/loop.
- Expone a Molang velocidad de movimiento, manos y casco; añade `math.exp`,
  `math.pow` y `math.random` seguro.
- Mejora la selección de animaciones para daño, agua quieta, sueño, riptide,
  mano activa, vuelo, montura y movimiento.
