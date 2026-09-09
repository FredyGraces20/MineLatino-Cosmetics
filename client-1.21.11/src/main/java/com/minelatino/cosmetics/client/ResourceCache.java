package com.minelatino.cosmetics.client;

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
    private final Map<String, PetAnimation> petAnimations = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<CachedResource>> pending = new ConcurrentHashMap<>();
    private final Map<String, Long> nextRefresh = new ConcurrentHashMap<>();
    private volatile long generation;
    private final Map<String, String> errors = new ConcurrentHashMap<>();

    public String error(String id) { return errors.get(id); }
    public boolean isLoading(String id) { return pending.containsKey(id); }
    public void retry(String id) { nextRefresh.remove(id); errors.remove(id); }

    public record Material(Identifier texture, TextureAnimation animation) {}
    public record CachedResource(Identifier texture, CosmeticModel model, Map<String, Material> materials, PetAnimation petAnimation) {}
    private CachedResource cached(String id, Identifier texture, CosmeticModel model) {
        return new CachedResource(texture, model, materials.getOrDefault(id, Map.of()), petAnimations.getOrDefault(id, PetAnimation.none()));
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
    private record DownloadData(byte[] texturePng, CosmeticModel model, Map<String, TextureData> materials, PetAnimation petAnimation) {}

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
        PetAnimation petAnimation=PetAnimation.none();
        try {
            String config=new String(download(cosmeticId,"type=animation-config&"+revision),StandardCharsets.UTF_8);
            var parsed=com.google.gson.JsonParser.parseString(config).getAsJsonObject();
            if (parsed.has("hasFile") && parsed.get("hasFile").getAsBoolean()) {
                String selected=parsed.has("animation") && !parsed.get("animation").isJsonNull() ? parsed.get("animation").getAsString() : null;
                petAnimation=PetAnimation.parse(new String(download(cosmeticId,"type=animation&"+revision),StandardCharsets.UTF_8),selected);
            }
        } catch (IOException ignored) { /* Static cosmetics and older services remain compatible. */ }
        return new DownloadData(textureData, model, named, petAnimation);
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
        petAnimations.put(cosmeticId,data.petAnimation());
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
        petAnimations.clear();
        pending.clear();
        nextRefresh.clear();
        errors.clear();
    }
}
