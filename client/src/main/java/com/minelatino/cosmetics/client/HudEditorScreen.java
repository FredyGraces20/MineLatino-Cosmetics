package com.minelatino.cosmetics.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Drag-and-drop editor for MineLatino HUD modules. */
public final class HudEditorScreen extends Screen {
    private final Screen parent;
    private String dragging;
    private double dragOffsetX;
    private double dragOffsetY;

    public HudEditorScreen(Screen parent) {
        super(Component.literal("Personalizar HUD"));
        this.parent = parent;
    }

    @Override protected void init() {
        HudConfig config = config();
        int x = Math.max(8, width - 148);
        int y = 34;
        for (String id : HudConfig.ORDER) {
            HudConfig.Widget widget = config.widget(id);
            Button button = Button.builder(label(id, widget.enabled()), pressed -> {
                config.toggle(id);
                pressed.setMessage(label(id, config.widget(id).enabled()));
            }).bounds(x, y, 140, 20).build();
            addRenderableWidget(button);
            y += 24;
        }
        addRenderableWidget(Button.builder(Component.literal("Restablecer HUD"), pressed -> {
            config.reset();
            minecraft.setScreen(new HudEditorScreen(parent));
        }).bounds(x, y + 6, 140, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Volver"), pressed -> onClose())
                .bounds(x, height - 28, 140, 20).build());
    }

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xE6101218);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        renderBackground(graphics, mouseX, mouseY, delta);
        HudOverlay.render(graphics, true);
        super.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFF2F7FA);
        graphics.drawString(font, "Arrastra cada elemento para colocarlo. Los cambios se guardan en este perfil.",
                8, height - 14, 0xFFA8B2BC, false);
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button == 0) {
            HudOverlay.Bounds bounds = HudOverlay.at(mouseX, mouseY);
            if (bounds != null) {
                dragging = bounds.id();
                dragOffsetX = mouseX - bounds.x();
                dragOffsetY = mouseY - bounds.y();
                return true;
            }
        }
        return false;
    }

    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (dragging != null && button == 0) {
            config().move(dragging, (int)Math.round(mouseX - dragOffsetX), (int)Math.round(mouseY - dragOffsetY));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging != null) {
            config().save();
            dragging = null;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override public void onClose() {
        config().save();
        minecraft.setScreen(parent);
    }

    private HudConfig config() {
        return HudConfig.get(minecraft.gameDirectory.toPath());
    }

    private static Component label(String id, boolean enabled) {
        String name = switch (id) {
            case HudConfig.FPS -> "FPS";
            case HudConfig.COORDINATES -> "Coordenadas";
            case HudConfig.CPS -> "CPS";
            case HudConfig.ARMOR -> "Durabilidad de armadura";
            case HudConfig.EFFECTS -> "Efectos";
            default -> id;
        };
        return Component.literal((enabled ? "✓ " : "○ ") + name);
    }
}
