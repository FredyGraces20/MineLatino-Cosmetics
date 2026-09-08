# MineLatino Cosmetics - Plan de Arquitectura Completa

## Resumen Ejecutivo

Este documento describe la arquitectura completa del sistema de cosmeticos de MineLatino, desde la creacion de modelos 3D hasta la entrega automatica a los jugadores a traves del launcher.

---

## 1. Flujo Completo de Cosmeticos

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           PANEL ADMINISTRATIVO                               │
│  https://minelatino-cosmetics-production.up.railway.app                     │
├─────────────────────────────────────────────────────────────────────────────┤
│  1. Crear cosmetico (ID, nombre, slot, estado)                              │
│  2. Subir textura PNG y/o modelo 3D (Blockbench JSON)                       │
│  3. Asignar a jugadores (manual, codigo, o tienda)                          │
│  4. Publicar cambios                                                        │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         BACKEND (Railway)                                    │
│  - SQLite: catalogo, asignaciones, recursos                                 │
│  - API REST: /v1/resources/:id (texturas/modelos)                           │
│  - API REST: /v1/admin/cosmetics/* (panel)                                  │
│  - API REST: /v1/auth/* (verificacion premium)                              │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                    ┌───────────────┴───────────────┐
                    ▼                               ▼
┌───────────────────────────────┐   ┌───────────────────────────────────────┐
│     MINELATINO LAUNCHER       │   │         MOD (Fabric/Forge)            │
├───────────────────────────────┤   ├───────────────────────────────────────┤
│  - Instala mod automaticamente│   │  - Conecta al backend                 │
│  - Actualiza mod en cada      │   │  - Autentica con sesion Minecraft     │
│    inicio si hay nueva version│   │  - Muestra wardrobe en ESC            │
│  - Gestiona resource packs    │   │  - Descarga texturas/modelos          │
│  - Configura instancia        │   │  - Renderiza cosmeticos en jugadores  │
└───────────────────────────────┘   └───────────────────────────────────────┘
                    │                               │
                    └───────────────┬───────────────┘
                                    ▼
                    ┌───────────────────────────────┐
                    │      MINECRAFT 1.21.4         │
                    │  - Jugador ve sus cosmeticos  │
                    │  - Otros jugadores los ven    │
                    │  - Modelos 3D renderizados    │
                    └───────────────────────────────┘
```

---

## 2. Sistema de Modelos 3D

### 2.1 Formato de Archivo: Blockbench

Usaremos **Blockbench** como formato estandar para modelos 3D:
- Formato: `bedrock` o `java_block` (JSON)
- Extension: `.bbmodel` (editable) o `.json` (exportado)
- Texturas: PNG 64x64 o 128x128 (formato skin de Minecraft)

### 2.2 Estructura de Recursos por Cosmetico

Cada cosmetico tiene uno o mas recursos asociados:

```
cosmetic-id/
├── texture.png          # Textura del modelo (requerido)
├── model.json           # Modelo 3D Blockbench exportado (opcional)
└── config.json          # Configuracion de renderizado
```

**config.json ejemplo:**
```json
{
  "slot": "HAT",
  "scale": 1.0,
  "offset": [0, 0, 0],
  "rotation": [0, 0, 0],
  "bone": "head",
  "renderType": "model",
  "animations": []
}
```

### 2.3 Panel Administrativo - Subida de Modelos

El panel web necesita extenderse para soportar modelos 3D:

**Nuevos endpoints:**
```
PUT /v1/admin/cosmetics/catalog/:id/resource/texture   # Subir textura
PUT /v1/admin/cosmetics/catalog/:id/resource/model     # Subir modelo 3D
PUT /v1/admin/cosmetics/catalog/:id/resource/config    # Subir config
GET /v1/resources/:id/texture                          # Descargar textura
GET /v1/resources/:id/model                            # Descargar modelo
GET /v1/resources/:id/config                           # Descargar config
```

**UI del panel:**
- Seleccion de cosmetico
- Upload de textura (PNG)
- Upload de modelo (JSON de Blockbench)
- Preview 3D en el navegador (usando three.js)
- Configuracion visual de offset/rotacion/scale

### 2.4 Almacenamiento en Backend

Tabla `resources` extendida:
```sql
CREATE TABLE resources (
  cosmetic_id TEXT NOT NULL,
  resource_type TEXT NOT NULL,  -- 'texture', 'model', 'config'
  file_path TEXT NOT NULL,
  sha256 TEXT NOT NULL,
  file_size INTEGER NOT NULL,
  content_type TEXT NOT NULL,
  uploaded_at INTEGER NOT NULL,
  PRIMARY KEY (cosmetic_id, resource_type),
  FOREIGN KEY (cosmetic_id) REFERENCES cosmetics(id)
);
```

---

## 3. Renderizado en el Juego

### 3.1 Arquitectura del Renderer

```
┌─────────────────────────────────────────────────────────────────┐
│                      CosmeticRenderer                            │
├─────────────────────────────────────────────────────────────────┤
│  RenderLayer<PlayerRenderState, PlayerModel>                    │
│                                                                  │
│  1. Obtener UUID del jugador (desde mixin)                      │
│  2. Consultar EquipmentCache para cosmeticos equipados          │
│  3. Para cada cosmetico:                                        │
│     a. Obtener ResourceLocation de ResourceCache                │
│     b. Si es textura plana: renderizar quad en el slot         │
│     c. Si es modelo 3D: parsear JSON y renderizar geometria    │
│  4. Aplicar transformaciones (offset, rotation, scale)          │
│  5. Renderizar en el bone correspondiente (head, body, etc.)    │
└─────────────────────────────────────────────────────────────────┘
```

### 3.2 Soporte de Modelos 3D

**Parser de Blockbench JSON:**
```java
public class BlockbenchModel {
    public record Bone(String name, List<Cube> cubes, double[] pivot) {}
    public record Cube(double[] origin, double[] size, double[] uv, int textureWidth, int textureHeight) {}
    
    public static BlockbenchModel parse(String json) { ... }
    public void render(VertexConsumer buffer, PoseStack pose, int packedLight) { ... }
}
```

**Renderizado:**
- Para cada cubo del modelo, generar 6 caras con UVs correctos
- Aplicar transformaciones del bone (head, body, leftArm, etc.)
- Usar `RenderType.entityCutout(textureLocation)` para la textura

### 3.3 Descarga Dinamica de Recursos

El `ResourceCache` existente se extiende:
```java
public class ResourceCache {
    // Texturas (existente)
    public ResourceLocation getOrDownloadTexture(String cosmeticId) { ... }
    
    // Modelos 3D (nuevo)
    public BlockbenchModel getOrDownloadModel(String cosmeticId) { ... }
    
    // Config (nuevo)
    public CosmeticConfig getOrDownloadConfig(String cosmeticId) { ... }
}
```

**Cache en disco:**
```
.minecraft/cache/minelatino-cosmetics/
├── textures/
│   ├── cape-fundador.png
│   └── alas-dragon.png
├── models/
│   └── sombrero-cowboy.json
└── configs/
    └── sombrero-cowboy.json
```

---

## 4. Integracion con MineLatino Launcher

### 4.1 Arquitectura de Integracion

El launcher gestiona automaticamente:
1. **Instalacion del mod** en la instancia de Minecraft
2. **Actualizacion del mod** cuando hay nueva version
3. **Configuracion** de la URL del backend
4. **Resource packs** opcionales para texturas

### 4.2 Servicio de Cosmeticos en el Launcher

Nuevo servicio en `MineLatinoService.ts`:

```typescript
// xmcl-electron-app/main/minelatino/cosmeticsMod.ts

export class CosmeticsModManager {
  private readonly modUrl = 'https://minelatino-cosmetics-production.up.railway.app/api/mod/latest';
  private readonly modVersionUrl = 'https://minelatino-cosmetics-production.up.railway.app/api/mod/version';
  
  /**
   * Verifica si el mod esta instalado y actualizado.
   * Si no, lo descarga e instala en la instancia.
   */
  async ensureModInstalled(instancePath: string, loader: 'fabric' | 'forge'): Promise<void> {
    // 1. Verificar version actual del mod
    const currentVersion = await this.getInstalledModVersion(instancePath, loader);
    const latestVersion = await this.fetchLatestVersion();
    
    // 2. Si no esta instalado o hay nueva version, descargar
    if (!currentVersion || currentVersion !== latestVersion) {
      const modFile = await this.downloadMod(loader);
      await this.installMod(instancePath, modFile);
    }
  }
  
  /**
   * Configura el mod para apuntar al backend correcto.
   */
  async configureMod(instancePath: string): Promise<void> {
    const configPath = join(instancePath, 'config/minelatino-cosmetics/config.json');
    await outputJson(configPath, {
      backendUrl: 'https://minelatino-cosmetics-production.up.railway.app'
    });
  }
}
```

### 4.3 Hook en el Lanzamiento del Juego

Modificar `MineLatinoService.ts` para verificar el mod antes de lanzar:

```typescript
// En el constructor de MineLatinoService, despues de minecraft-exit handler:

const instanceService = await this.app.registry.get(InstanceService);

// Antes de lanzar el juego, asegurar que el mod de cosmeticos esta instalado
this.app.on('game-launch', async (options) => {
  const instancePath = options.gameDirectory;
  const loader = options.loader; // 'fabric' o 'forge'
  
  const cosmeticsMod = new CosmeticsModManager(this.app);
  await cosmeticsMod.ensureModInstalled(instancePath, loader);
  await cosmeticsMod.configureMod(instancePath);
});
```

### 4.4 API del Backend para el Launcher

Nuevos endpoints en el backend de cosmeticos:

```
GET /api/mod/latest?loader=fabric    # Descarga el JAR mas reciente
GET /api/mod/version?loader=fabric   # Obtiene la version actual (string)
GET /api/mod/changelog               # Obtiene el changelog
```

El backend sirve el JAR desde un directorio configurado:
```javascript
// server.mjs
const modDir = process.env.COSMETICS_MOD_DIR || join(dataDir, 'mods');
mkdirSync(modDir, { recursive: true });

// Endpoint para descargar el mod
app.get('/api/mod/latest', (req, res) => {
  const loader = req.query.loader || 'fabric';
  const modFile = findLatestMod(modDir, loader);
  res.sendFile(modFile);
});

// Endpoint para verificar version
app.get('/api/mod/version', (req, res) => {
  const loader = req.query.loader || 'fabric';
  const version = getModVersion(modDir, loader);
  res.json({ version, loader });
});
```

### 4.5 Flujo de Actualizacion Automatica

```
1. Usuario abre MineLatino Launcher
2. Launcher verifica version del mod de cosmeticos
3. Si no esta instalado o hay nueva version:
   a. Descarga el JAR desde /api/mod/latest
   b. Lo copia a <instancia>/mods/
   c. Configura config.json con la URL del backend
4. Usuario lanza Minecraft
5. Mod se carga, conecta al backend, descarga texturas/modelos
6. Jugador ve sus cosmeticos
```

---

## 5. Sistema de Entrega y Tienda

### 5.1 Modelos de Entrega

| Metodo | Descripcion | Caso de Uso |
|--------|-------------|-------------|
| **Manual** | Admin asigna UUID en panel | Regalos, premios, staff |
| **Codigo** | Jugador ingresa codigo de redeem | Tarjetas, eventos |
| **Tienda** | Compra con dinero real | Monetizacion |
| **Pases** | Desbloqueo por nivel/XP | Recompensas de juego |
| **Gacha** | Caja sorpresa con probabilidades | Eventos especiales |

### 5.2 Sistema de Codigos de Redeem

**Backend:**
```sql
CREATE TABLE redeem_codes (
  code TEXT PRIMARY KEY,
  cosmetic_id TEXT NOT NULL REFERENCES cosmetics(id),
  max_uses INTEGER DEFAULT 1,
  uses INTEGER DEFAULT 0,
  expires_at INTEGER,
  created_at INTEGER NOT NULL
);

CREATE TABLE redeem_claims (
  code TEXT NOT NULL REFERENCES redeem_codes(code),
  uuid TEXT NOT NULL,
  claimed_at INTEGER NOT NULL,
  PRIMARY KEY (code, uuid)
);
```

**API:**
```
POST /v1/redeem                    # Jugador reclama un codigo
GET /v1/admin/redeem-codes         # Admin ve codigos creados
POST /v1/admin/redeem-codes        # Admin crea nuevo codigo
```

**Flujo del jugador:**
1. Jugador recibe codigo (ej: "FUNDADOR-2026")
2. Abre ESC → Cosméticos → "Reclamar codigo"
3. Ingresa el codigo
4. Backend valida y asigna el cosmetico
5. Jugador ve el cosmetico en su armario

### 5.3 Integracion con Tienda (Futuro)

**Arquitectura:**
```
┌─────────────────────────────────────────────────────────────┐
│                    TIENDA MINE LATINO                        │
│  https://minelatino.com/tienda                              │
├─────────────────────────────────────────────────────────────┤
│  - WooCommerce / Shopify / Custom                           │
│  - Pagos con PayPal, Stripe, etc.                           │
│  - Al comprar, genera asignacion en backend de cosmeticos   │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│              BACKEND COSMETICOS (Railway)                    │
│  POST /v1/admin/cosmetics/grants                            │
│  { uuid, cosmeticId, reference: "order-12345" }             │
└─────────────────────────────────────────────────────────────┘
```

**Flujo de compra:**
1. Usuario compra cosmetico en la tienda web
2. Tienda llama webhook a `/v1/admin/cosmetics/grants`
3. Backend asigna cosmetico al UUID del comprador
4. Usuario abre Minecraft, ve el cosmetico en su armario

### 5.4 Categorias y Precios

Tabla `cosmetics` extendida:
```sql
ALTER TABLE cosmetics ADD COLUMN price REAL;           -- Precio en USD
ALTER TABLE cosmetics ADD COLUMN rarity TEXT;          -- 'common', 'rare', 'epic', 'legendary'
ALTER TABLE cosmetics ADD COLUMN category TEXT;        -- 'seasonal', 'permanent', 'limited'
ALTER TABLE cosmetics ADD COLUMN store_visible INTEGER DEFAULT 0;  -- Visible en tienda
```

---

## 6. Roadmap de Implementacion

### Fase 1: Modelos 3D en Backend y Panel (Semana 1-2)
- [ ] Extender tabla `resources` para soportar multiples tipos
- [ ] Endpoints para subir textura, modelo y config separados
- [ ] UI del panel con upload de modelos Blockbench
- [ ] Preview 3D en el navegador (three.js)
- [ ] Validacion de archivos (tamano, formato)

### Fase 2: Renderizado 3D en el Mod (Semana 2-3)
- [ ] Parser de modelos Blockbench JSON
- [ ] Renderizador de cubos con UVs correctos
- [ ] Soporte de bones (head, body, arms, legs)
- [ ] Transformaciones (offset, rotation, scale)
- [ ] ResourceCache extendido para modelos y configs

### Fase 3: Integracion con Launcher (Semana 3-4)
- [ ] CosmeticsModManager en MineLatinoService
- [ ] API del backend para servir el mod
- [ ] Hook en game-launch para instalar/actualizar mod
- [ ] Configuracion automatica del backend URL
- [ ] UI en launcher mostrando estado del mod

### Fase 4: Sistema de Codigos (Semana 4-5)
- [ ] Tablas redeem_codes y redeem_claims
- [ ] Endpoint POST /v1/redeem
- [ ] UI en WardrobeScreen para reclamar codigos
- [ ] Panel admin para crear/gestionar codigos

### Fase 5: Tienda (Semana 6+)
- [ ] Definir plataforma de pagos (WooCommerce/Stripe)
- [ ] Webhook para asignar cosmeticos tras compra
- [ ] Catalogo visible en tienda web
- [ ] Integration con launcher (mostrar cosmeticos disponibles)

---

## 7. Consideraciones Tecnicas

### 7.1 Rendimiento

- **Cache agresivo**: Texturas y modelos se cachean en disco
- **Descarga diferida**: Solo descargar cosmeticos equipados
- **LOD (Level of Detail)**: Modelos simplificados a distancia
- **Batch rendering**: Agrupar cosmeticos por textura

### 7.2 Seguridad

- **Validacion de archivos**: Solo PNG y JSON permitidos
- **Sandbox de modelos**: Limitar numero de cubos, no ejecutar codigo
- **Rate limiting**: Prevenir abuso de descargas
- **Integridad**: SHA-256 en todos los recursos

### 7.3 Compatibilidad

- **Minecraft 1.21.4**: Versión objetivo
- **Fabric + Forge**: Ambos loaders soportados
- **Optifine/Sodium**: Compatible con mods de rendimiento
- **Servidores**: Funciona en multiplayer sin mod en servidor

---

## 8. Archivos Clave

### Backend (service/)
- `service/src/store.mjs` - Base de datos SQLite
- `service/src/api.mjs` - Endpoints REST
- `service/public/index.html` - Panel administrativo

### Mod (common/, client/, fabric/, forge/)
- `client/src/main/java/.../CosmeticRenderer.java` - Renderizado
- `client/src/main/java/.../ResourceCache.java` - Cache de recursos
- `client/src/main/java/.../BlockbenchModel.java` - Parser de modelos (nuevo)

### Launcher (minelatino-launcher/)
- `xmcl-electron-app/main/minelatino/MineLatinoService.ts` - Servicio principal
- `xmcl-electron-app/main/minelatino/cosmeticsMod.ts` - Gestor de mod (nuevo)

---

## 9. Resumen

Este sistema permite:
1. **Crear cosmeticos** facilmente desde el panel web
2. **Subir modelos 3D** con texturas y configuracion
3. **Distribuir automaticamente** a traves del launcher
4. **Renderizar en el juego** con soporte completo de 3D
5. **Entregar por multiples vias**: manual, codigo, tienda
6. **Escalar** a miles de jugadores sin esfuerzo manual

La arquitectura es modular, permitiendo implementar cada fase incrementalmente.
