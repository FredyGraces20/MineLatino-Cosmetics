# Heart of Sea: texturas múltiples y animadas (alpha.6)

El JSON original utiliza `#2` para `heart_of_the_sea_animation2` y `#3` para `heart_of_the_sea_texture`. No hay que cambiar las rutas originales del JSON ni dividir sus UV por `texture_size`: ya son UV Java (unidad 16).

## Instalación

1. Actualizar el servicio con estos cambios y reconstruir el launcher; sustituir el JAR del mod por alpha.6 (Fabric o Forge 1.21.4). El servicio debe responder a `GET /v1/resources/ID?type=manifest`.
2. En el cosmético de categoría BACKPACK, subir `examples/heart-of-sea/backpack-minelatino.json` como modelo. Es una copia del original con `display.minelatino_backpack` para adaptar la posición de Nexo al anclaje de espalda; conserva las UV, caras y referencias de textura.
3. En los archivos múltiples, subir `heart_of_the_sea_texture.png` con el nombre **heart_of_the_sea_texture**.
4. Subir `heart_of_the_sea_animation2.png` con el nombre **heart_of_the_sea_animation2** y adjuntarle `heart_of_the_sea_animation2.png.mcmeta` como metadatos de esa textura, no de la principal.
5. Publicar, actualizar el catálogo y probar en el visor y en F5. No es necesario poner las texturas en una ruta de Nexo del PC de cada jugador: los clientes las solicitan al servicio por nombre.

La tira se muestra fotograma a fotograma. El frametime 1.8 del archivo se interpreta como 1.8 ticks (90 ms). Esta tolerancia es propia del renderer de cosméticos; no promete compatibilidad de ese decimal con el cargador vanilla de resourcepacks. Se admiten secuencias `frames`, tiempos por fotograma y hojas en cuadrícula; `interpolate:true` se rechaza expresamente por ahora.

Se conservan las UV y las dos caras de los elementos planos. Un material ausente en un modelo con varias texturas produce un error, no una sustitución silenciosa por otro PNG.

También se corrigió la eliminación accidental del PNG recién subido al reemplazar el archivo llamado `texture`, y el catálogo cambia de versión cuando cambia cualquiera de sus texturas/modelo/metadatos.

Las transformaciones de colocación son independientes de las texturas: el JSON original incluye ajustes `display.head` de Nexo. La copia adaptada añade un ajuste de espalda explícito; los demás modelos conservan su comportamiento anterior. Debe verificarse también en juego: la validación del launcher no sustituye la prueba en Minecraft.

## Verificación realizada

- 48 pruebas Java sin fallos, incluida la lectura del modelo suministrado y de la copia adaptada; Fabric y Forge alpha.6 generados.
- 39 pruebas del servicio sin fallos; incluye reemplazar dos veces la textura principal y comprobar que sigue disponible.
- 6 pruebas del renderer sin fallos (incluido el modelo original), comprobación de tipos y build del renderer correctos.
- Launcher real en desarrollo: catálogo local temporal, skin seleccionada, vista de espalda y texturas separadas. Se detectó la colocación a los pies del JSON original y se verificó visualmente la copia adaptada sobre la espalda. Se restauró la conexión normal al acabar.
- El intento de redimensionar por DevTools a 1200×720 no está soportado por este Electron; la captura se comprobó al tamaño real disponible (1623×863). No se ha validado aún dentro de Minecraft ni se ha publicado el servicio/launcher en producción.

JAR: `fabric/build/heart-of-sea-alpha6/libs/` y `forge/build/heart-of-sea-alpha6/libs/`. Se utilizó `-I tools/isolated-test-output.gradle` para evitar resultados/clases antiguos bloqueados por OneDrive sin eliminarlos.
