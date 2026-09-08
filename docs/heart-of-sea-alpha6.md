# Heart of Sea: texturas múltiples y animadas (alpha.6)

El JSON original utiliza `#2` para `heart_of_the_sea_animation2` y `#3` para `heart_of_the_sea_texture`. No hay que cambiar las rutas originales del JSON ni dividir sus UV por `texture_size`: ya son UV Java (unidad 16).

## Instalación

1. Actualizar el servicio con estos cambios y reconstruir el launcher; sustituir el JAR del mod por alpha.6 (Fabric o Forge 1.21.4). El servicio debe responder a `GET /v1/resources/ID?type=manifest`.
2. En el cosmético, subir `backpack.json` como modelo.
3. En los archivos múltiples, subir `heart_of_the_sea_texture.png` con el nombre **heart_of_the_sea_texture**.
4. Subir `heart_of_the_sea_animation2.png` con el nombre **heart_of_the_sea_animation2** y adjuntarle `heart_of_the_sea_animation2.png.mcmeta` como metadatos de esa textura, no de la principal.
5. Publicar, actualizar el catálogo y probar en el visor y en F5. No es necesario poner las texturas en una ruta de Nexo del PC de cada jugador: los clientes las solicitan al servicio por nombre.

La tira se muestra fotograma a fotograma. El frametime 1.8 del archivo se interpreta como 1.8 ticks (90 ms). Esta tolerancia es propia del renderer de cosméticos; no promete compatibilidad de ese decimal con el cargador vanilla de resourcepacks. Se admiten secuencias `frames`, tiempos por fotograma y hojas en cuadrícula; `interpolate:true` se rechaza expresamente por ahora.

Se conservan las UV y las dos caras de los elementos planos. Un material ausente en un modelo con varias texturas produce un error, no una sustitución silenciosa por otro PNG.

También se corrigió la eliminación accidental del PNG recién subido al reemplazar el archivo llamado `texture`, y el catálogo cambia de versión cuando cambia cualquiera de sus texturas/modelo/metadatos.

Las transformaciones de colocación son independientes de las texturas: este recurso incluye ajustes `display.head` de Nexo. El slot BACKPACK usa el anclaje de espalda del mod, no el sistema de equipamiento de Nexo; debe verificarse su posición en juego antes de dar por idéntico el resultado.
