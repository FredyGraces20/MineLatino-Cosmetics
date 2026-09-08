package com.minelatino.cosmetics.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

class MenuConfigTest {
    @TempDir Path root;
    private Path config(String json) throws Exception {
        Path file = root.resolve("config/minelatino-cosmetics/menu.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
        return file;
    }
    @Test void createsDefaultConfigAndReloadsEdits() throws Exception {
        assertEquals(MenuConfig.defaults(), MenuConfig.read(root));
        config("{\"schemaVersion\":1,\"enabled\":false,\"buttons\":[],\"labels\":{}}");
        assertFalse(MenuConfig.read(root).enabled());
    }
    @Test void keepsInvalidFileIntactAndFallsBack() throws Exception {
        Path file = config("not json");
        assertEquals(MenuConfig.defaults(), MenuConfig.read(root));
        assertEquals("not json", Files.readString(file));
    }
    @Test void rejectsRemoteCodeAction() throws Exception {
        config("{\"schemaVersion\":1,\"enabled\":true,\"buttons\":[{\"label\":\"Run\",\"action\":\"EXECUTE\"}],\"labels\":{}}");
        assertEquals(MenuConfig.defaults(), MenuConfig.read(root));
    }
    @Test void rejectsOversizedConfiguration() throws Exception {
        config(" ".repeat(16_385));
        assertEquals(MenuConfig.defaults(), MenuConfig.read(root));
    }
    @Test void rejectsUnsupportedSchemaAndMissingFields() throws Exception {
        for (String invalid : new String[] { "null", "{}", "{\"schemaVersion\":2,\"enabled\":true,\"buttons\":[],\"labels\":{}}" }) {
            config(invalid);
            assertEquals(MenuConfig.defaults(), MenuConfig.read(root));
        }
    }
    @Test void acceptsKnownVanillaLabelWithoutHidingNavigation() throws Exception {
        config("{\"schemaVersion\":1,\"enabled\":true,\"buttons\":[],\"labels\":{\"menu.returnToGame\":\"Seguir jugando\"}}");
        assertEquals("Seguir jugando", MenuConfig.read(root).labels().get("menu.returnToGame"));
    }
}
