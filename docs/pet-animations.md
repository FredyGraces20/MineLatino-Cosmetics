# Animaciones de mascotas

El panel acepta archivos `.animation.json` exportados por Blockbench/GeckoLib para cosméticos del slot `PET`.

## Flujo

1. Crea el modelo visual como JSON Java y súbelo como hasta ahora.
2. En Blockbench crea un hueso raíz llamado `root`, `body` o `pet`.
3. Anima en ese hueso `position`, `rotation` o `scale` con valores numéricos.
4. Exporta las animaciones como `*.animation.json`.
5. En **Archivos y editor 3D → Animación de mascota**, sube el archivo y elige el clip activo.

El backend guarda el clip elegido. Los clientes actualizan el recurso periódicamente, por lo que cambiar de clip no requiere recompilar el mod.

## Formato admitido

- Objeto superior `animations` compatible con GeckoLib.
- Hasta 128 clips y 4096 keyframes por canal.
- Loop, duración, interpolación lineal, posición, rotación y escala.
- Keyframes directos o con valores `pre`/`post`.
- Se usa `root`, luego `body`, luego `pet`; si ninguno existe, se usa el primer hueso.

En esta primera versión se anima el modelo completo desde el hueso raíz. Las expresiones Molang y la animación independiente de varios huesos se rechazan para evitar resultados silenciosamente incorrectos.
