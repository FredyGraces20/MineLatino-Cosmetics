package com.minelatino.cosmetics.client;

import com.minelatino.cosmetics.core.MenuPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/** Shared UI, called by Fabric's mixin and Forge's native screen event. */
public final class PauseMenu {
    private PauseMenu() {}
    public static void install(Screen screen, Consumer<AbstractWidget> add) {
        if (screen.children().isEmpty()) return;
        Minecraft minecraft = Minecraft.getInstance();
        MenuConfig config = MenuConfig.read(minecraft.gameDirectory.toPath());
        if (!config.enabled()) return;
        // Phase 1: rename labels and collect buttons that need URL overrides
        record PendingReplace(Button button, String key) {}
        List<PendingReplace> toReplace = new ArrayList<>();
        for (var child : screen.children()) {
            if (child instanceof Button button && button.getMessage().getContents() instanceof TranslatableContents contents) {
                String key = contents.getKey();
                String replacement = config.labels().get(key);
                if (replacement != null) button.setMessage(Component.literal(replacement));
                if (config.vanillaUrls().containsKey(key)) toReplace.add(new PendingReplace(button, key));
            }
        }
        // Phase 2: for buttons with URL overrides, move vanilla button off-screen and add a replacement
        for (var pending : toReplace) {
            Button old = pending.button();
            String url = config.vanillaUrls().get(pending.key());
            if (url == null) continue;
            var uri = MenuPolicy.website(url);
            int x = old.getX(), y = old.getY(), w = old.getWidth(), h = old.getHeight();
            Component label = old.getMessage();
            old.setX(-0x4000);  // move vanilla button off-screen so it can't be clicked
            add.accept(Button.builder(label, btn -> {
                minecraft.setScreen(new ConfirmLinkScreen(confirmed -> {
                    if (confirmed) Util.getPlatform().openUri(uri);
                    minecraft.setScreen(screen);
                }, uri.toString(), true));
            }).bounds(x, y, w, h).build());
        }
        int index = 0;
        int count = config.buttons().size();
        int buttonWidth = count == 0 ? 150 : Math.min(150, (screen.width - 12 - (count - 1) * 4) / count);
        int startX = (screen.width - count * buttonWidth - Math.max(0, count - 1) * 4) / 2;
        for (MenuConfig.Entry entry : config.buttons()) {
            add.accept(Button.builder(Component.literal(entry.label()), button -> {
                if (entry.action() == MenuPolicy.Action.WARDROBE) {
                    minecraft.setScreen(new WardrobeScreen(screen));
                } else {
                    var uri = MenuPolicy.website(entry.url());
                    minecraft.setScreen(new ConfirmLinkScreen(confirmed -> {
                        if (confirmed) Util.getPlatform().openUri(uri);
                        minecraft.setScreen(screen);
                    }, uri.toString(), true));
                }
            }).bounds(startX + index++ * (buttonWidth + 4), 6, buttonWidth, 20).build());
        }
    }
}
