# MineLatino Cosmetics 0.1.0-alpha.17

- Mantiene la apariencia premium en servidores offline y resuelve jugadores cercanos por su nombre premium previamente verificado.
- Añade autenticación y renovación automática en Minecraft 1.21.11, con un límite de reintentos de cinco minutos.
- Valida UUID, equipo y transformaciones recibidas antes de renderizarlas.
- Impide publicar cosméticos sin textura y convierte en borrador los publicados inválidos al iniciar el servicio.
- Corrige el límite por IP detrás del proxy de Railway sin confiar cabeceras en instalaciones directas.
- Añade compilación Forge 1.21.11 mediante ForgeGradle 7 y separa las salidas de build por versión.
- El flujo de despliegue compila Fabric/Forge 1.21.4 y 1.21.11, verifica hashes y nunca elimina la versión anterior antes de tener la nueva.

Validación: 55 pruebas del servicio, pruebas comunes Java y compilación de los cuatro JARs.
