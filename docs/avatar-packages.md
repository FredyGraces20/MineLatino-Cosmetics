# Personajes animados MineLatino

El mod usa un formato abierto basado en ZIP. No carga ni distribuye archivos `.ysm`.
El ZIP puede envolver los archivos en una carpeta y debe contener:

- `main.json`: geometría Bedrock exportada por Blockbench (`minecraft:geometry`).
- `texture.png`: textura principal. Por compatibilidad, si no existe ese nombre se
  acepta una única imagen PNG dentro del paquete.
- `main.animation.json`, `extra.animation.json` y/o cualquier archivo terminado en
  `.animation.json` (opcionales).

El panel valida el ZIP antes de publicarlo: rutas, cifrado, método de compresión,
tamaño expandido, cantidad de archivos, PNG, geometría, huesos y animaciones. El
cliente repite límites equivalentes antes de guardar el archivo en caché.

## Uso

1. Crea el cosmético en el panel con zona **Skins / Personaje** y estado Borrador.
2. En **Archivos y editor 3D**, selecciona el cosmético y sube el ZIP en
   **Personaje animado**.
3. Publica el cosmético y asígnalo o véndelo como cualquier otro producto.
4. El usuario lo equipa desde la pestaña **Skins** del armario dentro del juego.
5. La tecla `B` abre la ruleta. El botón inferior permite capturar y guardar otra tecla.

El render selecciona automáticamente `idle`, `walk`, `run`, `sneak`, `swim`,
`elytra_fly`, `ride`, `sleep`, `death`, uso de objeto y ataque cuando esos clips
existen. La ruleta prioriza clips `extra0`, `extra1`, etc. También se evalúa un
subconjunto seguro de Molang para interpolación, trigonometría y movimiento de cabeza.

## Límites actuales

El sistema reproduce geometría, textura, jerarquía de huesos y animaciones del
personaje. No ejecuta scripts del paquete, no interpreta código arbitrario y no
implementa funciones exclusivas o cifradas de terceros. Las selecciones de la ruleta
se sincronizan mediante la cuenta MineLatino; los otros jugadores las ven si tienen
el mod y están usando una versión compatible.
