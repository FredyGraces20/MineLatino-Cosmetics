package com.minelatino.cosmetics.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.minelatino.cosmetics.core.MenuPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.slf4j.LoggerFactory;

/** Local presentation settings only; testable without loading Minecraft classes. */
public record MenuConfig(int schemaVersion, boolean enabled, List<Entry> buttons, Map<String, String> labels, Map<String, String> vanillaUrls) {
    public record Entry(String label, MenuPolicy.Action action, String url) {}
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static MenuConfig defaults() {
        return new MenuConfig(1, true, List.of(new Entry("Cosméticos MineLatino", MenuPolicy.Action.WARDROBE, null)), Map.of(), Map.of());
    }
    public static MenuConfig read(Path gameDir) {
        Path path = gameDir.resolve("config/minelatino-cosmetics/menu.json");
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path.getParent());
                Files.writeString(path, GSON.toJson(defaults()));
                return defaults();
            }
            if (Files.size(path) > 16_384) throw new IllegalArgumentException("Menu config too large");
            MenuConfig config = GSON.fromJson(Files.readString(path), MenuConfig.class);
            if (config == null || config.schemaVersion != 1 || config.buttons == null || config.labels == null)
                throw new IllegalArgumentException("Invalid menu schema");
            // Backward compat: vanillaUrls may be null in older configs
            Map<String, String> urls = config.vanillaUrls != null ? config.vanillaUrls : Map.of();
            if (config.buttons.size() > MenuPolicy.MAX_BUTTONS || config.labels.size() > 9)
                throw new IllegalArgumentException("Menu config exceeds limits");
            for (Entry entry : config.buttons) {
                if (entry == null || entry.action == null) throw new IllegalArgumentException("Invalid action");
                MenuPolicy.label(entry.label);
                if (entry.action == MenuPolicy.Action.WEBSITE) MenuPolicy.website(entry.url);
            }
            config.labels.forEach((key, label) -> {
                if (!MenuPolicy.canRename(key)) throw new IllegalArgumentException("Unknown vanilla button");
                MenuPolicy.label(label);
            });
            urls.forEach((key, url) -> {
                if (!MenuPolicy.canRename(key)) throw new IllegalArgumentException("Unknown vanilla button for URL");
                MenuPolicy.website(url);
            });
            return new MenuConfig(config.schemaVersion, config.enabled, config.buttons, config.labels, urls);
        } catch (Exception error) {
            LoggerFactory.getLogger("MineLatino Cosmetics").warn("Invalid menu configuration; using defaults ({})", error.getClass().getSimpleName());
            return defaults();
        }
    }
}
