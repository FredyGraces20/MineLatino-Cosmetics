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
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Properties;
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
    private record DownloadData(byte[] texturePng, CosmeticModel model, String modelJson,
                                Map<String, TextureData> materials, PetAnimation petAnimation,
                                String petAnimationJson, String petAnimationName, String version) {}
    private record DiskBundle(String version, DownloadData data) {}

    private static byte[] boundedRead(Path path) throws IOException {
        long size = Files.size(path);
        if (size <= 0 || size > 2 * 1024 * 1024) throw new IOException("Invalid cached resource size");
        return Files.readAllBytes(path);
    }

    private DiskBundle readDisk(String id) {
        Path dir = cacheDir.resolve(id);
        try {
            Properties meta = new Properties();
            try (var input = Files.newInputStream(dir.resolve("metadata.properties"))) { meta.load(input); }
            String version = meta.getProperty("version", "");
            if (!version.matches("[a-f0-9]{12}")) throw new IOException("Invalid cache version");
            byte[] primary = boundedRead(dir.resolve("texture.png"));
            String modelJson = Files.exists(dir.resolve("model.json")) ? Files.readString(dir.resolve("model.json"), StandardCharsets.UTF_8) : null;
            if (modelJson != null && modelJson.length() > 2 * 1024 * 1024) throw new IOException("Cached model too large");
            CosmeticModel model = modelJson == null ? CosmeticModel.empty() : CosmeticModel.parse(modelJson);
            var named = new java.util.LinkedHashMap<String, TextureData>();
            String names = meta.getProperty("materials", "");
            if (!names.isEmpty()) for (String name : names.split(",")) {
                if (!name.matches("[a-z0-9_]{1,32}")) throw new IOException("Invalid cached material name");
                byte[] png = boundedRead(dir.resolve("material-" + name + ".png"));
                Path mcmeta = dir.resolve("material-" + name + ".mcmeta");
                named.put(name, new TextureData(png, Files.exists(mcmeta) ? Files.readString(mcmeta, StandardCharsets.UTF_8) : null));
            }
            String animationJson = Files.exists(dir.resolve("pet-animation.json")) ? Files.readString(dir.resolve("pet-animation.json"), StandardCharsets.UTF_8) : null;
            String animationName = meta.getProperty("petAnimationName", "");
            PetAnimation animation = animationJson == null ? PetAnimation.none() : PetAnimation.parse(animationJson, animationName.isEmpty() ? null : animationName);
            return new DiskBundle(version, new DownloadData(primary, model, modelJson, Map.copyOf(named), animation,
                    animationJson, animationName.isEmpty() ? null : animationName, version));
        } catch (Exception e) { return null; }
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    private void writeDisk(String id, DownloadData data) {
        Path target = cacheDir.resolve(id), temporary = cacheDir.resolve(id + ".tmp-" + System.nanoTime());
        try {
            Files.createDirectories(temporary);
            Files.write(temporary.resolve("texture.png"), data.texturePng());
            if (data.modelJson() != null) Files.writeString(temporary.resolve("model.json"), data.modelJson(), StandardCharsets.UTF_8);
            for (var entry : data.materials().entrySet()) {
                Files.write(temporary.resolve("material-" + entry.getKey() + ".png"), entry.getValue().png());
                if (entry.getValue().mcmeta() != null) Files.writeString(temporary.resolve("material-" + entry.getKey() + ".mcmeta"), entry.getValue().mcmeta(), StandardCharsets.UTF_8);
            }
            if (data.petAnimationJson() != null) Files.writeString(temporary.resolve("pet-animation.json"), data.petAnimationJson(), StandardCharsets.UTF_8);
            Properties meta = new Properties();
            meta.setProperty("version", data.version());
            meta.setProperty("materials", String.join(",", data.materials().keySet()));
            meta.setProperty("petAnimationName", data.petAnimationName() == null ? "" : data.petAnimationName());
            try (var output = Files.newOutputStream(temporary.resolve("metadata.properties"))) { meta.store(output, null); }
            deleteTree(target);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (IOException ignored) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) {
            LOG.warn("Could not persist cosmetic cache for {}", id, e);
            deleteTree(temporary);
        }
    }

    private byte[] download(String id, String query) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/resources/" + id + "?" + query))
                .timeout(TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200 || response.body().length > 2*1024*1024)
            throw new IOException("Missing/invalid cosmetic resource " + query + " HTTP " + response.statusCode());
        return response.body();
    }

    private DownloadData downloadBoth(String cosmeticId) throws Exception {
        DiskBundle disk = readDisk(cosmeticId);
        HttpRequest.Builder manifestRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/resources/" + cosmeticId + "?type=manifest"))
                .timeout(TIMEOUT).header("Cache-Control", "no-cache").header("Accept", "application/json").GET();
        if (disk != null) manifestRequest.header("If-None-Match", "\"" + disk.version() + "\"");
        HttpResponse<String> manifestResponse;
        try { manifestResponse = http.send(manifestRequest.build(), HttpResponse.BodyHandlers.ofString()); }
        catch (Exception e) {
            if (disk != null) {
                CosmeticsDiagnostics.event("RESOURCE_DISK_STALE", CosmeticsDiagnostics.id(cosmeticId));
                return disk.data();
            }
            throw e;
        }
        if (manifestResponse.statusCode() == 304 && disk != null) {
            CosmeticsDiagnostics.event("RESOURCE_DISK_HIT", CosmeticsDiagnostics.id(cosmeticId) + " version=" + disk.version());
            return disk.data();
        }
        if (manifestResponse.statusCode() != 200) {
            if (disk != null) return disk.data();
            throw new IOException("Resource manifest unavailable: HTTP " + manifestResponse.statusCode());
        }
        var manifest = com.google.gson.JsonParser.parseString(manifestResponse.body()).getAsJsonObject();
        String version = manifest.has("resourceVersion") ? manifest.get("resourceVersion").getAsString() : "";
        if (!version.matches("[a-f0-9]{12}")) throw new IOException("Resource manifest has no valid version");
        var files = new java.util.HashMap<String, Boolean>();
        for (var entry : manifest.getAsJsonArray("files")) {
            var file = entry.getAsJsonObject();
            String name = file.get("name").getAsString();
            if (name.matches("[a-z0-9_]{1,32}")) files.put(name, file.get("hasMcmeta").getAsBoolean());
        }
        byte[] textureData = null;
        String revision = "v=" + version;
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
        String modelJson = null;
        HttpRequest modelReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/resources/" + cosmeticId + "?type=model&" + revision))
                .timeout(TIMEOUT).header("Cache-Control", "no-cache").header("Accept", "application/json").GET().build();
        HttpResponse<String> modelRes = http.send(modelReq, HttpResponse.BodyHandlers.ofString());
        CosmeticsDiagnostics.event("RESOURCE_MODEL",CosmeticsDiagnostics.id(cosmeticId)+" status="+modelRes.statusCode());
        if (modelRes.statusCode() == 200) {
            String json = modelRes.body();
            if (json == null || json.length() > 2 * 1024 * 1024) throw new IOException("Invalid model size");
            model = CosmeticModel.parse(json);
            modelJson = json;
        } else if (modelRes.statusCode() != 404) {
            throw new IOException("Model unavailable: HTTP " + modelRes.statusCode());
        }
        var named = new java.util.LinkedHashMap<String, TextureData>();
        var names = model.quads.stream().map(CosmeticModel.Quad::texture).distinct().toList();
        if (names.size() > 32) throw new IOException("Too many model textures");
        if (!names.isEmpty()) {
            for (String name : names) {
                if (files.containsKey(name)) {
                    byte[] png = download(cosmeticId, "file="+name+"&"+revision);
                    String meta = files.get(name) ? new String(download(cosmeticId,"file="+name+"&type=mcmeta&"+revision), StandardCharsets.UTF_8) : null;
                    named.put(name, new TextureData(png, meta));
                } else if (names.size() == 1) named.put(name, new TextureData(textureData,null));
                else throw new IOException("Missing texture file: " + name + ". Upload PNG with this name.");
            }
        }
        PetAnimation petAnimation=PetAnimation.none();
        String petAnimationJson = null, petAnimationName = null;
        try {
            String config=new String(download(cosmeticId,"type=animation-config&"+revision),StandardCharsets.UTF_8);
            var parsed=com.google.gson.JsonParser.parseString(config).getAsJsonObject();
            if (parsed.has("hasFile") && parsed.get("hasFile").getAsBoolean()) {
                String selected=parsed.has("animation") && !parsed.get("animation").isJsonNull() ? parsed.get("animation").getAsString() : null;
                petAnimationJson = new String(download(cosmeticId,"type=animation&"+revision),StandardCharsets.UTF_8);
                petAnimationName = selected;
                petAnimation=PetAnimation.parse(petAnimationJson,selected);
            }
        } catch (IOException ignored) { /* Static cosmetics and older services remain compatible. */ }
        DownloadData result = new DownloadData(textureData, model, modelJson, Map.copyOf(named), petAnimation,
                petAnimationJson, petAnimationName, version);
        writeDisk(cosmeticId, result);
        return result;
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
