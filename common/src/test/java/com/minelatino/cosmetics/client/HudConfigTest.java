package com.minelatino.cosmetics.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class HudConfigTest {
    @TempDir Path temporaryDirectory;

    @Test
    void createsSafeDefaultsAndPersistsEdits() throws Exception {
        HudConfig config = HudConfig.get(temporaryDirectory);
        assertTrue(config.widget(HudConfig.FPS).enabled());
        assertFalse(config.widget(HudConfig.CPS).enabled());

        config.toggle(HudConfig.CPS);
        config.move(HudConfig.CPS, 84, 37);
        config.save();

        Path saved = temporaryDirectory.resolve("config/minelatino-cosmetics/hud.json");
        assertTrue(Files.isRegularFile(saved));
        String json = Files.readString(saved);
        assertTrue(json.contains("\"cps\""));
        assertTrue(json.contains("\"x\": 84"));
        assertTrue(config.widget(HudConfig.CPS).enabled());
        assertEquals(37, config.widget(HudConfig.CPS).y());
        assertTrue(config.widget(HudConfig.COMPASS).enabled());
        assertFalse(config.widget(HudConfig.INPUT).enabled());
    }

    @Test
    void clampsWidgetsToTheVisibleOriginAndCanReset() {
        HudConfig config = HudConfig.get(temporaryDirectory.resolve("second-instance"));
        config.move(HudConfig.FPS, -20, -30);
        assertEquals(0, config.widget(HudConfig.FPS).x());
        assertEquals(0, config.widget(HudConfig.FPS).y());

        config.toggle(HudConfig.FPS);
        assertFalse(config.widget(HudConfig.FPS).enabled());
        config.reset();
        assertTrue(config.widget(HudConfig.FPS).enabled());
        assertEquals(8, config.widget(HudConfig.FPS).x());
    }

    @Test
    void persistsOrientationAndBackgroundChoice() {
        HudConfig config = HudConfig.get(temporaryDirectory.resolve("appearance"));
        assertEquals(HudConfig.HORIZONTAL, config.widget(HudConfig.ARMOR).layout());
        int initialBackground = config.widget(HudConfig.ARMOR).background();

        config.toggleLayout(HudConfig.ARMOR);
        config.nextBackground(HudConfig.ARMOR);

        assertEquals(HudConfig.VERTICAL, config.widget(HudConfig.ARMOR).layout());
        assertTrue(config.widget(HudConfig.ARMOR).background() != initialBackground);
        assertFalse(HudConfig.backgroundName(config.widget(HudConfig.ARMOR).background()).isBlank());
    }

    @Test
    void controlsEachWidgetsBackgroundBorderOpacityAndScaleIndependently() {
        HudConfig config = HudConfig.get(temporaryDirectory.resolve("appearance-controls"));

        config.toggleBackground(HudConfig.ARMOR);
        config.toggleBorder(HudConfig.ARMOR);
        config.adjustOpacity(HudConfig.ARMOR, -30);
        config.adjustScale(HudConfig.ARMOR, 40);

        HudConfig.Widget armor = config.widget(HudConfig.ARMOR);
        assertFalse(armor.showBackground());
        assertFalse(armor.showBorder());
        assertEquals(70, armor.opacity());
        assertEquals(140, armor.scale());
        assertTrue(config.widget(HudConfig.FPS).showBackground());
        assertTrue(config.widget(HudConfig.FPS).showBorder());
        assertEquals(100, config.widget(HudConfig.FPS).scale());

        config.adjustOpacity(HudConfig.ARMOR, -500);
        config.adjustScale(HudConfig.ARMOR, 500);
        assertEquals(0, config.widget(HudConfig.ARMOR).opacity());
        assertEquals(200, config.widget(HudConfig.ARMOR).scale());
    }

    @Test
    void migratesVersionOneHudWithoutRemovingItsExistingPanel() throws Exception {
        Path game = temporaryDirectory.resolve("legacy-hud");
        Path saved = game.resolve("config/minelatino-cosmetics/hud.json");
        Files.createDirectories(saved.getParent());
        Files.writeString(saved, """
                {
                  "version": 1,
                  "widgets": {
                    "fps": { "enabled": true, "x": 20, "y": 30,
                             "layout": "horizontal", "background": -1206646752 }
                  }
                }
                """);

        HudConfig.Widget migrated = HudConfig.get(game).widget(HudConfig.FPS);
        assertTrue(migrated.showBackground());
        assertTrue(migrated.showBorder());
        assertEquals(100, migrated.opacity());
        assertEquals(100, migrated.scale());
        assertEquals(20, migrated.x());
    }
}
