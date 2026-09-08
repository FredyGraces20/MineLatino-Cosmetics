package com.minelatino.cosmetics.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
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
 * Textures are registered as Minecraft DynamicTextures.
 * Models are parsed into CosmeticModel objects for rendering.
 */
public final class ResourceCache {
    private static final Logger LOG = LoggerFactory.getLogger("MineLatino Cosmetics");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String baseUrl;
    private final HttpClient http;
    private final Path cacheDir;
    private final Map<String, ResourceLocation> textures = new ConcurrentHashMap<>();
    private final Map<String, CosmeticModel> models = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<CachedResource>> pending = new ConcurrentHashMap<>();
    private final Map<String, Long> nextRefresh = new ConcurrentHashMap<>();
    private volatile long generation;
    private final Map<String, String> errors = new ConcurrentHashMap<>();

    public String error(String id) { return errors.get(id); }
    public boolean isLoading(String id) { return pending.containsKey(id); }
    public void retry(String id) { nextRefresh.remove(id); errors.remove(id); }

    public record CachedResource(ResourceLocation texture, CosmeticModel model) {}

    public ResourceCache(String baseUrl, Path cacheDir) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.cacheDir = cacheDir;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        try { Files.createDirectories(cacheDir); } catch (IOException e) { LOG.warn("Failed to create cache dir", e); }
    }

    /**
     * Returns the cached resource (texture + optional model) for a cosmetic.
     * Returns null if not yet loaded. Starts async download if not cached.
     */
    public CachedResource getOrDownload(String cosmeticId) {
        if (cosmeticId == null || !cosmeticId.matches("[a-z0-9_-]{1,128}")) return null;
        ResourceLocation tex = textures.get(cosmeticId);
        CosmeticModel model = models.get(cosmeticId);
        long now = System.currentTimeMillis();
        if (now < nextRefresh.getOrDefault(cosmeticId, 0L))
            return tex == null ? null : new CachedResource(tex, model);

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
                return res != null ? res : (tex == null ? null : new CachedResource(tex, model));
            } catch (Exception e) {
                pending.remove(cosmeticId);
                nextRefresh.put(cosmeticId, now + 15_000);
                LOG.warn("Failed to load cosmetic {}", cosmeticId, e);
                errors.put(cosmeticId, "No se pudo preparar la textura en el juego");
            }
        }
        // Return partial result if available
        if (tex != null) return new CachedResource(tex, model);
        return null;
    }

    /** Returns just the texture ResourceLocation, or null. */
    public ResourceLocation getOrDownloadTexture(String cosmeticId) {
        CachedResource res = getOrDownload(cosmeticId);
        return res != null ? res.texture() : null;
    }

    /** Returns the parsed model, or null if not loaded. */
    public CosmeticModel getModel(String cosmeticId) {
        return models.get(cosmeticId);
    }

    /** Returns just the texture location (already cached), or null. */
    public ResourceLocation get(String cosmeticId) {
        return textures.get(cosmeticId);
    }

    // ── Download (background thread) ──────────────────────────────────────

    private record DownloadData(byte[] texturePng, CosmeticModel model) {}

    private DownloadData downloadBoth(String cosmeticId) throws Exception {
        // Download texture PNG
        byte[] textureData = null;
        // Existing server URLs are immutable-cached: use a fresh key for each revalidation.
        String revision = "refresh=" + System.currentTimeMillis();
        HttpRequest texReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/resources/" + cosmeticId + "?" + revision))
                .timeout(TIMEOUT).header("Cache-Control", "no-cache").header("Accept", "image/png").GET().build();
        HttpResponse<byte[]> texRes = http.send(texReq, HttpResponse.BodyHandlers.ofByteArray());
        CosmeticsDiagnostics.event("RESOURCE_PNG",CosmeticsDiagnostics.id(cosmeticId)+" status="+texRes.statusCode());
        if (texRes.statusCode() == 200) {
            byte[] data = texRes.body();
            if (data.length >= 8 && data[0] == (byte) 0x89 && data[1] == (byte) 0x50) {
                textureData = data;
            }
        }

        if (textureData == null) throw new IOException("Texture unavailable: HTTP " + texRes.statusCode());
        if (textureData.length > 2 * 1024 * 1024) throw new IOException("Texture exceeds 2 MiB");
        // A 404 explicitly denotes a PNG-only asset. Errors must not replace a good model.
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

        return new DownloadData(textureData, model);
    }

    // ── Registration (main thread) ────────────────────────────────────────

    private CachedResource registerOnMainThread(String cosmeticId, DownloadData data) {
        ResourceLocation texLoc = null;
        CosmeticModel model = data.model();

        if (data.texturePng() != null) {
            try {
                texLoc = ResourceLocation.fromNamespaceAndPath("minelatino_cosmetics", "cosmetics/" + cosmeticId);
                NativeImage image = NativeImage.read(new ByteArrayInputStream(data.texturePng()));
                if (image.getWidth() > 4096 || image.getHeight() > 4096) {
                    image.close();
                    throw new IOException("Texture dimensions exceed 4096");
                }
                DynamicTexture texture = new DynamicTexture(image);
                Minecraft.getInstance().getTextureManager().register(texLoc, texture);
                // Cosmetic textures are pixel art (Blockbench/Minecraft UVs), not
                // photographs. Linear filtering makes the preview look blurred.
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
        models.put(cosmeticId, model);
        CosmeticsDiagnostics.event("RESOURCE_READY",CosmeticsDiagnostics.id(cosmeticId)+" elements="+model.elements.size()+" quads="+model.quads.size());
        return new CachedResource(texLoc, model);
    }

    public void clear() {
        generation++;
        var oldTextures = java.util.List.copyOf(textures.values());
        Minecraft.getInstance().execute(() -> oldTextures.forEach(
                id -> Minecraft.getInstance().getTextureManager().release(id)));
        textures.clear();
        models.clear();
        pending.clear();
        nextRefresh.clear();
        errors.clear();
    }
}
