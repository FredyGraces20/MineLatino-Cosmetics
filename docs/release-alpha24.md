# MineLatino Cosmetics 0.1.0-alpha.24

- Corrige la orientación global de las skins animadas: ya no aparecen giradas 180 grados respecto al jugador.
- Refuerza la sustitución del cuerpo vanilla en Minecraft 1.21.11 para evitar que la skin normal se dibuje debajo del avatar.
- Aplica la misma sustitución correctamente en la vista previa del armario.
- Elimina los refmaps estáticos obsoletos y hace que Fabric genere el refmap que realmente carga la configuración, incluyendo el hook de `PlayerModel.setupAnim`.
