# MineLatino Cosmetics — 1.21.4

Primera base **experimental**, no una versión comercial. Fabric y Forge comparten
el código de cliente con mappings oficiales de Mojang. No modifica el launcher
ni el backend de producción y no publica actualizaciones automáticamente.

## Implementado

- Proyectos Fabric (Loader 0.16.14+) y Forge (54.1.0+), Java 21, Minecraft **exactamente 1.21.4**.
- Botón en ESC que abre el armario; cierre y regreso al menú original.
- Configuración interna `config/minelatino-cosmetics/menu.json`, recargada al abrir ESC.
- Hasta tres botones adicionales: `WARDROBE` o `WEBSITE` (HTTPS MineLatino y confirmación).
- Renombrado de botones originales mediante claves de traducción. No elimina navegación esencial.
- Esquema de equipamiento por UUID y pruebas de política compartida.
- El armario informa que está desconectado: **no vende, no equipa ni renderiza cosméticos todavía**.
- API local en [`service/`](service/README.md): catálogo persistente, asignaciones,
  revocaciones, propietarios, auditoría y versiones del menú ESC. Tiene 23 pruebas.
- Autenticación premium obligatoria mediante el `serverId` de Mojang/Microsoft.
  El backend no acepta sesiones offline ni UUID enviados por el cliente como identidad.

## Compilar

Con JDK 21; el wrapper descarga y verifica Gradle 8.12:

```sh
./gradlew :common:test :fabric:build :forge:build
```

Usar el JAR de `fabric/build/libs` o `forge/build/libs` correspondiente, nunca ambos.
No instalar en servidores dedicados. Los directorios `run` de desarrollo son aislados.
En Windows usar `gradlew.bat`. Para iniciar clientes de prueba:
`./gradlew :fabric:runClient` y `./gradlew :forge:runClient`.

## Menú interno

Ejemplo de configuración local (solo presentación, nunca propiedad):

```json
{
  "schemaVersion": 1,
  "enabled": true,
  "buttons": [
    { "label": "Mi armario", "action": "WARDROBE" },
    { "label": "Web MineLatino", "action": "WEBSITE", "url": "https://minelatino.com/" }
  ],
  "labels": { "menu.returnToGame": "Seguir jugando" }
}
```

`enabled: false` conserva el menú original. Configuración inválida recupera los
valores predeterminados sin sobrescribir el archivo del usuario. No admite scripts,
comandos, contraseñas ni acciones descargadas. La edición web llegará mediante una
configuración versionada y validada con esta misma política.

## Próximas entregas

1. Validar el flujo premium en producción con varios clientes y servidores con forwarding moderno.
2. Panel web protegido: catálogo, asignaciones por UUID, propietarios y auditoría.
3. Catálogo y equipamiento dentro del armario; caché, descarga con límites e integridad.
4. Capas y sombreros renderizados, sincronización entre dos clientes, invisibilidad y rendimiento.
5. Publicación web del menú ESC con revisión y rollback.
6. Pagos verificados e idempotentes; instalación desde el launcher después de validar ambos JAR.

No aceptar un nick/UUID declarado por el cliente como prueba premium. No usar el
servicio público actual del launcher para conceder artículos sin autenticación.
En servidores offline, la identidad de las entidades también necesita una
vinculación verificada: un UUID premium de cuenta no garantiza que el servidor
publique ese mismo UUID. No resolverlo concediendo propiedad por nick.

## Referencias de compatibilidad

- https://fabricmc.net/2024/12/02/1214.html
- https://files.minecraftforge.net/net/minecraftforge/forge/index_1.21.4.html
