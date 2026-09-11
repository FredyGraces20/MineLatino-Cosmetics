package com.minelatino.cosmetics.client;

import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/** Compact glass button used by the HUD editor. */
final class HudButton extends AbstractButton {
    private final Runnable action;
    private final BooleanSupplier selected;
    private float hover;

    HudButton(int x, int y, int width, int height, String label, Runnable action, BooleanSupplier selected) {
        super(x, y, width, height, Component.literal(label));
        this.action = action;
        this.selected = selected;
    }

    @Override public void onPress() { action.run(); }
    @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }

    @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        hover += ((isHoveredOrFocused() ? 1f : 0f) - hover) * Math.min(1f, delta * .22f + .1f);
        boolean chosen = selected.getAsBoolean();
        int border = chosen ? 0xFF20D9FF : hover > .2f ? 0xFF4F8190 : 0xFF38444D;
        int fill = chosen ? 0xD0123440 : hover > .2f ? 0xE2263038 : 0xD0181E24;
        panel(graphics, getX(), getY(), getWidth(), getHeight(), border, fill);
        var font = Minecraft.getInstance().font;
        String text = font.plainSubstrByWidth(getMessage().getString(), Math.max(1, getWidth() - 10));
        graphics.drawCenteredString(font, text, getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2,
                active ? (chosen ? 0xFFA8F3FF : 0xFFF2F7FA) : 0xFF707A82);
    }

    static void panel(GuiGraphics graphics, int x, int y, int width, int height, int border, int fill) {
        rounded(graphics, x + 2, y + 2, width, height, 0x50000000);
        rounded(graphics, x, y, width, height, border);
        rounded(graphics, x + 1, y + 1, width - 2, height - 2, fill);
        if (width > 12) graphics.fill(x + 6, y + 1, x + width - 6, y + 2, 0x28FFFFFF);
    }

    private static void rounded(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x + 3, y, x + width - 3, y + height, color);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, color);
        graphics.fill(x, y + 3, x + width, y + height - 3, color);
    }
}
