# Correcciones de renderizado — 1.21.4 alpha.2

## Cambios

- Caras orientadas hacia afuera según el orden de vértices de Minecraft Java; UV con rotaciones de 0/90/180/270 grados y regiones invertidas.
- Normales calculadas después de rotar y reescalar cada elemento. Las caras sin superficie se descartan; los elementos planos conservan sus caras visibles.
- Geometría preparada al descargar, no reconstruida en cada fotograma.
- Sombreros: transformación vanilla de objetos equipados en la cabeza y `display.head` de Blockbench (traslación, rotación y escala). El modelo sigue los movimientos de cabeza.
- Overlay neutro correcto; cosméticos ocultos en jugadores invisibles/espectadores.
- Consulta pública de apariencias sin sesión del armario. Equipar y administrar siguen usando sus flujos de autenticación existentes.
- Recursos revalidados cada 60 segundos mientras están en uso, con claves nuevas para evitar la caché inmutable del servidor. Fallos: espera de 15 segundos antes de reintentar y conservación del último recurso válido. Un JSON inválido o HTTP 500 no se interpreta como modelo ausente; HTTP 404 del modelo permite un cosmético PNG plano.
- La apariencia anterior se conserva durante la actualización, como máximo 120 segundos desde la última lectura correcta, para evitar desapariciones entre consultas.
- La caché activa es de memoria; no se reutilizan ni borran los archivos de caché antiguos de versiones anteriores.

## Comprobaciones

Compilación Fabric y Forge 1.21.4 y pruebas de `common`; pruebas del servicio Node. Lectura del modelo público `pruebas` con el parser nuevo: 77 elementos, 210 caras con superficie y ninguna normal no finita. Las otras 252 caras declaradas son degeneradas, sin superficie renderizable.

No se ha validado visualmente esta versión con dos clientes conectados al servidor. No se publicaron cambios ni se modificaron compras, propietarios o recursos de producción.

## Prueba dentro del juego

1. Cerrar Minecraft y sustituir el JAR anterior por alpha.2 en cada instancia. Instalar **solo** Fabric o Forge, según el cargador; Minecraft 1.21.4 con Java 21.
2. Equipar `pruebas` en la cuenta que ya lo posee y comprobarlo en tercera persona. Mirar las seis direcciones, agacharse y girar la cabeza.
3. Conectar un segundo jugador con el mod, sin iniciar sesión en el armario de cosméticos. Debe ver el cosmético al actualizarse la apariencia (normalmente hasta 30 segundos para un jugador nuevo).
4. Cambiar el cosmético equipado y comprobar que se refleja en el otro cliente en hasta aproximadamente 90 segundos. Cambiar el PNG o JSON desde el panel y comprobar su actualización en aproximadamente 60 segundos más la descarga.
5. Ante errores, revisar `logs/latest.log`: buscar `MineLatino Cosmetics`, `Resource download failed` y `Failed to add cosmetic layer`.

## Límites que siguen vigentes

- Formato: elementos/cubos de Minecraft Java o un proyecto `.bbmodel` importado desde el panel para mascotas. El importador resuelve jerarquías, rotaciones, UVs y varias texturas PNG incrustadas; también convierte los clips numéricos al archivo de animación de mascota. El runtime actual reproduce el canal raíz (`root`, `body` o `pet`) sobre el modelo completo, no deformación independiente de cada hueso.
- `display.head` corresponde al slot HAT. CAPE/WINGS mantienen anclaje al cuerpo; requieren modelos diseñados para ese anclaje y ajuste visual.
- Un cosmético sin PNG subido no se puede dibujar. En el diagnóstico previo, `pruebascv` no tenía recurso: debe subirse desde el panel.
- El UUID entregado por el servidor de Minecraft debe coincidir con el UUID propietario en la API. En un servidor offline que cambie UUID, hace falta resolver la identidad de forma verificada; no se asignan cosméticos por coincidencia insegura de nick.
