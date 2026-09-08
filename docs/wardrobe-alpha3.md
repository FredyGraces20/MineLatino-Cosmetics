# Armario 3D — Minecraft 1.21.4 alpha.3

## Uso

Abrir ESC → Cosméticos MineLatino. El menú usa carbón, ámbar, paneles redondeados y botones con foco/teclado siguiendo la paleta del launcher.

1. Vincular la cuenta para cargar los cosméticos que posee. No se conceden cosméticos por abrir el menú.
2. Buscar o filtrar por Cabeza, Capa, Alas, Mochila o Mascota. Las flechas y la rueda en la colección permiten recorrer todas las páginas.
3. Seleccionar una tarjeta muestra una **prueba sin guardar** sobre la skin del jugador. Arrastrar gira la vista; la rueda hace zoom. Frente/Espalda restablecen el zoom y cambian la orientación.
4. Pulsar **Equipar** para guardar. El estado solo cambia a Equipado cuando la API devuelve el cosmético solicitado. Pulsar **Quitar cosmético** para desequiparlo.
5. Actualizar recarga el armario y reintenta los recursos del cosmético seleccionado. El pie muestra errores completos al pasar el cursor.

## Correcciones funcionales

- El controlador pertenece a CosmeticsClient, no a Screen: cerrar o redimensionar no pierde la respuesta de equipamiento.
- Una sola operación de escritura a la vez; los botones se desactivan durante el guardado.
- Al cargar el armario también se publican los cosméticos ya equipados en la caché del mundo.
- Respuestas públicas atrasadas no sobrescriben un equipamiento más reciente.
- Se comprueba que el UUID del armario sea el de la sesión y se normalizan guiones/mayúsculas.
- Una respuesta HTTP 200 que no confirme el cosmético solicitado se trata como error, no como éxito.
- El visor usa un RemotePlayer separado con la skin real del jugador y el mismo CosmeticRenderer que el mundo. La selección temporal solo existe durante el render del visor y no altera la caché pública.
- Los errores de PNG/modelo, sesión caducada, propiedad/categoría, ausencia del renderizador y UUID de servidor diferente se muestran en el menú.

## Zonas y formato

| ID de API | Zona | Comportamiento |
| --- | --- | --- |
| HAT | Cabeza | Sigue la cabeza y usa `display.head` del JSON de Minecraft Java. |
| CAPE | Capa | Anclaje a espalda; fallback PNG plano por fuera del torso. |
| WINGS | Alas | Anclaje a espalda. |
| BACKPACK | Mochila | Anclaje al torso, detrás del jugador. |
| PET | Mascota | Acompañante visual a un lado, con oscilación suave. No es una entidad con IA ni una mascota del servidor. |

Se admite un cosmético por categoría. Los modelos de mochila, alas y mascota deben exportarse alrededor del centro `[8,8,8]` en unidades de Minecraft (16 por bloque); la mascota se escala a 0.55. La colocación artística final necesita una prueba visual con cada modelo. Continúa el soporte de un PNG y geometría `elements` de Minecraft Java; no se implementan animaciones GeckoLib, archivos `.bbmodel` ni múltiples texturas independientes.

## Actualización del servicio

Los tres slots anteriores funcionan con la API existente. Para crear/equipar Mochila y Mascota hay que desplegar el servicio y panel actualizados: el código preparado aquí **no se ha desplegado**.

La migración SQLite v5 amplía la restricción de categorías conservando IDs, revisiones, propietarios, recursos y equipamiento. Se ejecuta transaccionalmente, comprueba claves foráneas y es idempotente. Antes del despliegue debe hacerse una copia de seguridad consistente de SQLite y de los recursos. No se cambia de categoría un ID existente: crear un ID nuevo con la categoría correcta.

## Verificación

Pruebas automatizadas: guardado sin pantalla/tick, doble clic, reapertura durante una escritura, errores/reintentos, quitar, UUID incorrecto, respuestas tardías, contrato HTTP, nuevas categorías, migración y geometría.

Pendiente: verificación visual del visor y equipamiento con dos clientes reales, uno Fabric y otro Forge. El control de escritorio `computer-use` falló con `Computer Use native pipe is unavailable`, por lo que no se ha inspeccionado el menú en pantalla.

Prueba manual recomendada: instalar únicamente el JAR alpha.3 del cargador correspondiente en ambas instancias 1.21.4; equipar un cosmético propio con PNG/JSON válidos, cerrar inmediatamente el armario y comprobar el resultado en tercera persona y desde el otro cliente. Reabrir, girar y hacer zoom, cambiar de categoría, quitar y revisar errores de descarga. Probar también ventana pequeña/escala GUI alta y skins clásica/slim.

Si aparece el aviso de UUID diferente, el menú no puede solucionar por sí solo la identidad online/offline del servidor. La identidad de la API y del jugador del mundo deben coincidir, o hay que implementar una vinculación verificada en el servidor. No se publica una identidad ajena basándose solo en el nick.
