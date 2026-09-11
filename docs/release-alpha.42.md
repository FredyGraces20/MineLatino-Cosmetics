# MineLatino Cosmetics 0.1.0-alpha.42

- Alinea el preview nativo con el flujo del launcher: malla completa, materiales por textura y doble cara.
- Envía los cosméticos por el pipeline principal de modelos de entidad, el mismo que dibuja la skin del jugador.
- Evita depender de la cola secundaria de `ModelPart`, omitida por algunas optimizaciones del framebuffer PIP.
