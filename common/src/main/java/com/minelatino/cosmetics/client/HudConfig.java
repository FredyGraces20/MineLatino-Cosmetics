package com.minelatino.cosmetics.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Local, per-instance HUD layout. It never leaves the player's computer. */
public final class HudConfig {
    public static final String FPS = "fps";
    public static final String COORDINATES = "coordinates";
    public static final String CPS = "cps";
    public static final String ARMOR = "armor";
    public static final String EFFECTS = "effects";
    public static final java.util.List<String> ORDER = java.util.List.of(FPS, COORDINATES, CPS, ARMOR, EFFECTS);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static HudConfig current;

    public record Widget(boolean enabled, int x, int y) {}
    private static final class Data {
        int version = 1;
        Map<String, Widget> widgets = defaults();
    }

    private final Path path;
    private Data data;

    private HudConfig(Path path, Data data) {
        this.path = path;
        this.data = data;
        mergeDefaults();
    }

    public static synchronized HudConfig get(Path gameDirectory) {
        Path path = gameDirectory.resolve("config/minelatino-cosmetics/hud.json");
        if (current != null && current.path.equals(path)) return current;
        Data loaded = null;
        try {
            if (Files.isRegularFile(path)) loaded = GSON.fromJson(Files.readString(path), Data.class);
        } catch (Exception ignored) {}
        current = new HudConfig(path, loaded == null ? new Data() : loaded);
        current.save();
        return current;
    }

    public synchronized Widget widget(String id) {
        Widget value = data.widgets.get(id);
        return value == null ? defaults().get(id) : value;
    }

    public synchronized void toggle(String id) {
        Widget old = widget(id);
        data.widgets.put(id, new Widget(!old.enabled(), old.x(), old.y()));
        save();
    }

    public synchronized void move(String id, int x, int y) {
        Widget old = widget(id);
        data.widgets.put(id, new Widget(old.enabled(), Math.max(0, x), Math.max(0, y)));
    }

    public synchronized void reset() {
        data = new Data();
        save();
    }

    public synchronized void save() {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(data));
        } catch (Exception ignored) {}
    }

    private void mergeDefaults() {
        if (data.widgets == null) data.widgets = new LinkedHashMap<>();
        defaults().forEach(data.widgets::putIfAbsent);
    }

    private static Map<String, Widget> defaults() {
        Map<String, Widget> result = new LinkedHashMap<>();
        result.put(FPS, new Widget(true, 8, 8));
        result.put(COORDINATES, new Widget(true, 8, 30));
        result.put(CPS, new Widget(false, 8, 52));
        result.put(ARMOR, new Widget(true, 8, 76));
        result.put(EFFECTS, new Widget(true, 8, 116));
        return result;
    }
}
