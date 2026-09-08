package com.minelatino.cosmetics.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

class CosmeticsConfigTest {
    @TempDir Path root;

    private Path writeConfig(String json) throws Exception {
        Path file = root.resolve("config/minelatino-cosmetics/config.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
        return file;
    }

    @Test void createsDefaultConfigWhenMissing() {
        CosmeticsConfig config = CosmeticsConfig.read(root);
        assertEquals("https://minelatino-cosmetics-production.up.railway.app", config.backendUrl());
        assertTrue(Files.exists(root.resolve("config/minelatino-cosmetics/config.json")));
    }

    @Test void readsValidBackendUrl() throws Exception {
        writeConfig("{\"backendUrl\":\"https://cosmetics.minelatino.com\"}");
        assertEquals("https://cosmetics.minelatino.com", CosmeticsConfig.read(root).backendUrl());
    }

    @Test void rejectsCredentialsInUrl() throws Exception {
        writeConfig("{\"backendUrl\":\"https://user:pass@evil.test/api\"}");
        assertEquals("https://minelatino-cosmetics-production.up.railway.app", CosmeticsConfig.read(root).backendUrl());
    }

    @Test void rejectsNonHttpScheme() throws Exception {
        writeConfig("{\"backendUrl\":\"file:///etc/passwd\"}");
        assertEquals("https://minelatino-cosmetics-production.up.railway.app", CosmeticsConfig.read(root).backendUrl());
    }

    @Test void fallsBackOnInvalidJson() throws Exception {
        writeConfig("not json");
        assertEquals("https://minelatino-cosmetics-production.up.railway.app", CosmeticsConfig.read(root).backendUrl());
    }

    @Test void fallsBackOnOversizedFile() throws Exception {
        writeConfig(" ".repeat(4_097));
        assertEquals("https://minelatino-cosmetics-production.up.railway.app", CosmeticsConfig.read(root).backendUrl());
    }

    @Test void fallsBackOnNullBackendUrl() throws Exception {
        writeConfig("{\"backendUrl\":null}");
        assertEquals("https://minelatino-cosmetics-production.up.railway.app", CosmeticsConfig.read(root).backendUrl());
    }
}
