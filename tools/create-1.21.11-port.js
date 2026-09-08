const fs = require('fs');
const path = require('path');

const base = path.join(__dirname, '..', 'client-1.21.11', 'src', 'main');
const javaDir = path.join(base, 'java', 'com', 'minelatino', 'cosmetics', 'client');
const mixinDir = path.join(javaDir, 'mixin');
const resDir = path.join(base, 'resources');

[javaDir, mixinDir, resDir].forEach(d => fs.mkdirSync(d, { recursive: true }));

const files = {};

// ═══════════════════════════════════════════════════════════
// CosmeticRenderer.java (1.21.11)
// ═══════════════════════════════════════════════════════════
files[path.join(javaDir, 'CosmeticRenderer.java')] = `package com.minelatino.cosmetics.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Quaternionf;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders cosmetic overlays on players using 3D models (Blockbench JSON format).
 * Ported for Minecraft 1.21.11:
 *   PlayerRenderState -> AvatarRenderState
 *   PlayerModel -> net.minecraft.client.model.player.PlayerModel
 *   ResourceLocation -> Identifier
 *   RenderType -> RenderTypes (in rendertype subpackage)
 *   render() -> submit() with SubmitNodeCollector
 *   MultiBufferSource -> SubmitNodeCollector
 */
public final class CosmeticRenderer extends RenderLayer<AvatarRenderState, PlayerModel> {
    private static final Logger LOG = LoggerFactory.getLogger("MineLatino Cosmetics");

    static final Map<Integer, UUID> ENTITY_UUID_MAP = new ConcurrentHashMap<>();
    private static int renderCallCount = 0;
    private final ResourceCache resourceCache;

    public CosmeticRenderer(RenderLayerParent<AvatarRenderState, PlayerModel> parent, ResourceCache resourceCache) {
        super(parent);
        this.resourceCache = resourceCache;
        LOG.info("[CosmeticRenderer] Layer constructed (1.21.11)");
        CosmeticsDiagnostics.event("LAYER_CREATED","CosmeticRenderer");
    }

    public static void putEntityUuid(int entityId, UUID uuid) {
        ENTITY_UUID_MAP.put(entityId, uuid);
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int packedLight,
                       AvatarRenderState renderState, float yaw, float partialTick) {
        renderCallCount++;
        boolean local=Minecraft.getInstance().player!=null && Minecraft.getInstance().player.getId()==renderState.id;
        if (renderState.isInvisible || renderState.isSpectator) {
            if(local) CosmeticsDiagnostics.changed("WORLD_RENDER","skipped invisible="+renderState.isInvisible+" spectator="+renderState.isSpectator);
            return;
        }

        CosmeticPreview.Frame preview = CosmeticPreview.FRAME.get();
        List<EquipmentCache.EquippedItem> equipped;
        if (preview != null && preview.entityId == renderState.id) {
            preview.layerVisited = true;
            CosmeticsDiagnostics.changed("PREVIEW_RENDER","layer active; items="+preview.items.size());
            equipped = preview.items;
        } else {
            UUID uuid = ENTITY_UUID_MAP.get(renderState.id);
            if (Minecraft.getInstance().player != null
                    && Minecraft.getInstance().player.getId() == renderState.id
                    && CosmeticsClient.instance().auth().isConnected()
                    && CosmeticsClient.instance().auth().session() != null) {
                try {
                    uuid = UUID.fromString(formatUuid(CosmeticsClient.instance().auth().session().uuid()));
                } catch (IllegalArgumentException ignored) {}
            }
            if (uuid == null) {
                if(local) CosmeticsDiagnostics.changed("WORLD_RENDER","missing entity UUID mapping");
                return;
            }
            equipped = CosmeticsClient.instance().equipment().get(uuid.toString());
        }
        if (equipped == null || equipped.isEmpty()) {
            if(local) CosmeticsDiagnostics.changed("WORLD_RENDER","no equipment in resolved UUID cache");
            return;
        }

        int submitted=0, pendingResources=0, unsupported=0;
        for (EquipmentCache.EquippedItem item : equipped) {
            if (CosmeticSlot.from(item.slot()).isEmpty()) { unsupported++; continue; }
            ResourceCache.CachedResource res = resourceCache.getOrDownload(item.cosmeticId());
            if (res == null || res.texture() == null) { pendingResources++; continue; }

            CosmeticModel model = res.model();
            if (model != null && !model.elements.isEmpty()) {
                renderModel(poseStack, collector, packedLight, renderState, res, model, item.slot(), item.cosmeticId());
                if(!model.quads.isEmpty()) submitted++;
            } else {
                renderFallback(poseStack, collector, packedLight, renderState, res.texture(), item.slot());
                submitted++;
            }
        }
        if(local) CosmeticsDiagnostics.changed("WORLD_RENDER","equipped="+equipped.size()+" submitted="+submitted+
                " resourcesUnavailable="+pendingResources+" unsupportedSlots="+unsupported);
    }

    private static String formatUuid(String value) {
        String hex = value.replace("-", "");
        if (!hex.matches("[0-9a-fA-F]{32}")) throw new IllegalArgumentException("invalid UUID");
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
                + "-" + hex.substring(16, 20) + "-" + hex.substring(20);
    }

    private void renderModel(PoseStack poseStack, SubmitNodeCollector collector, int packedLight,
                             AvatarRenderState state, ResourceCache.CachedResource resource, CosmeticModel model, String slot, String cosmeticId) {
        poseStack.pushPose();
        try {
            getParentModel().root().translateAndRotate(poseStack);
            if ("HAT".equals(slot)) {
                getParentModel().head.translateAndRotate(poseStack);
                CustomHeadLayer.translateToHead(poseStack, CustomHeadLayer.Transforms.DEFAULT);
                ApiClient.TransformData serverHead = CosmeticsClient.instance().getTransform(cosmeticId, "head");
                if (serverHead != null) {
                    applyDisplayTransform(poseStack, serverHead);
                } else {
                    CosmeticModel.DisplayTransform head = model.head;
                    poseStack.translate(head.translation()[0]/16, head.translation()[1]/16, head.translation()[2]/16);
                    poseStack.mulPose(new Quaternionf().rotationXYZ(
                            (float)Math.toRadians(head.rotation()[0]),
                            (float)Math.toRadians(head.rotation()[1]),
                            (float)Math.toRadians(head.rotation()[2])));
                    poseStack.scale(head.scale()[0], head.scale()[1], head.scale()[2]);
                }
            } else if ("PET".equals(slot)) {
                poseStack.translate(0.8, 1.0 + Math.sin(state.ageInTicks * 0.08) * 0.035, 0);
                poseStack.scale(0.55f, -0.55f, -0.55f);
            } else {
                getParentModel().body.translateAndRotate(poseStack);
                poseStack.translate(0, 0.3, "BACKPACK".equals(slot) ? 0.30 : 0.16);
                poseStack.scale(1, -1, -1);
                if ("BACKPACK".equals(slot)) {
                    ApiClient.TransformData serverBackpack = CosmeticsClient.instance().getTransform(cosmeticId, "backpack");
                    if (serverBackpack != null) {
                        applyDisplayTransform(poseStack, serverBackpack);
                    } else {
                        var b = model.backpack;
                        poseStack.translate(b.translation()[0]/16,b.translation()[1]/16,b.translation()[2]/16);
                        poseStack.mulPose(new Quaternionf().rotationXYZ((float)Math.toRadians(b.rotation()[0]),(float)Math.toRadians(b.rotation()[1]),(float)Math.toRadians(b.rotation()[2])));
                        poseStack.scale(b.scale()[0],b.scale()[1],b.scale()[2]);
                    }
                }
            }
            for (CosmeticModel.Quad quad : model.quads) {
                var material = resource.materials().get(quad.texture());
                var texture = material == null ? resource.texture() : material.texture();
                if (material == null || (material.animation().rows() == 1 && material.animation().columns() == 1)) {
                    collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(texture), (pose, consumer) -> {
                        renderQuad(consumer, pose, packedLight, quad);
                    });
                } else {
                    var a = material.animation();
                    double ticks = System.nanoTime() / 50_000_000.0;
                    CosmeticModel.Quad animQuad = new CosmeticModel.Quad(a.vertex(quad.v0(),ticks),a.vertex(quad.v1(),ticks),
                            a.vertex(quad.v2(),ticks),a.vertex(quad.v3(),ticks),quad.normal(),quad.texture());
                    collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(texture), (pose, consumer) -> {
                        renderQuad(consumer, pose, packedLight, animQuad);
                    });
                }
            }
        } finally {
            poseStack.popPose();
        }
    }

    private static void applyDisplayTransform(PoseStack poseStack, ApiClient.TransformData t) {
        poseStack.translate(t.translation()[0]/16, t.translation()[1]/16, t.translation()[2]/16);
        poseStack.mulPose(new Quaternionf().rotationXYZ(
                (float)Math.toRadians(t.rotation()[0]),
                (float)Math.toRadians(t.rotation()[1]),
                (float)Math.toRadians(t.rotation()[2])));
        poseStack.scale(t.scale()[0], t.scale()[1], t.scale()[2]);
    }

    private static void renderQuad(VertexConsumer consumer, PoseStack.Pose pose, int packedLight, CosmeticModel.Quad quad) {
        float[] n = quad.normal();
        addVertex(consumer, pose, quad.v0(), packedLight, n);
        addVertex(consumer, pose, quad.v1(), packedLight, n);
        addVertex(consumer, pose, quad.v2(), packedLight, n);
        addVertex(consumer, pose, quad.v3(), packedLight, n);
    }

    private static void addVertex(VertexConsumer consumer, PoseStack.Pose pose, float[] v, int light, float[] normal) {
        consumer.addVertex(pose, v[0], v[1], v[2])
                .setColor(255, 255, 255, 255)
                .setUv(v[3], v[4])
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, normal[0], normal[1], normal[2]);
    }

    private void renderFallback(PoseStack poseStack, SubmitNodeCollector collector, int packedLight,
                                AvatarRenderState state, Identifier texture, String slot) {
        poseStack.pushPose();
        getParentModel().root().translateAndRotate(poseStack);
        try { switch (slot) {
            case "CAPE" -> collector.submitCustomGeometry(poseStack, RenderTypes.entityCutoutNoCull(texture), (pose, c) -> {
                getParentModel().body.translateAndRotate(new PoseStack()); // no-op for pose context
                drawQuad(c, pose, packedLight, -0.25f, 0.25f, 0.0f, 0.75f, 0.0f, 1.0f, 0.0f, 1.0f, 0, 0, -1);
            });
            case "HAT" -> collector.submitCustomGeometry(poseStack, RenderTypes.entityCutoutNoCull(texture), (pose, c) -> {
                drawQuad(c, pose, packedLight, -0.3f, 0.3f, -0.3f, 0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 0, 0, -1);
            });
            case "WINGS" -> collector.submitCustomGeometry(poseStack, RenderTypes.entityCutoutNoCull(texture), (pose, c) -> {
                drawQuad(c, pose, packedLight, -0.35f, 0.0f, -0.3f, 0.3f, 0.0f, 0.5f, 0.0f, 1.0f, 0, 0, -1);
                drawQuad(c, pose, packedLight, 0.0f, 0.35f, -0.3f, 0.3f, 0.5f, 1.0f, 0.0f, 1.0f, 0, 0, -1);
            });
            case "BACKPACK", "PET" -> {
                if ("BACKPACK".equals(slot)) {
                    getParentModel().body.translateAndRotate(poseStack);
                    poseStack.translate(0, 0.3, 0.16);
                } else poseStack.translate(0.8, 1.0, 0);
                collector.submitCustomGeometry(poseStack, RenderTypes.entityCutoutNoCull(texture), (pose, c) -> {
                    drawQuad(c, pose, packedLight, -.22f, .22f, -.22f, .22f, 0, 1, 0, 1, 0, 0, -1);
                });
            }
        } } finally { poseStack.popPose(); }
    }

    private static void drawQuad(VertexConsumer consumer, PoseStack.Pose pose, int packedLight,
                                  float x1, float x2, float y1, float y2,
                                  float u1, float u2, float v1, float v2,
                                  int nx, int ny, int nz) {
        consumer.addVertex(pose, x1, y1, 0).setColor(255, 255, 255, 255).setUv(u1, v1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(packedLight).setNormal(pose, nx, ny, nz);
        consumer.addVertex(pose, x1, y2, 0).setColor(255, 255, 255, 255).setUv(u1, v2).setOverlay(OverlayTexture.NO_OVERLAY).setLight(packedLight).setNormal(pose, nx, ny, nz);
        consumer.addVertex(pose, x2, y2, 0).setColor(255, 255, 255, 255).setUv(u2, v2).setOverlay(OverlayTexture.NO_OVERLAY).setLight(packedLight).setNormal(pose, nx, ny, nz);
        consumer.addVertex(pose, x2, y1, 0).setColor(255, 255, 255, 255).setUv(u2, v1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(packedLight).setNormal(pose, nx, ny, nz);
    }
}
`;

// ═══════════════════════════════════════════════════════════
// ResourceCache.java (1.21.11)
// ═══════════════════════════════════════════════════════════
files[path.join(javaDir, 'ResourceCache.java')] = `package com.minelatino.cosmetics.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Downloads and caches cosmetic resources: textures (PNG) and 3D models (JSON).
 * Ported for 1.21.11: ResourceLocation -> Identifier, DynamicTexture requires name supplier.
 */
public final class ResourceCache {
    private static final Logger LOG = LoggerFactory.getLogger("MineLatino Cosmetics");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String baseUrl;
    private final HttpClient http;
    private final Path cacheDir;
    private final Map<String, Identifier> textures = new ConcurrentHashMap<>();
    private final Map<String, CosmeticModel> models = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Material>> materials = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<CachedResource>> pending = new ConcurrentHashMap<>();
    private final Map<String, Long> nextRefresh = new ConcurrentHashMap<>();
    private volatile long generation;
    private final Map<String, String> errors = new ConcurrentHashMap<>();

    public String error(String id) { return errors.get(id); }
    public boolean isLoading(String id) { return pending.containsKey(id); }
    public void retry(String id) { nextRefresh.remove(id); errors.remove(id); }

    public record Material(Identifier texture, TextureAnimation animation) {}
    public record CachedResource(Identifier texture, CosmeticModel model, Map<String, Material> materials) {}
    private CachedResource cached(String id, Identifier texture, CosmeticModel model) {
        return new CachedResource(texture, model, materials.getOrDefault(id, Map.of()));
    }

    public ResourceCache(String baseUrl, Path cacheDir) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.cacheDir = cacheDir;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        try { Files.createDirectories(cacheDir); } catch (IOException e) { LOG.warn("Failed to create cache dir", e); }
    }

    public CachedResource getOrDownload(String cosmeticId) {
        if (cosmeticId == null || !cosmeticId.matches("[a-z0-9_-]{1,128}")) return null;
        Identifier tex = textures.get(cosmeticId);
        CosmeticModel model = models.get(cosmeticId);
        long now = System.currentTimeMillis();
        if (now < nextRefresh.getOrDefault(cosmeticId, 0L))
            return tex == null ? null : cached(cosmeticId, tex, model);

        long requestGeneration = generation;
        pending.computeIfAbsent(cosmeticId, id -> CompletableFuture.supplyAsync(() -> {
            try { return downloadBoth(id); }
            catch (Exception e) {
                CosmeticsDiagnostics.event("RESOURCE_FAILED",CosmeticsDiagnostics.id(id)+" "+CosmeticsDiagnostics.failure(e));
                if (requestGeneration == generation) errors.put(id, "No se pudo cargar PNG/modelo: " + e.getMessage());
                LOG.warn("Resource download failed for {}: {}", id, e.toString()); return null;
            }
        }).thenApplyAsync(data -> {
            if (data == null || requestGeneration != generation) return null;
            return registerOnMainThread(cosmeticId, data);
        }, runnable -> Minecraft.getInstance().execute(runnable)));

        CompletableFuture<CachedResource> future = pending.get(cosmeticId);
        if (future != null && future.isDone()) {
            try {
                CachedResource res = future.get();
                pending.remove(cosmeticId);
                nextRefresh.put(cosmeticId, now + (res == null ? 15_000 : 60_000));
                if (res != null) errors.remove(cosmeticId);
                return res != null ? res : (tex == null ? null : cached(cosmeticId, tex, model));
            } catch (Exception e) {
                pending.remove(cosmeticId);
                nextRefresh.put(cosmeticId, now + 15_000);
                LOG.warn("Failed to load cosmetic {}", cosmeticId, e);
                errors.put(cosmeticId, "No se pudo preparar la textura en el juego");
            }
        }
        if (tex != null) return cached(cosmeticId, tex, model);
        return null;
    }

    public Identifier getOrDownloadTexture(String cosmeticId) {
        CachedResource res = getOrDownload(cosmeticId);
        return res != null ? res.texture() : null;
    }

    public CosmeticModel getModel(String cosmeticId) { return models.get(cosmeticId); }
    public Identifier get(String cosmeticId) { return textures.get(cosmeticId); }

    private record TextureData(byte[] png, String mcmeta) {}
    private record DownloadData(byte[] texturePng, CosmeticModel model, Map<String, TextureData> materials) {}

    private byte[] download(String id, String query) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/resources/" + id + "?" + query))
                .timeout(TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200 || response.body().length > 2*1024*1024)
            throw new IOException("Missing/invalid cosmetic resource " + query + " HTTP " + response.statusCode());
        return response.body();
    }

    private DownloadData downloadBoth(String cosmeticId) throws Exception {
        byte[] textureData = null;
        String revision = "refresh=" + System.currentTimeMillis();
        HttpRequest texReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/resources/" + cosmeticId + "?" + revision))
                .timeout(TIMEOUT).header("Cache-Control", "no-cache").header("Accept", "image/png").GET().build();
        HttpResponse<byte[]> texRes = http.send(texReq, HttpResponse.BodyHandlers.ofByteArray());
        CosmeticsDiagnostics.event("RESOURCE_PNG",CosmeticsDiagnostics.id(cosmeticId)+" status="+texRes.statusCode());
        if (texRes.statusCode() == 200) {
            byte[] data = texRes.body();
            if (data.length >= 8 && data[0] == (byte) 0x89 && data[1] == (byte) 0x50) textureData = data;
        }
        if (textureData == null) throw new IOException("Texture unavailable: HTTP " + texRes.statusCode());
        if (textureData.length > 2 * 1024 * 1024) throw new IOException("Texture exceeds 2 MiB");
        CosmeticModel model = CosmeticModel.empty();
        HttpRequest modelReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/resources/" + cosmeticId + "?type=model&" + revision))
                .timeout(TIMEOUT).header("Cache-Control", "no-cache").header("Accept", "application/json").GET().build();
        HttpResponse<String> modelRes = http.send(modelReq, HttpResponse.BodyHandlers.ofString());
        CosmeticsDiagnostics.event("RESOURCE_MODEL",CosmeticsDiagnostics.id(cosmeticId)+" status="+modelRes.statusCode());
        if (modelRes.statusCode() == 200) {
            String json = modelRes.body();
            if (json == null || json.length() > 2 * 1024 * 1024) throw new IOException("Invalid model size");
            model = CosmeticModel.parse(json);
        } else if (modelRes.statusCode() != 404) {
            throw new IOException("Model unavailable: HTTP " + modelRes.statusCode());
        }
        var named = new java.util.LinkedHashMap<String, TextureData>();
        var names = model.quads.stream().map(CosmeticModel.Quad::texture).distinct().toList();
        if (names.size() > 32) throw new IOException("Too many model textures");
        if (!names.isEmpty()) {
            var manifestResponse = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/resources/" + cosmeticId + "?type=manifest&" + revision))
                    .timeout(TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofString());
            var filesMap = new java.util.HashMap<String, Boolean>();
            try {
                var manifest = com.google.gson.JsonParser.parseString(manifestResponse.body()).getAsJsonObject();
                for (var entry : manifest.getAsJsonArray("files")) {
                    var file = entry.getAsJsonObject(); filesMap.put(file.get("name").getAsString(), file.get("hasMcmeta").getAsBoolean());
                }
            } catch (RuntimeException e) {
                if (names.size() > 1) throw new IOException("Update cosmetics service: texture manifest unavailable", e);
            }
            for (String name : names) {
                if (filesMap.containsKey(name)) {
                    byte[] png = download(cosmeticId, "file="+name+"&"+revision);
                    String meta = filesMap.get(name) ? new String(download(cosmeticId,"file="+name+"&type=mcmeta&"+revision), StandardCharsets.UTF_8) : null;
                    named.put(name, new TextureData(png, meta));
                } else if (names.size() == 1) named.put(name, new TextureData(textureData,null));
                else throw new IOException("Missing texture file: " + name + ". Upload PNG with this name.");
            }
        }
        return new DownloadData(textureData, model, named);
    }

    private CachedResource registerOnMainThread(String cosmeticId, DownloadData data) {
        Identifier texLoc = null;
        CosmeticModel model = data.model();
        if (data.texturePng() != null) {
            try {
                texLoc = Identifier.fromNamespaceAndPath("minelatino_cosmetics", "cosmetics/" + cosmeticId);
                NativeImage image = NativeImage.read(new ByteArrayInputStream(data.texturePng()));
                if (image.getWidth() > 4096 || image.getHeight() > 4096) { image.close(); throw new IOException("Texture dimensions exceed 4096"); }
                DynamicTexture texture = new DynamicTexture(() -> "minelatino_cosmetics/" + cosmeticId, image);
                Minecraft.getInstance().getTextureManager().register(texLoc, texture);
                Minecraft.getInstance().getTextureManager().getTexture(texLoc).setFilter(false, false);
                textures.put(cosmeticId, texLoc);
                LOG.debug("Registered cosmetic texture: {}", cosmeticId);
            } catch (IOException e) {
                LOG.warn("Failed to register cosmetic texture {}", cosmeticId, e);
                errors.put(cosmeticId, "PNG inválido o dimensiones no admitidas");
                return null;
            }
        }
        if (texLoc == null) return null;
        var loaded = new java.util.LinkedHashMap<String, Material>();
        try {
            for (var entry : data.materials().entrySet()) {
                NativeImage image = NativeImage.read(new ByteArrayInputStream(entry.getValue().png()));
                TextureAnimation animation;
                try {
                    if (image.getWidth() > 4096 || image.getHeight() > 4096) throw new IOException("Texture too large");
                    animation = TextureAnimation.parse(entry.getValue().mcmeta(), image.getWidth(), image.getHeight());
                } catch (Exception e) { image.close(); throw e; }
                var location = Identifier.fromNamespaceAndPath("minelatino_cosmetics", "cosmetics/"+cosmeticId+"/"+(entry.getKey().isEmpty()?"default":entry.getKey()));
                Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(() -> "minelatino_cosmetics/" + cosmeticId + "/" + entry.getKey(), image));
                Minecraft.getInstance().getTextureManager().getTexture(location).setFilter(false,false);
                loaded.put(entry.getKey(), new Material(location,animation));
            }
        } catch (Exception e) {
            loaded.values().forEach(m -> Minecraft.getInstance().getTextureManager().release(m.texture()));
            throw new IllegalArgumentException("Could not prepare model textures", e);
        }
        var oldMaterials = materials.put(cosmeticId, Map.copyOf(loaded));
        if (oldMaterials != null) oldMaterials.values().stream().filter(m -> loaded.values().stream().noneMatch(n -> n.texture().equals(m.texture())))
                .forEach(m -> Minecraft.getInstance().getTextureManager().release(m.texture()));
        models.put(cosmeticId, model);
        CosmeticsDiagnostics.event("RESOURCE_READY",CosmeticsDiagnostics.id(cosmeticId)+" elements="+model.elements.size()+" quads="+model.quads.size());
        return cached(cosmeticId, texLoc, model);
    }

    public void clear() {
        generation++;
        var oldTextures = java.util.List.copyOf(textures.values());
        var oldMaterials = materials.values().stream().flatMap(m -> m.values().stream()).map(Material::texture).toList();
        Minecraft.getInstance().execute(() -> oldMaterials.forEach(id -> Minecraft.getInstance().getTextureManager().release(id)));
        materials.clear();
        Minecraft.getInstance().execute(() -> oldTextures.forEach(id -> Minecraft.getInstance().getTextureManager().release(id)));
        textures.clear();
        models.clear();
        pending.clear();
        nextRefresh.clear();
        errors.clear();
    }
}
`;

// ═══════════════════════════════════════════════════════════
// PauseMenu.java (1.21.11) - only import change
// ═══════════════════════════════════════════════════════════
// Read the original and replace the Util import
let pauseMenu = fs.readFileSync(path.join(__dirname, '..', 'client', 'src', 'main', 'java', 'com', 'minelatino', 'cosmetics', 'client', 'PauseMenu.java'), 'utf8');
pauseMenu = pauseMenu.replace('import net.minecraft.Util;', 'import net.minecraft.util.Util;');
files[path.join(javaDir, 'PauseMenu.java')] = pauseMenu;

// ═══════════════════════════════════════════════════════════
// WardrobeScreen.java (1.21.11)
// ═══════════════════════════════════════════════════════════
let wardrobe = fs.readFileSync(path.join(__dirname, '..', 'client', 'src', 'main', 'java', 'com', 'minelatino', 'cosmetics', 'client', 'WardrobeScreen.java'), 'utf8');
wardrobe = wardrobe.replace(
    'import net.minecraft.client.renderer.RenderType;',
    'import net.minecraft.client.renderer.rendertype.RenderTypes;'
);
// Replace RenderType::guiTextured with the appropriate pipeline reference
// In 1.21.11, blit takes RenderPipeline instead of RenderType
// The simplest fix: use the simple blit overload that doesn't need RenderType
wardrobe = wardrobe.replace(
    /g\.blit\(RenderType::guiTextured,texture,getX\(\)\+4,getY\(\)\+4,0,0,26,26,26,26\)/,
    'g.blit(texture,getX()+4,getY()+4,26,26,0,0,26,26,26,26)'
);
files[path.join(javaDir, 'WardrobeScreen.java')] = wardrobe;

// ═══════════════════════════════════════════════════════════
// CosmeticPreview.java (1.21.11)
// ═══════════════════════════════════════════════════════════
files[path.join(javaDir, 'CosmeticPreview.java')] = `package com.minelatino.cosmetics.client;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.EntityRenderer;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** A separate player entity: orbiting the preview never rotates/mutates the actual player.
 *  Ported for 1.21.11: uses submitEntityRenderState instead of InventoryScreen.renderEntityInInventory. */
public final class CosmeticPreview {
    static final ThreadLocal<Frame> FRAME = new ThreadLocal<>();
    static final class Frame {
        final int entityId;
        final List<EquipmentCache.EquippedItem> items;
        boolean layerVisited;
        Frame(int entityId, List<EquipmentCache.EquippedItem> items) { this.entityId=entityId; this.items=items; }
    }
    private RemotePlayer actor;
    private boolean layerAvailable;
    public boolean layerAvailable() { return layerAvailable; }

    public void render(GuiGraphics g, int x, int y, int w, int h, float orbit, float zoom,
                       List<EquipmentCache.EquippedItem> equipment) {
        Minecraft mc=Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (actor == null || actor.level() != mc.level) {
            actor=new RemotePlayer(mc.level,mc.player.getGameProfile()) {
                @Override public PlayerSkin getSkin() { return mc.player.getSkin(); }
                @Override public boolean isSpectator() { return false; }
                @Override public boolean isModelPartShown(PlayerModelPart part) { return mc.player.isModelPartShown(part); }
            };
            actor.yBodyRot=180; actor.yBodyRotO=180;
            actor.setYRot(180); actor.yRotO=180;
            actor.yHeadRot=180; actor.yHeadRotO=180;
        }
        actor.tickCount=mc.player.tickCount;
        // Skin texture: PlayerSkin.body().texturePath() returns Identifier in 1.21.11
        mc.getTextureManager().getTexture(actor.getSkin().body().texturePath()).setFilter(false, false);
        float scale=Math.min(h/3.2f,w/2.4f)*zoom;
        Frame frame=new Frame(actor.getId(),List.copyOf(equipment));
        Frame previous=FRAME.get();
        g.enableScissor(x,y,x+w,y+h);
        FRAME.set(frame);
        try {
            // 1.21.11: use GuiGraphics.submitEntityRenderState instead of InventoryScreen.renderEntityInInventory
            @SuppressWarnings("unchecked")
            EntityRenderer<? super RemotePlayer, ?> renderer = (EntityRenderer<? super RemotePlayer, ?>) mc.getEntityRenderDispatcher().getRenderer(actor);
            AvatarRenderState renderState = (AvatarRenderState) renderer.createRenderState(actor, mc.getFrameTime());
            float aspect = (float) w / h;
            g.submitEntityRenderState(renderState, scale,
                    new Vector3f(0, -0.1f, 0),
                    new Quaternionf().rotationZ((float)Math.PI).rotateY((float)Math.toRadians(orbit)),
                    new Quaternionf(),
                    x + w/2, y + h, h);
            layerAvailable=frame.layerVisited;
        } catch (Exception e) {
            // Preview rendering is non-critical
        } finally {
            if (previous == null) FRAME.remove(); else FRAME.set(previous);
            CosmeticRenderer.ENTITY_UUID_MAP.remove(actor.getId());
            g.disableScissor();
        }
    }
}
`;

// ═══════════════════════════════════════════════════════════
// PlayerRendererMixin.java (1.21.11)
// ═══════════════════════════════════════════════════════════
files[path.join(mixinDir, 'PlayerRendererMixin.java')] = `package com.minelatino.cosmetics.client.mixin;

import com.minelatino.cosmetics.client.CosmeticRenderer;
import com.minelatino.cosmetics.client.CosmeticsClient;
import com.minelatino.cosmetics.client.ResourceCache;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into AvatarRenderer (1.21.11 renamed from PlayerRenderer) to:
 * 1. Add the CosmeticRenderer layer after construction
 * 2. Capture player UUID during extractRenderState for cosmetic lookup
 */
@Mixin(AvatarRenderer.class)
public abstract class PlayerRendererMixin {

    @Inject(method = "<init>", at = @At("TAIL"))
    private void minelatino$addCosmeticLayer(CallbackInfo ci) {
        try {
            @SuppressWarnings("unchecked")
            LivingEntityRendererAccessor accessor = (LivingEntityRendererAccessor) this;
            ResourceCache cache = CosmeticsClient.instance().resources();
            accessor.invokeAddLayer(new CosmeticRenderer(
                    (net.minecraft.client.renderer.entity.RenderLayerParent<AvatarRenderState, PlayerModel>) this,
                    cache));
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger("MineLatino Cosmetics")
                    .error("Failed to add cosmetic layer", e);
        }
    }

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL"))
    private void minelatino$captureUuid(net.minecraft.world.entity.Avatar avatar, AvatarRenderState state, float partialTick, CallbackInfo ci) {
        if (avatar instanceof AbstractClientPlayer player) {
            CosmeticRenderer.putEntityUuid(state.id, player.getGameProfile().getId());
        }
    }
}
`;

// ═══════════════════════════════════════════════════════════
// LivingEntityRendererAccessor.java (1.21.11)
// ═══════════════════════════════════════════════════════════
files[path.join(mixinDir, 'LivingEntityRendererAccessor.java')] = `package com.minelatino.cosmetics.client.mixin;

import net.minecraft.client.renderer.entity.layers.RenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Accessor mixin to expose the protected addLayer method from LivingEntityRenderer.
 * Ported for 1.21.11: generic bounds updated to AvatarRenderState hierarchy.
 */
@Mixin(net.minecraft.client.renderer.entity.LivingEntityRenderer.class)
public interface LivingEntityRendererAccessor {
    @Invoker("addLayer")
    <S extends net.minecraft.client.renderer.entity.state.EntityRenderState> boolean invokeAddLayer(
            RenderLayer<S, ?> layer);
}
`;

// ═══════════════════════════════════════════════════════════
// PauseScreenMixin.java (1.21.11) - unchanged from 1.21.4
// ═══════════════════════════════════════════════════════════
let pauseMixin = fs.readFileSync(path.join(__dirname, '..', 'client', 'src', 'main', 'java', 'com', 'minelatino', 'cosmetics', 'client', 'mixin', 'PauseScreenMixin.java'), 'utf8');
files[path.join(mixinDir, 'PauseScreenMixin.java')] = pauseMixin;

// ═══════════════════════════════════════════════════════════
// MinecraftMixin.java (1.21.11) - unchanged from 1.21.4
// ═══════════════════════════════════════════════════════════
let mcMixin = fs.readFileSync(path.join(__dirname, '..', 'client', 'src', 'main', 'java', 'com', 'minelatino', 'cosmetics', 'client', 'mixin', 'MinecraftMixin.java'), 'utf8');
files[path.join(mixinDir, 'MinecraftMixin.java')] = mcMixin;

// ═══════════════════════════════════════════════════════════
// minelatino_cosmetics.mixins.json (1.21.11)
// ═══════════════════════════════════════════════════════════
files[path.join(resDir, 'minelatino_cosmetics.mixins.json')] = JSON.stringify({
  "required": true,
  "package": "com.minelatino.cosmetics.client.mixin",
  "compatibilityLevel": "JAVA_21",
  "client": ["PauseScreenMixin", "LivingEntityRendererAccessor", "PlayerRendererMixin", "MinecraftMixin"],
  "injectors": { "defaultRequire": 1 }
}, null, 2) + '\n';

// ═══════════════════════════════════════════════════════════
// fabric.mod.json (1.21.11)
// ═══════════════════════════════════════════════════════════
files[path.join(resDir, 'fabric.mod.json')] = `{
  "schemaVersion": 1,
  "id": "minelatino_cosmetics",
  "version": "\${version}",
  "name": "MineLatino Cosmetics",
  "description": "Base experimental de cosméticos y menú ESC de MineLatino.",
  "environment": "client",
  "license": "All Rights Reserved",
  "mixins": ["minelatino_cosmetics.mixins.json"],
  "depends": { "fabricloader": ">=\${fabricLoader}", "minecraft": "\${mcVersion}", "java": ">=21" }
}
`;

// Write all files
let count = 0;
for (const [filePath, content] of Object.entries(files)) {
    fs.writeFileSync(filePath, content, 'utf8');
    console.log('Created:', path.relative(path.join(__dirname, '..'), filePath));
    count++;
}
console.log(`\nDone: ${count} files created for 1.21.11 port`);
