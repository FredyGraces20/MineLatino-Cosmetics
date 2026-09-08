# Cosmetics alpha.8 / Launcher 0.68.37

## Correcciones revisadas

- UV Java en unidades de 16, independientemente del `texture_size` exportado por Blockbench. Pruebas de regresión en Java, launcher y panel web.
- Miniaturas del launcher y visor del panel usan el mismo cargador por cara que el probador: múltiples PNG, referencias `#alias`, rotaciones de elementos/UV y animación `.mcmeta`.
- Los bitmaps del panel permanecen disponibles hasta liberar sus texturas; ya no se cierran antes de que WebGL los suba.
- El selector de metadatos acepta archivos `.png.mcmeta`, enviándolos como JSON al servicio existente.
- Se conserva el diseño local del panel y la eliminación de revisiones antiguas del menú ESC, con pruebas de autorización y protección de la revisión actual.
- Compilaciones aisladas por proyecto y versión. El task local Fabric no copia Forge a la misma instancia.

## Distribución

- Fabric 1.21.4, Forge 1.21.4 y Fabric 1.21.11: JAR distintos; no se afirma compatibilidad con versiones intermedias ni con Forge 1.21.11.
- Publicar una release nueva `v0.1.0-alpha.8`; conservar las anteriores. `mods.json` debe enumerar solamente archivos ya publicados, con SHA-1 y tamaño verificados.
- Launcher: publicar ASAR, ASAR.gz, SHA-256, EXE y ZIP de 0.68.37 antes de cambiar `RELEASE_TAG_NAME` y `RELEASE_ASSETS_BASE_URL` en Railway.
- El backend del launcher mantiene `ML_AUTO_MODS` en caché al arrancar: reiniciarlo después de actualizar el manifiesto.
- Desplegar únicamente el directorio `service`, sin `.env`, datos locales ni notas. Mantener el volumen `/data` existente.

## Renderer compartido del panel

Desde la raíz del repositorio del launcher:

```powershell
node tools/build-cosmetics-admin.mjs ../minelatino-cosmetics/service/public/cosmetic-preview.js
```

El archivo generado reutiliza `cosmeticGeometry.ts` y `cosmeticMaterials.ts` con Three.js ya incluido en el panel. No contiene modelos ni texturas comerciales.

## Verificación

JAR compilados y metadatos de loader/Minecraft comprobados; pruebas Java y del servicio correctas. Siete pruebas del launcher correctas, incluido el JSON original del usuario. Probador real del launcher validado con los recursos públicos de Ocean y la skin seleccionada. Esto no sustituye una prueba dentro de Minecraft con dos jugadores: esa prueba sigue siendo necesaria para validar visualmente la sincronización multijugador.

El JSON publicado de Ocean ya incluye el anclaje `minelatino_backpack` y el servicio entrega las dos texturas y su animación. No se modificaron compras, cuentas, asignaciones ni recursos publicados del catálogo durante esta entrega.
