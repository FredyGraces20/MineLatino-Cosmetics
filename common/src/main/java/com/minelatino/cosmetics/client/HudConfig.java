package com.minelatino.cosmetics.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Local, per-instance HUD layout. It never leaves the player's computer. */
public final class HudConfig {
    private static final int DATA_VERSION = 2;
    public static final String FPS = "fps";
    public static final String COORDINATES = "coordinates";
    public static final String CPS = "cps";
    public static final String ARMOR = "armor";
    public static final String EFFECTS = "effects";
    public static final String COMPASS = "compass";
    public static final String INPUT = "input";
    public static final String HORIZONTAL = "horizontal";
    public static final String VERTICAL = "vertical";
    public static final java.util.List<String> ORDER = java.util.List.of(
            FPS, COORDINATES, CPS, ARMOR, EFFECTS, COMPASS, INPUT);
    private static final int[] BACKGROUNDS = {
            0xB8141820, 0xE623252B, 0xB8123440, 0xB8281B3D, 0xB815263F, 0x50101218
    };
    private static final String[] BACKGROUND_NAMES = {
            "Glass", "Carbono", "Cian", "Morado", "Azul", "Sutil"
    };

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static HudConfig current;

    public record Widget(boolean enabled, int x, int y, String layout, int background,
                         boolean showBackground, boolean showBorder, int opacity, int scale) {}
    private static final class Data {
        int version = DATA_VERSION;
        Map<String, Widget> widgets = defaults();
    }

    private final Path path;
    private Data data;

    private HudConfig(Path path, Data data) {
        this.path = path;
        this.data = data;
        mergeDefaults(data.version < DATA_VERSION);
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
        data.widgets.put(id, widget(!old.enabled(), old.x(), old.y(), old.layout(), old.background(),
                old.showBackground(), old.showBorder(), old.opacity(), old.scale()));
        save();
    }

    public synchronized void move(String id, int x, int y) {
        Widget old = widget(id);
        data.widgets.put(id, widget(old.enabled(), Math.max(0, x), Math.max(0, y), old.layout(), old.background(),
                old.showBackground(), old.showBorder(), old.opacity(), old.scale()));
    }

    public synchronized void toggleLayout(String id) {
        Widget old = widget(id);
        String next = VERTICAL.equals(old.layout()) ? HORIZONTAL : VERTICAL;
        data.widgets.put(id, widget(old.enabled(), old.x(), old.y(), next, old.background(),
                old.showBackground(), old.showBorder(), old.opacity(), old.scale()));
        save();
    }

    public synchronized void nextBackground(String id) {
        Widget old = widget(id);
        int index = 0;
        for (int i = 0; i < BACKGROUNDS.length; i++) if (BACKGROUNDS[i] == old.background()) index = i;
        int next = BACKGROUNDS[(index + 1) % BACKGROUNDS.length];
        data.widgets.put(id, widget(old.enabled(), old.x(), old.y(), old.layout(), next,
                old.showBackground(), old.showBorder(), old.opacity(), old.scale()));
        save();
    }

    public synchronized void toggleBackground(String id) {
        Widget old = widget(id);
        data.widgets.put(id, widget(old.enabled(), old.x(), old.y(), old.layout(), old.background(),
                !old.showBackground(), old.showBorder(), old.opacity(), old.scale()));
        save();
    }

    public synchronized void toggleBorder(String id) {
        Widget old = widget(id);
        data.widgets.put(id, widget(old.enabled(), old.x(), old.y(), old.layout(), old.background(),
                old.showBackground(), !old.showBorder(), old.opacity(), old.scale()));
        save();
    }

    public synchronized void adjustOpacity(String id, int delta) {
        Widget old = widget(id);
        int next = clamp(old.opacity() + delta, 0, 100);
        data.widgets.put(id, widget(old.enabled(), old.x(), old.y(), old.layout(), old.background(),
                old.showBackground(), old.showBorder(), next, old.scale()));
        save();
    }

    public synchronized void adjustScale(String id, int delta) {
        Widget old = widget(id);
        int next = clamp(old.scale() + delta, 50, 200);
        data.widgets.put(id, widget(old.enabled(), old.x(), old.y(), old.layout(), old.background(),
                old.showBackground(), old.showBorder(), old.opacity(), next));
        save();
    }

    public static boolean supportsLayout(String id) {
        return COORDINATES.equals(id) || CPS.equals(id) || ARMOR.equals(id) || EFFECTS.equals(id);
    }

    public static String backgroundName(int background) {
        for (int i = 0; i < BACKGROUNDS.length; i++) {
            if (BACKGROUNDS[i] == background) return BACKGROUND_NAMES[i];
        }
        return BACKGROUND_NAMES[0];
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

    private void mergeDefaults(boolean legacy) {
        if (data.widgets == null) data.widgets = new LinkedHashMap<>();
        Map<String, Widget> defaults = defaults();
        defaults.forEach(data.widgets::putIfAbsent);
        data.widgets.replaceAll((id, value) -> normalize(value, defaults.getOrDefault(id,
                defaultWidget(true, 8, 8, HORIZONTAL)), legacy));
        data.version = DATA_VERSION;
    }

    private static Widget normalize(Widget value, Widget fallback, boolean legacy) {
        if (value == null) return fallback;
        String layout = HORIZONTAL.equals(value.layout()) || VERTICAL.equals(value.layout())
                ? value.layout() : fallback.layout();
        int background = value.background() == 0 ? fallback.background() : value.background();
        boolean showBackground = legacy || value.showBackground();
        boolean showBorder = legacy || value.showBorder();
        int opacity = legacy ? 100 : clamp(value.opacity(), 0, 100);
        int scale = value.scale() == 0 ? 100 : clamp(value.scale(), 50, 200);
        return new Widget(value.enabled(), Math.max(0, value.x()), Math.max(0, value.y()), layout, background,
                showBackground, showBorder, opacity, scale);
    }

    private static Map<String, Widget> defaults() {
        Map<String, Widget> result = new LinkedHashMap<>();
        result.put(FPS, defaultWidget(true, 8, 8, HORIZONTAL));
        result.put(COORDINATES, defaultWidget(true, 8, 34, HORIZONTAL));
        result.put(CPS, defaultWidget(false, 8, 60, HORIZONTAL));
        result.put(ARMOR, defaultWidget(true, 8, 88, HORIZONTAL));
        result.put(EFFECTS, defaultWidget(true, 8, 132, VERTICAL));
        result.put(COMPASS, defaultWidget(true, 180, 8, HORIZONTAL));
        result.put(INPUT, defaultWidget(false, 8, 176, HORIZONTAL));
        return result;
    }

    private static Widget defaultWidget(boolean enabled, int x, int y, String layout) {
        return new Widget(enabled, x, y, layout, BACKGROUNDS[0], true, true, 100, 100);
    }

    private static Widget widget(boolean enabled, int x, int y, String layout, int background,
                                 boolean showBackground, boolean showBorder, int opacity, int scale) {
        return new Widget(enabled, x, y, layout, background, showBackground, showBorder, opacity, scale);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
