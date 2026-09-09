# MineLatino Cosmetics 0.1.0-alpha.16

- Añade caché persistente de modelos, texturas, `.mcmeta` y animaciones de mascotas.
- Revalida recursos mediante `resourceVersion` y ETag; una respuesta 304 evita todas las descargas pesadas.
- Conserva la última copia válida cuando el servicio no está disponible.
- Escribe cada paquete completo antes de activarlo para no reutilizar cachés parciales.
- Mantiene soporte para Fabric 1.21.4, Forge 1.21.4 y Fabric 1.21.11.

El servicio web ahora publica un ETag coherente para el paquete completo de cada cosmético.
