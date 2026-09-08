# Cosméticos en el launcher

## Implementado

- Ruta `/minelatino/cosmeticos`, junto a Jugar, Anuncios, Actualizaciones y Tienda.
- Catálogo publicado paginado, búsqueda y categorías. Las tarjetas muestran la textura real; al abrirlas se usa un visor 3D con la skin de la cuenta seleccionada. Solo un visor activo para limitar consumo de GPU.
- Vista Java JSON de una textura (mismo formato que el mod), UVs, rotaciones y `display.head`. Capas/alas sin JSON usan el visor de capa/elytra. No se admite directamente `.bbmodel`, modelos animados o múltiples texturas.
- Nombre, descripción, precio y moneda editables en el panel administrativo. Precio vacío significa no disponible; no equivale a gratuito. Las monedas admitidas utilizan dos decimales.
- El esquema de productos es una tabla adicional: no borra cosméticos, propietarios ni equipamiento existentes. Clientes antiguos conservan la descripción/precio cuando no envían `product`.
- `commerce.mjs`: base de pedidos persistentes, idempotencia, importe/moneda definidos por el servidor y entrega transaccional a `entitlements`, que ya consulta el mod. No se expone una ruta pública para marcar pagos aprobados.

## Despliegue

Actualizar el servicio y compilar el renderer del launcher. El cliente utiliza `VITE_MINELATINO_COSMETICS_API` al compilar; por defecto apunta al servicio Railway existente. No poner secretos en variables `VITE_*`. No se ha desplegado automáticamente esta actualización.

Rutas públicas nuevas: `GET /v1/storefront/catalog?offset=0` devuelve `{items,nextOffset}`; `GET /v1/storefront/payments` anuncia todos los proveedores desactivados. Solo catálogo, configuración de pagos y recursos publicados admiten lectura CORS; no se relaja el acceso administrativo ni de autenticación.

## Pendiente antes de aceptar dinero

La interfaz de compra es preparatoria: muestra los tres proveedores desactivados y no crea pedidos ni cargos. `POST /v1/storefront/checkout` rechaza solicitudes mientras no esté conectado el flujo. Añadir credenciales por sí solo NO activa pagos.

1. Vinculación premium desde el launcher o página de checkout segura. Completar el desafío del servicio con la sesión Minecraft desde el proceso principal; nunca pasar el token Minecraft al renderer o en una URL. Distinguir sesiones premium de las sesiones offline de desarrollo (el servicio actual no guarda esa distinción). No aceptar `premiumVerified` ni UUID/nick declarados por el navegador como prueba de identidad.
2. Crear adaptadores de PayPal, Binance Pay y Mercado Pago en el servidor: `createCheckout(order)` y `verifyNotification(rawBody, headers)`. Seguir la documentación vigente de cada proveedor. Claves, firmas, identificadores de comerciante y URLs de retorno permitidas solo en servidor.
3. La ruta autenticada debe llamar `Commerce.createOrder(identity, cosmeticId, provider, idempotencyKey)` solo después de obtener `identity` desde la verificación premium. El cliente envía producto/proveedor/clave, nunca precio ni propietario. Guardar y reutilizar la sesión externa con la misma clave ante reintentos.
4. Un webhook verificado debe consultar el pago al proveedor, comprobar comerciante, estado final, ID de pedido, moneda e importe y solo entonces llamar a `settleVerified`. Ese método es interno, no valida firmas por sí mismo. No conceder nada por una URL de retorno ni por un botón del cliente.
5. Exponer consulta autenticada del estado de compra, recuperación tras reinicios y expiración/cancelación de pedidos pendientes. Añadir reconciliación, reembolsos/contracargos y sus reglas de revocación antes de producción. Un pedido pendiente bloquea otra compra del mismo artículo para evitar doble cobro.
6. Activar proveedores individualmente en configuración e integrar ese estado en el launcher (ahora muestra explícitamente el modo preparatorio). Probar primero con los entornos de prueba oficiales y pedidos sin dinero real.

La propiedad se guarda por UUID premium; el nick es una etiqueta visible que puede cambiar. Los demás jugadores necesitan el mod y una identidad UUID coherente con la que publique el servidor. La compra no resuelve automáticamente discrepancias de UUID en servidores offline.

## Verificación

Servicio: `node --test service/test/*.test.mjs` desde el proyecto del mod.
Launcher: `pnpm check`, `pnpm lint`, `pnpm exec vitest run xmcl-keystone-ui/src/util/cosmeticGeometry.test.ts`, `pnpm build:renderer`.
Validación visual: skin propia, frente/espalda, JSON con `display.head`, capa PNG, recursos ausentes, cambio de cuenta, cerrar modal durante carga, desconexión, categorías vacías; ventanas 1200×720 y 800×400.
