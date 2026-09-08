package com.minelatino.cosmetics.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Quaternionf;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders cosmetic overlays on players using 3D models (Blockbench JSON format).
 * Falls back to flat textured quads for CAPE/HAT/WINGS when no model is available.
 *
 * Uses PlayerRenderState.id (entity ID) to look up the player UUID.
 * The UUID map is populated by both Fabric (mixin) and Forge (tick event).
 */
public final class CosmeticRenderer extends RenderLayer<PlayerRenderState, PlayerModel> {
    private static final Logger LOG = LoggerFactory.getLogger("MineLatino Cosmetics");

    /** Maps entity ID → player UUID (without hyphens). Populated by tick handlers. */
    static final Map<Integer, UUID> ENTITY_UUID_MAP = new ConcurrentHashMap<>();

    private static long lastWarnLog = 0;
    private static int renderCallCount = 0;

    private final ResourceCache resourceCache;

    public CosmeticRenderer(RenderLayerParent<PlayerRenderState, PlayerModel> parent, ResourceCache resourceCache) {
        super(parent);
        this.resourceCache = resourceCache;
        LOG.info("[CosmeticRenderer] Layer constructed");
        CosmeticsDiagnostics.event("LAYER_CREATED","CosmeticRenderer");
    }

    /**
     * Stores the UUID for an entity ID. Called by Fabric mixin or Forge tick handler.
     */
    public static void putEntityUuid(int entityId, UUID uuid) {
        ENTITY_UUID_MAP.put(entityId, uuid);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                       PlayerRenderState renderState, float yaw, float partialTick) {
        renderCallCount++;
        boolean local=Minecraft.getInstance().player!=null && Minecraft.getInstance().player.getId()==renderState.id;
        if (renderState.isInvisible || renderState.isSpectator) {
            if(local) CosmeticsDiagnostics.changed("WORLD_RENDER","skipped invisible="+renderState.isInvisible+" spectator="+renderState.isSpectator);
            return;
        }

        // Use state.id directly (public field in 1.21.4 PlayerRenderState)
        CosmeticPreview.Frame preview = CosmeticPreview.FRAME.get();
        List<EquipmentCache.EquippedItem> equipped;
        if (preview != null && preview.entityId == renderState.id) {
            preview.layerVisited = true;
            CosmeticsDiagnostics.changed("PREVIEW_RENDER","layer active; items="+preview.items.size());
            equipped = preview.items;
        } else {
            UUID uuid = ENTITY_UUID_MAP.get(renderState.id);
            // The local server may expose an offline UUID. For the local player,
            // prefer the verified MineLatino session directly so an equipped item
            // is visible immediately even when server and account UUIDs differ.
            if (Minecraft.getInstance().player != null
                    && Minecraft.getInstance().player.getId() == renderState.id
                    && CosmeticsClient.instance().auth().isConnected()
                    && CosmeticsClient.instance().auth().session() != null) {
                try {
                    uuid = UUID.fromString(formatUuid(CosmeticsClient.instance().auth().session().uuid()));
                } catch (IllegalArgumentException ignored) {
                    // Keep the normal entity mapping if the verified UUID is malformed.
                }
            }
            if (uuid == null) {
                if(local) CosmeticsDiagnostics.changed("WORLD_RENDER","missing entity UUID mapping");
                return;
            }
            equipped = CosmeticsClient.instance().equipment().get(uuid.toString());
        }
        if (equipped == null || equipped.isEmpty()) {
            if(local) CosmeticsDiagnostics.changed("WORLD_RENDER","no equipment in resolved UUID cache");
            return;
        }

        int submitted=0, pendingResources=0, unsupported=0;
        for (EquipmentCache.EquippedItem item : equipped) {
            if (CosmeticSlot.from(item.slot()).isEmpty()) { unsupported++; continue; }
            ResourceCache.CachedResource res = resourceCache.getOrDownload(item.cosmeticId());
            if (res == null || res.texture() == null) { pendingResources++; continue; }

            CosmeticModel model = res.model();
            if (model != null && !model.elements.isEmpty()) {
                renderModel(poseStack, bufferSource, packedLight, renderState, res, model, item.slot());
                if(!model.quads.isEmpty()) submitted++;
            } else {
                renderFallback(poseStack, bufferSource, packedLight, renderState, res.texture(), item.slot());
                submitted++;
            }
        }
        if(local) CosmeticsDiagnostics.changed("WORLD_RENDER","equipped="+equipped.size()+" submitted="+submitted+
                " resourcesUnavailable="+pendingResources+" unsupportedSlots="+unsupported);
    }

    private static String formatUuid(String value) {
        String hex = value.replace("-", "");
        if (!hex.matches("[0-9a-fA-F]{32}")) throw new IllegalArgumentException("invalid UUID");
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
                + "-" + hex.substring(16, 20) + "-" + hex.substring(20);
    }

    /** Renders a 3D model attached to the player. */
    private void renderModel(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                             PlayerRenderState state, ResourceCache.CachedResource resource, CosmeticModel model, String slot) {

        poseStack.pushPose();
        try {
            getParentModel().root().translateAndRotate(poseStack);
            if ("HAT".equals(slot)) {
                getParentModel().head.translateAndRotate(poseStack);
                CustomHeadLayer.translateToHead(poseStack, CustomHeadLayer.Transforms.DEFAULT);
                CosmeticModel.DisplayTransform head = model.head;
                poseStack.translate(head.translation()[0]/16, head.translation()[1]/16, head.translation()[2]/16);
                poseStack.mulPose(new Quaternionf().rotationXYZ(
                        (float)Math.toRadians(head.rotation()[0]),
                        (float)Math.toRadians(head.rotation()[1]),
                        (float)Math.toRadians(head.rotation()[2])));
                poseStack.scale(head.scale()[0], head.scale()[1], head.scale()[2]);
            } else if ("PET".equals(slot)) {
                // Companion beside the player's feet, not attached to the animated head/body.
                poseStack.translate(0.8, 1.0 + Math.sin(state.ageInTicks * 0.08) * 0.035, 0);
                poseStack.scale(0.55f, -0.55f, -0.55f);
            } else {
                getParentModel().body.translateAndRotate(poseStack);
                poseStack.translate(0, 0.3, "BACKPACK".equals(slot) ? 0.30 : 0.16);
                // Java element models use Y-up; player ModelPart coordinates use Y-down.
                poseStack.scale(1, -1, -1);
                if ("BACKPACK".equals(slot)) {
                    var b = model.backpack;
                    poseStack.translate(b.translation()[0]/16,b.translation()[1]/16,b.translation()[2]/16);
                    poseStack.mulPose(new Quaternionf().rotationXYZ((float)Math.toRadians(b.rotation()[0]),(float)Math.toRadians(b.rotation()[1]),(float)Math.toRadians(b.rotation()[2])));
                    poseStack.scale(b.scale()[0],b.scale()[1],b.scale()[2]);
                }
            }
            for (CosmeticModel.Quad quad : model.quads) {
                var material = resource.materials().get(quad.texture());
                var texture = material == null ? resource.texture() : material.texture();
                VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityCutout(texture));
                if (material == null || (material.animation().rows() == 1 && material.animation().columns() == 1)) renderQuad(consumer, poseStack, packedLight, quad);
                else {
                    var a = material.animation();
                    double ticks = System.nanoTime() / 50_000_000.0;
                    renderQuad(consumer, poseStack, packedLight, new CosmeticModel.Quad(a.vertex(quad.v0(),ticks),a.vertex(quad.v1(),ticks),
                            a.vertex(quad.v2(),ticks),a.vertex(quad.v3(),ticks),quad.normal(),quad.texture()));
                }
            }
        } finally {
            poseStack.popPose();
        }
    }

    /** Renders a single quad from a CosmeticModel. */
    private static void renderQuad(VertexConsumer consumer, PoseStack poseStack, int packedLight, CosmeticModel.Quad quad) {
        PoseStack.Pose pose = poseStack.last();
        float[] n = quad.normal();
        addVertex(consumer, pose, quad.v0(), packedLight, n);
        addVertex(consumer, pose, quad.v1(), packedLight, n);
        addVertex(consumer, pose, quad.v2(), packedLight, n);
        addVertex(consumer, pose, quad.v3(), packedLight, n);
    }

    /**
     * Adds a vertex with all required attributes: position, color, UV (texture),
     * UV1 (overlay), light, and normal.
     */
    private static void addVertex(VertexConsumer consumer, PoseStack.Pose pose, float[] v, int light, float[] normal) {
        consumer.addVertex(pose, v[0], v[1], v[2])
                .setColor(255, 255, 255, 255)
                .setUv(v[3], v[4])
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, normal[0], normal[1], normal[2]);
    }

    /** Fallback: renders flat textured quads for cosmetics without 3D models. */
    private void renderFallback(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                                PlayerRenderState state, ResourceLocation texture, String slot) {
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(texture));
        poseStack.pushPose();
        getParentModel().root().translateAndRotate(poseStack);
        try { switch (slot) {
            case "CAPE" -> renderCape(poseStack, consumer, packedLight);
            case "HAT" -> renderHat(poseStack, consumer, packedLight);
            case "WINGS" -> renderWings(poseStack, consumer, packedLight);
            case "BACKPACK", "PET" -> {
                if ("BACKPACK".equals(slot)) {
                    getParentModel().body.translateAndRotate(poseStack);
                    poseStack.translate(0, 0.3, 0.16);
                } else poseStack.translate(0.8, 1.0, 0);
                drawQuad(consumer, poseStack, packedLight, -.22f, .22f, -.22f, .22f,
                        0, 1, 0, 1, 0, 0, -1);
            }
        } } finally { poseStack.popPose(); }
    }

    private void renderCape(PoseStack poseStack, VertexConsumer consumer, int packedLight) {
        poseStack.pushPose();
        getParentModel().body.translateAndRotate(poseStack);
        poseStack.translate(0.0, 0.0, 0.14);
        drawQuad(consumer, poseStack, packedLight,
                -0.25f, 0.25f, 0.0f, 0.75f,
                0.0f, 1.0f, 0.0f, 1.0f, 0, 0, -1);
        poseStack.popPose();
    }

    private void renderHat(PoseStack poseStack, VertexConsumer consumer, int packedLight) {
        poseStack.pushPose();
        getParentModel().head.translateAndRotate(poseStack);
        poseStack.translate(0.0, -0.6, 0.0);
        drawQuad(consumer, poseStack, packedLight,
                -0.3f, 0.3f, -0.3f, 0.0f,
                0.0f, 1.0f, 0.0f, 1.0f, 0, 0, -1);
        poseStack.popPose();
    }

    private void renderWings(PoseStack poseStack, VertexConsumer consumer, int packedLight) {
        poseStack.pushPose();
        getParentModel().body.translateAndRotate(poseStack);
        poseStack.translate(0.0, 0.1, 0.14);
        poseStack.pushPose();
        poseStack.translate(-0.15, 0, 0);
        drawQuad(consumer, poseStack, packedLight, -0.35f, 0.0f, -0.3f, 0.3f, 0.0f, 0.5f, 0.0f, 1.0f, 0, 0, -1);
        poseStack.popPose();
        poseStack.pushPose();
        poseStack.translate(0.15, 0, 0);
        drawQuad(consumer, poseStack, packedLight, 0.0f, 0.35f, -0.3f, 0.3f, 0.5f, 1.0f, 0.0f, 1.0f, 0, 0, -1);
        poseStack.popPose();
        poseStack.popPose();
    }

    private static void drawQuad(VertexConsumer consumer, PoseStack poseStack, int packedLight,
                                  float x1, float x2, float y1, float y2,
                                  float u1, float u2, float v1, float v2,
                                  int nx, int ny, int nz) {
        PoseStack.Pose pose = poseStack.last();
        consumer.addVertex(pose, x1, y1, 0).setColor(255, 255, 255, 255).setUv(u1, v1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(packedLight).setNormal(pose, nx, ny, nz);
        consumer.addVertex(pose, x1, y2, 0).setColor(255, 255, 255, 255).setUv(u1, v2).setOverlay(OverlayTexture.NO_OVERLAY).setLight(packedLight).setNormal(pose, nx, ny, nz);
        consumer.addVertex(pose, x2, y2, 0).setColor(255, 255, 255, 255).setUv(u2, v2).setOverlay(OverlayTexture.NO_OVERLAY).setLight(packedLight).setNormal(pose, nx, ny, nz);
        consumer.addVertex(pose, x2, y1, 0).setColor(255, 255, 255, 255).setUv(u2, v1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(packedLight).setNormal(pose, nx, ny, nz);
    }
}
