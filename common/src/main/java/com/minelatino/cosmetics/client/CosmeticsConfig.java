package com.minelatino.cosmetics.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.LoggerFactory;

/**
 * Connection settings only; read from config/minelatino-cosmetics/config.json.
 * Invalid values fall back to safe defaults without overwriting the user file.
 */
public record CosmeticsConfig(String backendUrl, String accountToken, String accountId, long accountExpiresAt) {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DEFAULT_URL = "https://minelatino-cosmetics-production.up.railway.app";

    public static CosmeticsConfig defaults() {
        return new CosmeticsConfig(DEFAULT_URL, null, null, 0);
    }

    public static CosmeticsConfig read(Path gameDir) {
        Path path = gameDir.resolve("config/minelatino-cosmetics/config.json");
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path.getParent());
                Files.writeString(path, GSON.toJson(defaults()));
                return defaults();
            }
            if (Files.size(path) > 4_096) throw new IllegalArgumentException("Config too large");
            CosmeticsConfig config = GSON.fromJson(Files.readString(path), CosmeticsConfig.class);
            if (config == null || config.backendUrl == null) throw new IllegalArgumentException("Null backend URL");
            validate(config.backendUrl);
            if (!config.hasAccountSession()) return new CosmeticsConfig(config.backendUrl, null, null, 0);
            return config;
        } catch (Exception error) {
            LoggerFactory.getLogger("MineLatino Cosmetics").warn("Invalid config; using defaults ({})", error.getClass().getSimpleName());
            return defaults();
        }
    }

    private static void validate(String url) {
        java.net.URI uri = java.net.URI.create(url);
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")))
            throw new IllegalArgumentException("Backend URL must use http or https");
        if (uri.getHost() == null || uri.getHost().isBlank())
            throw new IllegalArgumentException("Backend URL must have a host");
        if (scheme.equalsIgnoreCase("http") && !isLoopback(uri.getHost()))
            throw new IllegalArgumentException("Plain HTTP is only allowed for local development");
        if (uri.getUserInfo() != null)
            throw new IllegalArgumentException("Backend URL must not contain credentials");
        if (url.length() > 500)
            throw new IllegalArgumentException("Backend URL too long");
    }

    public boolean hasAccountSession() {
        return accountToken != null && accountToken.length() >= 32 && accountToken.length() <= 256
                && accountId != null && accountId.matches("[a-f0-9]{32}")
                && accountExpiresAt > System.currentTimeMillis();
    }

    private static boolean isLoopback(String host) {
        return host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1")
                || host.equals("0:0:0:0:0:0:0:1");
    }
}
