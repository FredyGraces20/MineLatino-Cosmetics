package com.minelatino.cosmetics.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/** Compact, font-independent icons for the tighter Minecraft 26.2 pause menu. */
final class PauseMenuIconButton extends AbstractButton {
    enum Icon { CART, DISCORD, GEAR }

    private final Runnable action;
    private final Icon icon;

    PauseMenuIconButton(int x, int y, int width, int height, String label, Icon icon, Runnable action) {
        super(x, y, width, height, Component.literal(label));
        this.action = action;
        this.icon = icon;
        setTooltip(Tooltip.create(Component.literal(label)));
    }

    @Override public void onPress(net.minecraft.client.input.InputWithModifiers input) {
        action.run();
    }

    @Override protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }

    @Override protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int x = getX(), y = getY(), width = getWidth(), height = getHeight();
        int border = isHoveredOrFocused() ? 0xFFE8A32E : 0xFF4A4D4D;
        int fill = isHoveredOrFocused() ? 0xFF40372A : 0xFF2B2D2D;
        panel(graphics, x, y, width, height, border, fill);

        int color = active ? (isHoveredOrFocused() ? 0xFFFFC45C : 0xFFEDE9E2) : 0xFF77736D;
        int centerX = x + width / 2;
        int centerY = y + height / 2;
        switch (icon) {
            case CART -> cart(graphics, centerX, centerY, color);
            case DISCORD -> discord(graphics, centerX, centerY, color, fill);
            case GEAR -> gear(graphics, centerX, centerY, color, fill);
        }
    }

    private static void cart(GuiGraphicsExtractor g, int cx, int cy, int color) {
        // Handle, basket and two wheels in a crisp 12x10 pixel footprint.
        g.fill(cx - 7, cy - 5, cx - 4, cy - 3, color);
        g.fill(cx - 5, cy - 4, cx - 4, cy + 2, color);
        g.fill(cx - 4, cy - 3, cx + 6, cy + 2, color);
        g.fill(cx - 3, cy + 2, cx + 5, cy + 3, color);
        g.fill(cx - 3, cy + 4, cx - 1, cy + 6, color);
        g.fill(cx + 3, cy + 4, cx + 5, cy + 6, color);
        g.fill(cx - 2, cy - 2, cx - 1, cy + 1, fillAlpha(color, 0x70));
        g.fill(cx + 2, cy - 2, cx + 3, cy + 1, fillAlpha(color, 0x70));
    }

    private static void discord(GuiGraphicsExtractor g, int cx, int cy, int color, int background) {
        // Recognisable Discord controller silhouette with eyes and lower smile.
        g.fill(cx - 5, cy - 4, cx + 5, cy - 3, color);
        g.fill(cx - 7, cy - 3, cx + 7, cy + 3, color);
        g.fill(cx - 6, cy + 3, cx - 3, cy + 5, color);
        g.fill(cx + 3, cy + 3, cx + 6, cy + 5, color);
        g.fill(cx - 3, cy - 1, cx - 1, cy + 1, background);
        g.fill(cx + 1, cy - 1, cx + 3, cy + 1, background);
        g.fill(cx - 2, cy + 2, cx + 2, cy + 3, background);
    }

    private static void gear(GuiGraphicsExtractor g, int cx, int cy, int color, int background) {
        // Eight teeth around a compact ring; the hollow center keeps it legible.
        g.fill(cx - 2, cy - 6, cx + 2, cy + 6, color);
        g.fill(cx - 6, cy - 2, cx + 6, cy + 2, color);
        g.fill(cx - 4, cy - 4, cx + 4, cy + 4, color);
        g.fill(cx - 5, cy - 5, cx - 2, cy - 2, color);
        g.fill(cx + 2, cy - 5, cx + 5, cy - 2, color);
        g.fill(cx - 5, cy + 2, cx - 2, cy + 5, color);
        g.fill(cx + 2, cy + 2, cx + 5, cy + 5, color);
        g.fill(cx - 2, cy - 2, cx + 2, cy + 2, background);
    }

    private static int fillAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static void panel(GuiGraphicsExtractor g, int x, int y, int width, int height, int border, int fill) {
        rounded(g, x, y, width, height, border);
        rounded(g, x + 1, y + 1, width - 2, height - 2, fill);
        if (width > 12) g.fill(x + 5, y + 1, x + width - 5, y + 2, 0x20FFFFFF);
    }

    private static void rounded(GuiGraphicsExtractor g, int x, int y, int width, int height, int color) {
        if (width < 6 || height < 6) {
            g.fill(x, y, x + width, y + height, color);
            return;
        }
        g.fill(x + 3, y, x + width - 3, y + height, color);
        g.fill(x + 1, y + 1, x + width - 1, y + height - 1, color);
        g.fill(x, y + 3, x + width, y + height - 3, color);
    }
}
