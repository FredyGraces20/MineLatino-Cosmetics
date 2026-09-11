# MineLatino Cosmetics 0.1.0-alpha.44

- El preview 1.21.11 usa directamente la misma geometría que renderiza correctamente los cosméticos equipados en el mundo.
- Elimina la conversión intermedia a `ModelPart` que reinterpretaba las caras como fragmentos del atlas PNG.
- Mantiene el framebuffer PIP aislado, doble cara y un solo cosmético visible por vez.
