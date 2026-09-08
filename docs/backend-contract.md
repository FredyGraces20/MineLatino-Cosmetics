# Contrato de integración — API local implementada, sin servicio publicado

Este documento fija los límites entre el mod, el panel y el backend. La API local
implementada y sus límites se describen en `../service/README.md`. El panel,
los assets y pagos siguen pendientes. Ningún endpoint está activado en producción.

## Identidad

La identidad canónica es el UUID premium verificado. El nombre es un atributo de
presentación. La verificación de propiedad de Minecraft se hace en el backend;
no se aceptan UUID, claims premium o listas de artículos enviados por el cliente
como prueba. El mecanismo de vinculación usará un desafío de un solo uso,
caducidad y comprobación de sesión de Minecraft; su implementación necesita
pruebas contra los servicios oficiales antes de habilitar equipamiento.

Las sesiones del mod serán de corta duración y ámbito cosméticos. No almacenar
tokens de Microsoft en `menu.json`, logs o URLs. La web administrativa requiere
autenticación independiente y permisos por rol.

## Recursos y permisos

| Recurso | Lectura | Escritura |
|---|---|---|
| Catálogo publicado | Clientes | Administradores |
| Apariencia equipada por UUID | Clientes, por lotes limitados | Solo propietario autenticado |
| Inventario privado | Propietario y administradores | Backend de compras o administrador autorizado |
| Propietarios de un cosmético | Administradores, paginado | No tiene escritura directa |
| Menú publicado | Clientes | Administradores con permiso de configuración |
| Auditoría | Administradores autorizados | Solo backend, append-only |

Rutas bajo `/v1/cosmetics`: `catalog`, `appearance`, `me/wardrobe`, `me/equipment`.
Administración bajo `/v1/admin/cosmetics`: catálogo, grants/revocations y owners.
Menú versionado bajo `/v1/client-config/pause-menu`.

## Datos mínimos

- Cosmetic: id estable, nombre, slot, estado, revisión de archivos.
- Asset: hash SHA-256, tamaño, tipo permitido y ubicación HTTPS de almacenamiento autorizado.
- Entitlement: UUID propietario, cosmeticId, origen, referencia de pago/entrega y revocación.
- Equipment: UUID propietario, slot, cosmeticId y revisión.
- Menu: schemaVersion, revisión, ámbito de servidor, buttons y labels.
- Audit: actor administrativo, acción, destino, fecha y motivo.

No devolver inventario ni historial de compras en la apariencia pública.
Asignar y revocar exige transacción; revocar quita también el equipamiento.
Publicar una revisión gráfica conserva los mismos ids de propiedad.
Los pagos se verifican en el backend y tienen clave única de entrega, nunca
se confía en una pantalla de pago exitoso del cliente.

## Clientes offline y fallos

La configuración puede usar la última revisión válida. El fallo del backend no
impide entrar o salir del juego. No agregar propiedad local al fallar una compra.
La apariencia remota se solicita por lotes con TTL, sin llamadas por frame.
Los modelos son datos limitados, nunca clases, JAR, scripts ni comandos.

## Puerta de publicación

No conectar cobros ni publicar automáticamente en el launcher hasta validar:
suplantación de UUID, expiración y replay de sesión, compra duplicada, revocación,
invisibilidad, clientes Fabric/Forge simultáneos y menú ESC a distintas escalas.
