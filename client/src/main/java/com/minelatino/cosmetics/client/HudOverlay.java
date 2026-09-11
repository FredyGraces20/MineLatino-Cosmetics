package com.minelatino.cosmetics.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/** Renders MineLatino's configurable in-game HUD. */
public final class HudOverlay {
    private static final int BACKGROUND = 0xB8141820;
    private static final int BORDER = 0xFF20D9FF;
    private static final int TEXT = 0xFFF2F7FA;
    private static final int MUTED = 0xFFA8B2BC;
    private static List<Bounds> lastBounds = List.of();

    public record Bounds(String id, int x, int y, int width, int height) {
        public boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }

    private HudOverlay() {}

    public static void render(GuiGraphics graphics, boolean editor) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!editor && (minecraft.options.hideGui || minecraft.player == null || minecraft.screen != null)) return;
        HudConfig config = HudConfig.get(minecraft.gameDirectory.toPath());
        List<Bounds> rendered = new ArrayList<>();
        for (String id : HudConfig.ORDER) {
            HudConfig.Widget widget = config.widget(id);
            if (!widget.enabled() && !editor) continue;
            Bounds bounds = renderWidget(graphics, id, widget, editor);
            if (bounds != null) rendered.add(bounds);
        }
        lastBounds = List.copyOf(rendered);
    }

    public static Bounds at(double x, double y) {
        for (int i = lastBounds.size() - 1; i >= 0; i--) {
            Bounds bounds = lastBounds.get(i);
            if (bounds.contains(x, y)) return bounds;
        }
        return null;
    }

    private static Bounds renderWidget(GuiGraphics graphics, String id, HudConfig.Widget widget, boolean editor) {
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        List<String> lines = text(id, editor);
        int width = id.equals(HudConfig.ARMOR) ? 100 : lines.stream().mapToInt(font::width).max().orElse(50) + 12;
        int height = id.equals(HudConfig.ARMOR) ? 37 : Math.max(20, lines.size() * 11 + 8);
        int x = Math.max(2, Math.min(widget.x(), graphics.guiWidth() - width - 2));
        int y = Math.max(2, Math.min(widget.y(), graphics.guiHeight() - height - 2));

        graphics.fill(x, y, x + width, y + height, BACKGROUND);
        graphics.fill(x, y, x + 2, y + height, widget.enabled() ? BORDER : 0xFF59636D);

        if (id.equals(HudConfig.ARMOR)) {
            renderArmor(graphics, x, y, editor);
        } else {
            int lineY = y + 5;
            for (String line : lines) {
                graphics.drawString(font, line, x + 7, lineY, widget.enabled() ? TEXT : MUTED, false);
                lineY += 11;
            }
        }
        return new Bounds(id, x, y, width, height);
    }

    private static List<String> text(String id, boolean editor) {
        Minecraft minecraft = Minecraft.getInstance();
        String suffix = editor && !HudConfig.get(minecraft.gameDirectory.toPath()).widget(id).enabled() ? " · apagado" : "";
        if (id.equals(HudConfig.FPS)) return List.of(minecraft.getFps() + " FPS" + suffix);
        if (id.equals(HudConfig.COORDINATES)) {
            if (minecraft.player == null) return List.of("XYZ 120 / 64 / -38" + suffix);
            return List.of(String.format(Locale.ROOT, "XYZ %.1f / %.1f / %.1f%s",
                    minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ(), suffix));
        }
        if (id.equals(HudConfig.CPS)) return List.of("L " + ClickTracker.left() + " CPS  |  R " + ClickTracker.right() + " CPS" + suffix);
        if (id.equals(HudConfig.EFFECTS)) {
            if (minecraft.player == null || minecraft.player.getActiveEffects().isEmpty()) {
                return List.of("Sin efectos" + suffix);
            }
            List<String> result = new ArrayList<>();
            for (MobEffectInstance effect : minecraft.player.getActiveEffects()) {
                if (result.size() >= 6) break;
                String name = effect.getEffect().value().getDisplayName().getString();
                int amplifier = effect.getAmplifier() + 1;
                result.add(name + (amplifier > 1 ? " " + amplifier : "") + "  " + duration(effect.getDuration()));
            }
            if (!suffix.isEmpty() && !result.isEmpty()) result.set(0, result.get(0) + suffix);
            return result;
        }
        return List.of();
    }

    private static void renderArmor(GuiGraphics graphics, int x, int y, boolean editor) {
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        EquipmentSlot[] slots = { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };
        for (int i = 0; i < slots.length; i++) {
            int itemX = x + 7 + i * 23;
            ItemStack stack = minecraft.player == null ? ItemStack.EMPTY : minecraft.player.getItemBySlot(slots[i]);
            if (!stack.isEmpty()) {
                graphics.renderItem(stack, itemX, y + 3);
                int percent = stack.isDamageableItem()
                        ? Math.max(0, Math.round((stack.getMaxDamage() - stack.getDamageValue()) * 100f / stack.getMaxDamage()))
                        : 100;
                graphics.drawString(font, percent + "%", itemX - 1, y + 23, durabilityColor(percent), false);
            } else {
                graphics.drawString(font, "-", itemX + 5, y + 8, MUTED, false);
            }
        }
        if (editor && !HudConfig.get(minecraft.gameDirectory.toPath()).widget(HudConfig.ARMOR).enabled()) {
            graphics.drawString(font, "apagado", x + 51, y + 25, MUTED, false);
        }
    }

    private static int durabilityColor(int percent) {
        if (percent <= 20) return 0xFFFF5A67;
        if (percent <= 50) return 0xFFFFC857;
        return 0xFF6DFF9A;
    }

    private static String duration(int ticks) {
        int seconds = Math.max(0, ticks / 20);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
