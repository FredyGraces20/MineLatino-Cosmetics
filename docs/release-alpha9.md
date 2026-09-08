# MineLatino Cosmetics alpha.9

- Los modelos de espalda (`BACKPACK`, `CAPE`, `WINGS`) se giran 180 grados en ambos renderizadores para mostrar su frente hacia fuera y no ocultarlo contra el jugador.
- `HAT` y `PET` conservan su orientación anterior.
- La adaptación Heart of Sea recupera su escala completa `[2,2,2]` y la posición validada para mochila; el intento `[0.8,0.8,0.8]` hacía el modelo demasiado pequeño.
- La transformación pública de Ocean se actualizó de escala 1 a escala 2, conservando su posición y rotación, para que los clientes reciban el tamaño correcto.
- Incluye pruebas compartidas de orientación por slot y una regresión de escala del display de mochila.

Artefactos: Fabric 1.21.4, Forge 1.21.4 y Fabric 1.21.11. Cada loader debe usar exclusivamente su JAR correspondiente.
