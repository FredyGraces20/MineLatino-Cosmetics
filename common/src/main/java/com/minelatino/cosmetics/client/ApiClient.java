package com.minelatino.cosmetics.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for the MineLatino Cosmetics backend.
 * Uses only java.net.http (no external dependencies). All responses are parsed
 * through Gson into simple DTOs, then converted to domain types.
 */
public final class ApiClient implements WardrobeController.Gateway {
    private static final Gson GSON = new Gson();
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private final String baseUrl;
    private final HttpClient http;

    public ApiClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    /** Returns the base URL this client connects to. */
    public String getBaseUrl() {
        return baseUrl;
    }

    // ── Auth ──────────────────────────────────────────────────────────

    public record ChallengeResponse(String challengeId, String serverId, long expiresAt) {}

    public ChallengeResponse challenge(String username) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("username", username);
        HttpResponse<String> response = post("/v1/auth/challenge", body.toString(), null);
        if (response.statusCode() != 201) throw new ApiException(response.statusCode(), "challenge");
        JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
        return new ChallengeResponse(
            json.get("challengeId").getAsString(),
            json.get("serverId").getAsString(),
            json.get("expiresAt").getAsLong()
        );
    }

    public record VerifyResponse(String token, long expiresAt, String uuid, String name) {}

    public VerifyResponse verify(String challengeId) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("challengeId", challengeId);
        HttpResponse<String> response = post("/v1/auth/verify", body.toString(), null);
        if (response.statusCode() != 200) throw new ApiException(response.statusCode(), "verify");
        JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
        return new VerifyResponse(
            json.get("token").getAsString(),
            json.get("expiresAt").getAsLong(),
            json.get("uuid").getAsString(),
            json.get("name").getAsString()
        );
    }

    public void logout(String token) throws Exception {
        HttpResponse<String> response = post("/v1/auth/logout", "{}", token);
        if (response.statusCode() != 200) throw new ApiException(response.statusCode(), "logout");
    }

    // ── Wardrobe ──────────────────────────────────────────────────────

    public record WardrobeResponse(String uuid, List<CosmeticItem> owned, List<EquippedEntry> equipped) {}
    public record CosmeticItem(String id, String name, String slot, String status, long revision) {}
    public record EquippedEntry(String slot, String cosmeticId) {}

    public WardrobeResponse wardrobe(String token) throws Exception {
        HttpResponse<String> response = get("/v1/cosmetics/me/wardrobe", token);
        if (response.statusCode() != 200) throw responseError(response, "wardrobe");
        return GSON.fromJson(response.body(), WardrobeResponse.class);
    }

    public record EquipResponse(List<EquippedEntry> equipped) {}

    public EquipResponse equip(String token, String slot, String cosmeticId) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("slot", slot);
        if (cosmeticId != null) body.addProperty("cosmeticId", cosmeticId);
        else body.add("cosmeticId", com.google.gson.JsonNull.INSTANCE);
        HttpResponse<String> response = put("/v1/cosmetics/me/equipment", body.toString(), token);
        if (response.statusCode() != 200) throw responseError(response, "equip");
        return GSON.fromJson(response.body(), EquipResponse.class);
    }

    // ── Appearance (batch) ────────────────────────────────────────────

    public record AppearanceEntry(String slot, String cosmeticId) {}
    public record PlayerAppearance(String uuid, List<AppearanceEntry> equipped) {}
    public record AppearanceResponse(List<PlayerAppearance> players) {}

    public AppearanceResponse appearance(List<String> uuids) throws Exception {
        String joined = String.join(",", uuids);
        HttpResponse<String> response = get("/v1/cosmetics/appearance?uuids=" + joined, null);
        if (response.statusCode() != 200) throw new ApiException(response.statusCode(), "appearance");
        return GSON.fromJson(response.body(), AppearanceResponse.class);
    }

    // ── Health ────────────────────────────────────────────────────────

    public record HealthResponse(boolean ok, boolean premiumEnabled, boolean offlineAuthEnabled, String stage) {}

    public HealthResponse health() throws Exception {
        HttpResponse<String> response = get("/health", null);
        if (response.statusCode() != 200) throw new ApiException(response.statusCode(), "health");
        return GSON.fromJson(response.body(), HealthResponse.class);
    }

    // ── Pause Menu Config ─────────────────────────────────────────────

    public record PauseMenuConfigResponse(int revision, MenuConfig config) {}

    public PauseMenuConfigResponse pauseMenuConfig() throws Exception {
        HttpResponse<String> response = get("/v1/client-config/pause-menu", null);
        if (response.statusCode() != 200) throw new ApiException(response.statusCode(), "pause-menu");
        return GSON.fromJson(response.body(), PauseMenuConfigResponse.class);
    }

    // ── Cosmetic Transforms ────────────────────────────────────────────

    public record TransformData(float[] translation, float[] rotation, float[] scale) {}
    public record CosmeticTransformsResponse(Map<String, Map<String, TransformData>> transforms) {}

    public CosmeticTransformsResponse cosmeticTransforms() throws Exception {
        HttpResponse<String> response = get("/v1/client-config/cosmetic-transforms", null);
        if (response.statusCode() != 200) throw new ApiException(response.statusCode(), "cosmetic-transforms");
        return GSON.fromJson(response.body(), CosmeticTransformsResponse.class);
    }

    // ── HTTP primitives ───────────────────────────────────────────────

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(TIMEOUT)
            .GET();
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return send(builder);
    }

    private HttpResponse<String> post(String path, String jsonBody, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return send(builder);
    }

    private HttpResponse<String> put(String path, String jsonBody, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(jsonBody));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return send(builder);
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        HttpRequest request=builder.build();
        String route=request.method()+" "+request.uri().getPath();
        long start=System.nanoTime();
        try {
            var response=http.send(request, HttpResponse.BodyHandlers.ofString());
            CosmeticsDiagnostics.event("HTTP",route+" status="+response.statusCode()+" ms="+(System.nanoTime()-start)/1_000_000);
            return response;
        } catch(Exception e) {
            CosmeticsDiagnostics.event("HTTP_FAILED",route+" "+CosmeticsDiagnostics.failure(e));
            throw e;
        }
    }

    public static final class ApiException extends Exception {
        public final int status;
        public ApiException(int status, String context) {
            super("API " + context + " failed: " + status);
            this.status = status;
        }
    }

    private static ApiException responseError(HttpResponse<String> response, String context) {
        try {
            String message = GSON.fromJson(response.body(), JsonObject.class).get("error").getAsString();
            return new ApiException(response.statusCode(), message.length() <= 180 ? message : context);
        } catch (RuntimeException ignored) {
            return new ApiException(response.statusCode(), context);
        }
    }
}
