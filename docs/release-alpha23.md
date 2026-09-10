# MineLatino Cosmetics 0.1.0-alpha.23

- Corrige personajes animados que reproducían `run` mientras estaban quietos usando la velocidad real de la animación de caminar.
- Implementa las capas `pre_parallel0..7` y `parallel0..7` con su prioridad y mezcla de rotación correspondientes.
- Oculta correctamente armas, escudos, efectos y otros huesos opcionales hasta que la animación principal los active.
- Evita escoger una animación arbitraria cuando un paquete no contiene el clip de movimiento solicitado.
- Incluye cobertura automática de la composición de posición, rotación y escala.
