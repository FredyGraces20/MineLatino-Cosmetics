# Personajes animados MineLatino

> Función retirada del mod desde `0.1.0-alpha.26`. El armario ya no ofrece
> Skins, el cliente ignora equipamientos SKIN antiguos y no descarga ni renderiza
> estos paquetes. La ruleta de animaciones asociada también fue retirada.
> Lo siguiente documenta el formato histórico del panel, no una función vigente del mod.

El mod usa un formato abierto basado en ZIP. Los modelos `.ysm` autorizados se
convierten previamente con la herramienta local; el juego no necesita instalar YSM
ni distribuye el contenedor original.
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
`swim_stand`, `elytra_fly`, `ride`, `sleep`, `riptide`, `attacked`, `death`, uso de
objeto y ataque cuando esos clips existen. La ruleta prioriza clips `extra0`,
`extra1`, etc.

La geometría aplica las mismas conversiones Bedrock que YSM/GeckoLib: jerarquía de
huesos, pivotes, ejes, rotaciones, UV por cara, UV negativos, `uv_rotation`,
`mirror` e `inflate`. Las animaciones admiten keyframes `pre`/`post`, interpolación
lineal, escalonada, Catmull-Rom y easings comunes. El subconjunto seguro de Molang
incluye movimiento de cabeza, velocidad, equipo, trigonometría, exponenciales y
potencias.

## Límites actuales

El sistema reproduce geometría, textura, jerarquía de huesos y animaciones del
personaje. No ejecuta scripts del paquete, no interpreta código arbitrario y no
implementa funciones exclusivas o cifradas de terceros. Las selecciones de la ruleta
se sincronizan mediante la cuenta MineLatino; los otros jugadores las ven si tienen
el mod y están usando una versión compatible.
