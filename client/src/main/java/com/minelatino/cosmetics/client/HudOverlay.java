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
        List<String> lines = text(id, widget, editor);
        if (lines.isEmpty() && !id.equals(HudConfig.ARMOR) && !id.equals(HudConfig.COMPASS)
                && !id.equals(HudConfig.INPUT)) return null;
        boolean vertical = HudConfig.VERTICAL.equals(widget.layout());
        int width = id.equals(HudConfig.ARMOR) ? (vertical ? 46 : 104)
                : id.equals(HudConfig.COMPASS) ? 214
                : id.equals(HudConfig.INPUT) ? 78
                : lines.stream().mapToInt(font::width).max().orElse(50) + 16;
        int height = id.equals(HudConfig.ARMOR) ? (vertical ? 94 : 40)
                : id.equals(HudConfig.COMPASS) ? 38
                : id.equals(HudConfig.INPUT) ? 64
                : Math.max(22, lines.size() * 11 + 10);
        int x = Math.max(2, Math.min(widget.x(), graphics.guiWidth() - width - 2));
        int y = Math.max(2, Math.min(widget.y(), graphics.guiHeight() - height - 2));

        panel(graphics, x, y, width, height, widget.background(), widget.enabled());

        if (id.equals(HudConfig.ARMOR)) {
            renderArmor(graphics, x, y, widget, editor);
        } else if (id.equals(HudConfig.COMPASS)) {
            renderCompass(graphics, x, y);
        } else if (id.equals(HudConfig.INPUT)) {
            renderInput(graphics, x, y);
        } else {
            int lineY = y + 6;
            for (String line : lines) {
                graphics.drawString(font, line, x + 8, lineY, widget.enabled() ? TEXT : MUTED, false);
                lineY += 11;
            }
        }
        return new Bounds(id, x, y, width, height);
    }

    private static List<String> text(String id, HudConfig.Widget widget, boolean editor) {
        Minecraft minecraft = Minecraft.getInstance();
        String suffix = editor && !HudConfig.get(minecraft.gameDirectory.toPath()).widget(id).enabled() ? " · apagado" : "";
        if (id.equals(HudConfig.FPS)) return List.of(minecraft.getFps() + " FPS" + suffix);
        if (id.equals(HudConfig.COORDINATES)) {
            if (minecraft.player == null) return List.of("XYZ 120 / 64 / -38" + suffix);
            if (HudConfig.VERTICAL.equals(widget.layout())) return List.of(
                    String.format(Locale.ROOT, "X  %.1f%s", minecraft.player.getX(), suffix),
                    String.format(Locale.ROOT, "Y  %.1f", minecraft.player.getY()),
                    String.format(Locale.ROOT, "Z  %.1f", minecraft.player.getZ()));
            return List.of(String.format(Locale.ROOT, "XYZ %.1f / %.1f / %.1f%s",
                    minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ(), suffix));
        }
        if (id.equals(HudConfig.CPS)) {
            if (HudConfig.VERTICAL.equals(widget.layout())) return List.of(
                    "L " + ClickTracker.left() + " CPS" + suffix,
                    "R " + ClickTracker.right() + " CPS");
            return List.of("L " + ClickTracker.left() + " CPS  |  R " + ClickTracker.right() + " CPS" + suffix);
        }
        if (id.equals(HudConfig.EFFECTS)) {
            if (minecraft.player == null || minecraft.player.getActiveEffects().isEmpty()) {
                return editor ? List.of("Efectos · aparecen al activarse" + suffix) : List.of();
            }
            List<String> result = new ArrayList<>();
            for (MobEffectInstance effect : minecraft.player.getActiveEffects()) {
                if (result.size() >= 6) break;
                String name = effect.getEffect().value().getDisplayName().getString();
                int amplifier = effect.getAmplifier() + 1;
                result.add(name + (amplifier > 1 ? " " + amplifier : "") + "  " + duration(effect.getDuration()));
            }
            if (!suffix.isEmpty() && !result.isEmpty()) result.set(0, result.get(0) + suffix);
            if (!HudConfig.VERTICAL.equals(widget.layout())) return List.of(String.join("  ·  ", result));
            return result;
        }
        return List.of();
    }

    private static void renderArmor(GuiGraphics graphics, int x, int y, HudConfig.Widget widget, boolean editor) {
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        EquipmentSlot[] slots = { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };
        boolean vertical = HudConfig.VERTICAL.equals(widget.layout());
        for (int i = 0; i < slots.length; i++) {
            int itemX = x + 7 + (vertical ? 0 : i * 24);
            int itemY = y + 4 + (vertical ? i * 22 : 0);
            ItemStack stack = minecraft.player == null ? ItemStack.EMPTY : minecraft.player.getItemBySlot(slots[i]);
            if (!stack.isEmpty()) {
                graphics.renderItem(stack, itemX, itemY);
                int percent = stack.isDamageableItem()
                        ? Math.max(0, Math.round((stack.getMaxDamage() - stack.getDamageValue()) * 100f / stack.getMaxDamage()))
                        : 100;
                graphics.drawString(font, percent + "%", vertical ? itemX + 19 : itemX - 1,
                        vertical ? itemY + 4 : y + 26, durabilityColor(percent), false);
            } else {
                graphics.drawString(font, "-", itemX + 5, itemY + 5, MUTED, false);
            }
        }
        if (editor && !HudConfig.get(minecraft.gameDirectory.toPath()).widget(HudConfig.ARMOR).enabled()) {
            graphics.drawString(font, "apagado", x + (vertical ? 3 : 54), y + (vertical ? 81 : 27), MUTED, false);
        }
    }

    private static void renderCompass(GuiGraphics graphics, int x, int y) {
        Minecraft minecraft = Minecraft.getInstance();
        float yaw = minecraft.player == null ? 0f : minecraft.player.getYRot();
        float heading = ((yaw % 360f) + 360f) % 360f;
        int center = x + 107;
        graphics.enableScissor(x + 4, y + 3, x + 210, y + 34);
        for (int angle = 0; angle < 360; angle += 15) {
            float delta = ((angle - heading + 540f) % 360f) - 180f;
            int tickX = center + Math.round(delta * 1.45f);
            if (tickX < x + 5 || tickX > x + 209) continue;
            boolean major = angle % 45 == 0;
            graphics.fill(tickX, y + (major ? 8 : 12), tickX + 1, y + 17, major ? TEXT : MUTED);
            if (major) {
                String label = cardinal(angle);
                graphics.drawCenteredString(minecraft.font, label, tickX, y + 20, TEXT);
            } else {
                graphics.drawCenteredString(minecraft.font, Integer.toString(angle), tickX, y + 21, 0xFF7F8B95);
            }
        }
        graphics.disableScissor();
        graphics.fill(center, y + 3, center + 1, y + 8, BORDER);
        graphics.drawCenteredString(minecraft.font, Math.round(heading) + "°", center, y + 29, 0xFF8DEBFF);
    }

    private static String cardinal(int angle) {
        return switch (angle) {
            case 0 -> "S"; case 45 -> "SW"; case 90 -> "W"; case 135 -> "NW";
            case 180 -> "N"; case 225 -> "NE"; case 270 -> "E"; default -> "SE";
        };
    }

    private static void renderInput(GuiGraphics graphics, int x, int y) {
        Minecraft minecraft = Minecraft.getInstance();
        key(graphics, x + 28, y + 5, 22, 17, "W", minecraft.options.keyUp.isDown());
        key(graphics, x + 4, y + 24, 22, 17, "A", minecraft.options.keyLeft.isDown());
        key(graphics, x + 28, y + 24, 22, 17, "S", minecraft.options.keyDown.isDown());
        key(graphics, x + 52, y + 24, 22, 17, "D", minecraft.options.keyRight.isDown());
        key(graphics, x + 4, y + 43, 34, 16, "LMB", ClickTracker.leftDown());
        key(graphics, x + 40, y + 43, 34, 16, "RMB", ClickTracker.rightDown());
        graphics.fill(x + 24, y + 60, x + 54, y + 61,
                minecraft.options.keyJump.isDown() ? BORDER : 0xFF83909A);
    }

    private static void key(GuiGraphics graphics, int x, int y, int width, int height, String label, boolean down) {
        rounded(graphics, x, y, width, height, down ? 0xD020D9FF : 0x90404750);
        graphics.drawCenteredString(Minecraft.getInstance().font, label, x + width / 2, y + 5,
                down ? 0xFF071217 : TEXT);
    }

    private static void panel(GuiGraphics graphics, int x, int y, int width, int height, int background, boolean enabled) {
        rounded(graphics, x + 2, y + 2, width, height, 0x50000000);
        rounded(graphics, x, y, width, height, enabled ? BORDER : 0xFF59636D);
        rounded(graphics, x + 1, y + 1, width - 2, height - 2, background);
        graphics.fill(x + 6, y + 1, x + width - 6, y + 2, 0x28FFFFFF);
    }

    private static void rounded(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        if (width < 6 || height < 6) { graphics.fill(x, y, x + width, y + height, color); return; }
        graphics.fill(x + 3, y, x + width - 3, y + height, color);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, color);
        graphics.fill(x, y + 3, x + width, y + height - 3, color);
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
