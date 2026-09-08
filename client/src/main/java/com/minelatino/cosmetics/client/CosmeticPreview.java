package com.minelatino.cosmetics.client;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.world.entity.player.PlayerModelPart;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** A separate player entity: orbiting the preview never rotates/mutates the actual player. */
public final class CosmeticPreview {
    static final ThreadLocal<Frame> FRAME = new ThreadLocal<>();
    static final class Frame {
        final int entityId;
        final List<EquipmentCache.EquippedItem> items;
        boolean layerVisited;
        Frame(int entityId, List<EquipmentCache.EquippedItem> items) { this.entityId=entityId; this.items=items; }
    }
    private RemotePlayer actor;
    private boolean layerAvailable;
    public boolean layerAvailable() { return layerAvailable; }

    public void render(GuiGraphics g, int x, int y, int w, int h, float orbit, float zoom,
                       List<EquipmentCache.EquippedItem> equipment) {
        Minecraft mc=Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (actor == null || actor.level() != mc.level) {
            actor=new RemotePlayer(mc.level,mc.player.getGameProfile()) {
                @Override public PlayerSkin getSkin() { return mc.player.getSkin(); }
                @Override public boolean isSpectator() { return false; }
                @Override public boolean isModelPartShown(PlayerModelPart part) { return mc.player.isModelPartShown(part); }
            };
            actor.yBodyRot=180; actor.yBodyRotO=180;
            actor.setYRot(180); actor.yRotO=180;
            actor.yHeadRot=180; actor.yHeadRotO=180;
        }
        actor.tickCount=mc.player.tickCount;
        // Inventory/entity rendering can inherit linear filtering from another GUI
        // texture. Minecraft skins are pixel art; force nearest-neighbour sampling
        // for the preview so the skin and cosmetics stay sharp at GUI scale.
        mc.getTextureManager().getTexture(actor.getSkin().texture()).setFilter(false, false);
        // Include a margin above the head for models positioned with display.head.
        float scale=Math.min(h/3.2f,w/2.4f)*zoom;
        Frame frame=new Frame(actor.getId(),List.copyOf(equipment));
        Frame previous=FRAME.get();
        g.enableScissor(x,y,x+w,y+h);
        FRAME.set(frame);
        try {
            InventoryScreen.renderEntityInInventory(g,x+w/2f,y+h*.87f,scale,new Vector3f(),
                    new Quaternionf().rotationZ((float)Math.PI).rotateY((float)Math.toRadians(orbit)),null,actor);
            layerAvailable=frame.layerVisited;
        } finally {
            if (previous == null) FRAME.remove(); else FRAME.set(previous);
            CosmeticRenderer.ENTITY_UUID_MAP.remove(actor.getId());
            g.disableScissor();
        }
    }
}
