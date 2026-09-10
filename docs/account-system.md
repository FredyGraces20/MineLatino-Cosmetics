# Cuentas MineLatino para cosméticos

La propiedad de un cosmético se guarda contra `player_accounts.account_id`. El correo es único para iniciar sesión; el nick es un alias visible y puede repetirse.

El launcher conserva únicamente la sesión principal en el almacén seguro del sistema operativo. Antes de iniciar Minecraft solicita un token `game` de 24 horas y escribe ese token en `config/minelatino-cosmetics/config.json`. El token del juego puede consultar/equipar el armario y publicar presencia, pero no puede leer el correo ni modificar o eliminar la cuenta.

El mod publica el UUID y nombre de la sesión de Minecraft asociados temporalmente al `account_id`. Las consultas públicas resuelven esa presencia para que otros clientes con el mod rendericen el equipamiento. El nombre de Minecraft debe coincidir con el nick configurado en la cuenta.

Las tablas antiguas por UUID premium se conservan para compatibilidad. Las compras nuevas deben crear órdenes con `owner_type=account`; al confirmarse el pago, el cosmético se entrega a `account_entitlements`.

La eliminación es lógica (`status=deleted`): revoca todas las sesiones y retira la presencia, pero conserva compras y auditoría para una posible recuperación o anonimización posterior.
