package com.minelatino.cosmetics.client;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.EntityRenderer;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** A separate player entity: orbiting the preview never rotates/mutates the actual player.
 *  Ported for 1.21.11: uses submitEntityRenderState instead of InventoryScreen.renderEntityInInventory. */
public final class CosmeticPreview {
    private static final int PREVIEW_ENTITY_ID = Integer.MIN_VALUE + 4931;
    static final class Frame {
        final List<EquipmentCache.EquippedItem> items;
        boolean layerVisited;
        Frame(List<EquipmentCache.EquippedItem> items) { this.items=items; }
    }
    private RemotePlayer actor;
    private Frame previousFrame;
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
            // GUI entity render states are retained in 1.21.11. Give the actor a
            // stable ID which cannot collide with a real world entity so the
            // cosmetic layer can recover the corresponding preview frame later.
            actor.setId(PREVIEW_ENTITY_ID);
            actor.yBodyRot=180; actor.yBodyRotO=180;
            actor.setYRot(180); actor.yRotO=180;
            actor.yHeadRot=180; actor.yHeadRotO=180;
        }
        actor.tickCount=mc.player.tickCount;
        float scale=Math.min(h/3.2f,w/2.4f)*zoom;
        if (previousFrame != null) layerAvailable=previousFrame.layerVisited;
        Frame frame=new Frame(List.copyOf(equipment));
        // 1.21.11 keeps the whole GUI as a retained render state. Put the PIP entity
        // in its own stratum so its framebuffer blit cannot be merged with or drawn
        // over the wardrobe controls submitted before/after it.
        g.nextStratum();
        g.enableScissor(x,y,x+w,y+h);
        try {
            @SuppressWarnings("unchecked")
            EntityRenderer<? super RemotePlayer, ?> renderer = (EntityRenderer<? super RemotePlayer, ?>) mc.getEntityRenderDispatcher().getRenderer(actor);
            AvatarRenderState renderState = (AvatarRenderState) renderer.createRenderState(actor, 0f);
            // Match InventoryScreen.extractRenderState: GUI entities use full
            // brightness and do not submit world shadows or outlines.
            renderState.lightCoords=0x00F000F0;
            renderState.shadowPieces.clear();
            renderState.outlineColor=0;
            if (equipment.stream().anyMatch(item -> "CAPE".equals(item.slot()))) renderState.showCape=false;
            CosmeticRenderer.registerPreview(renderState,frame);
            previousFrame=frame;
            g.submitEntityRenderState(renderState, scale,
                    new Vector3f(0, renderState.boundingBoxHeight/2f, 0),
                    new Quaternionf().rotationZ((float)Math.PI).rotateY((float)Math.toRadians(orbit)),
                    new Quaternionf(),
                    x, y, x+w, y+h);
        } catch (Exception e) {
            previousFrame=null;
            layerAvailable=false;
            CosmeticsDiagnostics.event("PREVIEW_ERROR",CosmeticsDiagnostics.failure(e));
        } finally {
            g.disableScissor();
            g.nextStratum();
        }
    }
}
