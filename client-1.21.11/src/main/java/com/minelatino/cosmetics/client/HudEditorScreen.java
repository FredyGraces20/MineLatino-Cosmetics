package com.minelatino.cosmetics.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
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
        int x = Math.max(8, width - 246);
        int y = 30;
        for (String id : HudConfig.ORDER) {
            HudConfig.Widget widget = config.widget(id);
            boolean hasLayout = HudConfig.supportsLayout(id);
            addRenderableWidget(new HudButton(x, y, hasLayout ? 112 : 159, 20,
                    (widget.enabled() ? "✓ " : "○ ") + name(id), () -> {
                config.toggle(id);
                reopen();
            }, widget::enabled));
            if (hasLayout) {
                addRenderableWidget(new HudButton(x + 116, y, 43, 20,
                        HudConfig.VERTICAL.equals(widget.layout()) ? "V" : "H", () -> {
                    config.toggleLayout(id);
                    reopen();
                }, () -> HudConfig.VERTICAL.equals(config.widget(id).layout())));
            }
            addRenderableWidget(new HudButton(x + 163, y, 75, 20,
                    HudConfig.backgroundName(widget.background()), () -> {
                config.nextBackground(id);
                reopen();
            }, () -> false));
            y += 22;
        }
        addRenderableWidget(new HudButton(x, y + 5, 116, 20, "Restablecer", () -> {
            config.reset();
            reopen();
        }, () -> false));
        addRenderableWidget(new HudButton(x + 122, y + 5, 116, 20, "Guardar y volver", this::onClose, () -> false));
    }

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xE6101218);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        renderBackground(graphics, mouseX, mouseY, delta);
        HudOverlay.render(graphics, true);
        int panelX = Math.max(4, width - 250);
        HudButton.panel(graphics, panelX, 4, 246, Math.min(height - 8, 216), 0xFF31515D, 0xE611171D);
        super.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(font, title, panelX + 123, 11, 0xFFA8F3FF);
        graphics.drawString(font, "Arrastra los módulos · H/V cambia su forma · el último botón cambia el fondo",
                8, height - 14, 0xFFA8B2BC, false);
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() == 0) {
            HudOverlay.Bounds bounds = HudOverlay.at(event.x(), event.y());
            if (bounds != null) {
                dragging = bounds.id();
                dragOffsetX = event.x() - bounds.x();
                dragOffsetY = event.y() - bounds.y();
                return true;
            }
        }
        return false;
    }

    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging != null && event.button() == 0) {
            config().move(dragging, (int)Math.round(event.x() - dragOffsetX), (int)Math.round(event.y() - dragOffsetY));
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging != null) {
            config().save();
            dragging = null;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override public void onClose() {
        config().save();
        minecraft.setScreen(parent);
    }

    private HudConfig config() {
        return HudConfig.get(minecraft.gameDirectory.toPath());
    }

    private void reopen() { minecraft.setScreen(new HudEditorScreen(parent)); }

    private static String name(String id) {
        return switch (id) {
            case HudConfig.FPS -> "FPS";
            case HudConfig.COORDINATES -> "Coordenadas";
            case HudConfig.CPS -> "CPS";
            case HudConfig.ARMOR -> "Armadura";
            case HudConfig.EFFECTS -> "Efectos";
            case HudConfig.COMPASS -> "Brújula";
            case HudConfig.INPUT -> "Teclas y mouse";
            default -> id;
        };
    }
}
