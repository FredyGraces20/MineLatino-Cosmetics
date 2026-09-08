# Registro de diagnóstico (alpha.4)

El mod registra eventos con la marca `[ML-DIAG]` en `logs/latest.log` de la instancia de Minecraft. El botón **Registro**, arriba del visor del armario, guarda un informe `logs/minelatino-diagnostico-<número>.txt`. La ruta se muestra al terminar; pasar el cursor por el pie permite verla completa.

Para investigar un problema: vincular la cuenta, equipar el cosmético, cerrar el menú, mirar al personaje con F5, volver al armario y pulsar Registro. Compartir el TXT generado. El informe no se envía automáticamente.

El informe incluye UUID de cuenta, UUID del servidor y UUID de sesión, estado/validez de la sesión, equipo confirmado, las dos cachés, estado del mundo y hasta 400 eventos recientes. No incluye tokens, contraseñas, credenciales de Minecraft, cabeceras, cuerpos HTTP ni mensajes de excepciones. Los eventos del renderer se anotan al cambiar de estado, no en cada fotograma.

| Evento | Qué permite comprobar |
| --- | --- |
| AUTH_MODE / AUTH_CONNECTED / AUTH_FAILED | Verificación premium o autenticación de desarrollo; éxito/fallo de vinculación. |
| HTTP / HTTP_FAILED | Ruta sin parámetros, código HTTP, duración y clase del error. |
| EQUIP_REQUEST / EQUIPMENT_CONFIRMED | Solicitud enviada y equipo realmente confirmado por la API. |
| WARDROBE_FAILED | Fase del armario y tipo de error. |
| IDENTITY | Diferencias entre cuenta, jugador del servidor y sesión. |
| APPEARANCE_REFRESH / APPEARANCE_FAILED | Consulta de apariencias y errores. |
| CACHE_STALE_RESPONSE | Una consulta antigua se descartó para no deshacer un cambio reciente. |
| RESOURCE_PNG / RESOURCE_MODEL / RESOURCE_READY / RESOURCE_FAILED | HTTP de las descargas, número de elementos/caras y fallos de recursos. |
| PREVIEW_RENDER | La capa se ejecutó en el visor. |
| WORLD_RENDER | Jugador local omitido por invisibilidad/espectador, identidad ausente, caché vacía, recursos pendientes o geometría enviada. |
| TICK_FAILED | Excepciones del tick que antes se ignoraban. |

`submitted` confirma que se ejecutó la llamada de dibujo, no que un humano vio el cosmético. La ausencia de WORLD_RENDER puede indicar que no se miró al jugador en tercera persona, que no se renderizó o que falta la capa. Las texturas de recursos y UUID se analizan con los demás eventos.

Si `identityMismatch=true`, la apariencia pública de otros jugadores sigue necesitando una identidad consistente/verificada; ver el cosmético localmente no demuestra sincronización entre clientes. El registro tampoco considera la autenticación offline una verificación de Mojang.

Este registro corresponde al código alpha.4 y requiere instalar su JAR. Los JAR alpha.3 anteriores no tienen el botón Registro.
