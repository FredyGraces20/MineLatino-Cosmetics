# Validación de 0.1.0-alpha.1

## Automatizado

- JDK 21.0.10, Gradle Wrapper 8.12 con checksum fijado.
- Compilación limpia de Fabric y Forge 1.21.4.
- 10 pruebas JUnit: URLs autorizadas, etiquetas, propiedad consistente e inmutable,
  creación/recarga de configuración, archivo inválido preservado, acción desconocida,
  tamaño máximo, esquema y renombrado de controles.

## Pendiente antes de distribuir a jugadores

La habilidad computer-use no pudo conectar con su servicio de Windows
(`native pipe unavailable`, error 2), incluso después del procedimiento de
recuperación. No se ha validado visualmente ni lanzado el flujo ESC en Minecraft.

Probar por separado Fabric y Forge en sus directorios de desarrollo aislados:

1. Entrar a un mundo de prueba, abrir ESC y pulsar Cosméticos MineLatino.
2. Volver con el botón y con ESC. Comprobar que los controles originales funcionan.
3. Reabrir y redimensionar; no debe duplicar botones ni superponer controles.
4. Probar uno, dos y tres botones a distintas escalas de GUI.
5. Cambiar `menu.returnToGame`, reabrir ESC y comprobar la etiqueta.
6. Probar `enabled: false`, JSON inválido, URL externa y acción desconocida.
7. Un enlace permitido exige confirmación y cancelar vuelve al menú.
8. Cerrar el juego, reabrir y confirmar la configuración persistida.

La alpha no tiene renderizado de accesorios, autenticación, panel web, pagos ni
sincronización entre jugadores. El contrato del siguiente paso está en
`backend-contract.md`; no confundir documentación con endpoints implementados.
