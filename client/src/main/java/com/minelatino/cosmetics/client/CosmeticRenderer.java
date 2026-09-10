package com.minelatino.cosmetics.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.PlayerCapeModel;
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
    private final PlayerCapeModel<PlayerRenderState> capeModel =
            new PlayerCapeModel<>(PlayerCapeModel.createCapeLayer().bakeRoot());

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

            if ("SKIN".equals(item.slot())) {
                if (res.avatar() != null) { renderAvatar(poseStack,bufferSource,packedLight,renderState,res); submitted++; }
                else pendingResources++;
                continue;
            }

            CosmeticModel model = res.model();
            if (model != null && !model.elements.isEmpty()) {
                renderModel(poseStack, bufferSource, packedLight, renderState, res, model, item.slot(), item.cosmeticId());
                if(!model.quads.isEmpty()) submitted++;
            } else {
                renderFallback(poseStack, bufferSource, packedLight, renderState, res.texture(), item.slot());
                submitted++;
            }
        }
        if(local) CosmeticsDiagnostics.changed("WORLD_RENDER","equipped="+equipped.size()+" submitted="+submitted+
                " resourcesUnavailable="+pendingResources+" unsupportedSlots="+unsupported);
    }

    /** Used by PlayerModelMixin to suppress the vanilla body only after a valid replacement is ready. */
    public static boolean hasReadySkin(PlayerRenderState state) {
        List<EquipmentCache.EquippedItem> equipped=equipmentFor(state.id);
        if(equipped==null)return false;
        for(var item:equipped)if("SKIN".equals(item.slot())){var resource=CosmeticsClient.instance().resources().getOrDownload(item.cosmeticId());return resource!=null&&resource.avatar()!=null;}
        return false;
    }

    private static List<EquipmentCache.EquippedItem> equipmentFor(int entityId){
        CosmeticPreview.Frame preview=CosmeticPreview.FRAME.get();if(preview!=null&&preview.entityId==entityId)return preview.items;
        UUID uuid=ENTITY_UUID_MAP.get(entityId);if(Minecraft.getInstance().player!=null&&Minecraft.getInstance().player.getId()==entityId&&CosmeticsClient.instance().auth().isConnected()&&CosmeticsClient.instance().auth().session()!=null)try{uuid=UUID.fromString(formatUuid(CosmeticsClient.instance().auth().session().uuid()));}catch(IllegalArgumentException ignored){}
        return uuid==null?null:CosmeticsClient.instance().equipment().get(uuid.toString());
    }

    private void renderAvatar(PoseStack stack,MultiBufferSource buffers,int light,PlayerRenderState state,ResourceCache.CachedResource resource){
        AvatarPackage avatar=resource.avatar();String clip=AvatarEmoteState.clip(state.id);
        double seconds=AvatarEmoteState.seconds(state.id);
        if(clip==null){clip=movementClip(avatar.animations(),state);seconds=state.ageInTicks/20.0;}
        VertexConsumer consumer=buffers.getBuffer(RenderType.entityCutoutNoCull(resource.texture()));
        boolean rightMain=state.mainArm==net.minecraft.world.entity.HumanoidArm.RIGHT;
        AvatarAnimation.Context animationContext=new AvatarAnimation.Context(state.yRot,state.xRot,state.walkAnimationSpeed,0,
                !state.getMainHandItem().isEmpty(),!(rightMain?state.leftHandItem:state.rightHandItem).isEmpty(),!state.headEquipment.isEmpty());
        stack.pushPose();try{
            // Bedrock/Gecko geometry uses pixels, Y-up and +Z forward. Mirror X and
            // Y to enter Minecraft's model space; flipping Z here turns the entire
            // avatar around and makes it face backwards relative to the player.
            stack.translate(0,1.5,0);stack.scale(-1,-1,1);
            for(AvatarModel.Bone root:avatar.model().roots())renderAvatarBone(stack,consumer,light,avatar,root,clip,seconds,state.ageInTicks/20.0,animationContext);
        }finally{stack.popPose();}
    }

    private static String movementClip(AvatarAnimation animations,PlayerRenderState state){
        List<String> candidates;
        if(state.deathTime>0)candidates=List.of("death");
        else if(state.bedOrientation!=null)candidates=List.of("sleep");
        else if(state.isAutoSpinAttack)candidates=List.of("riptide");
        else if(state.hasRedOverlay)candidates=List.of("attacked");
        else if(state.isUsingItem)candidates=state.useItemHand==net.minecraft.world.InteractionHand.OFF_HAND?List.of("use_offhand","use_mainhand"):List.of("use_mainhand","use_offhand");
        else if(state.attackTime>0)candidates=List.of("swing_hand");
        else if(state.isFallFlying)candidates=List.of("elytra_fly","fly");
        else if(state.isVisuallySwimming)candidates=List.of("swim");
        else if(state.isInWater)candidates=List.of("swim_stand","swim");
        else if(state.isPassenger)candidates=List.of("ride","boat","sit");
        else if(state.isCrouching)candidates=List.of(state.walkAnimationSpeed>.02f?"sneak":"sneaking");
        else if(state.walkAnimationSpeed>.75f)candidates=List.of("run","walk");
        else if(state.walkAnimationSpeed>.02f)candidates=List.of("walk","run");
        else candidates=List.of("idle");
        for(String candidate:candidates)
            for(String name:animations.names())if(name.equals(candidate)||name.endsWith("."+candidate))return name;
        return null;
    }

    private static void renderAvatarBone(PoseStack stack,VertexConsumer consumer,int light,AvatarPackage avatar,AvatarModel.Bone bone,String clip,double seconds,double ambientSeconds,AvatarAnimation.Context context){
        AvatarAnimation.Pose animation=avatar.animations().sampleLayered(clip,bone.name(),seconds,ambientSeconds,context);
        float[]p=bone.pivot(),r=bone.rotation(),ar=animation.rotation();stack.pushPose();try{
            // YSM/Gecko bake: translation(-X,+Y,+Z), pivot(-X,+Y,+Z),
            // rotation(-X,-Y,+Z). Applying raw Blockbench values directly makes
            // left/right bones cross over and rotates accessories inside-out.
            stack.translate(-animation.position()[0]/16.0,animation.position()[1]/16.0,animation.position()[2]/16.0);
            stack.translate(-p[0]/16.0,p[1]/16.0,p[2]/16.0);
            stack.mulPose(new Quaternionf().rotationZYX((float)Math.toRadians(r[2]+ar[2]),(float)Math.toRadians(-(r[1]+ar[1])),(float)Math.toRadians(-(r[0]+ar[0]))));
            stack.scale(animation.scale()[0],animation.scale()[1],animation.scale()[2]);stack.translate(p[0]/16.0,-p[1]/16.0,-p[2]/16.0);
            for(AvatarModel.Cube cube:bone.cubes())renderAvatarCube(stack,consumer,light,avatar.model(),cube);
            for(AvatarModel.Bone child:avatar.model().children(bone.name()))renderAvatarBone(stack,consumer,light,avatar,child,clip,seconds,ambientSeconds,context);
        }finally{stack.popPose();}
    }

    private static void renderAvatarCube(PoseStack stack,VertexConsumer consumer,int light,AvatarModel model,AvatarModel.Cube cube){
        stack.pushPose();try{float[]p=cube.pivot(),r=cube.rotation();stack.translate(-p[0]/16.0,p[1]/16.0,p[2]/16.0);stack.mulPose(new Quaternionf().rotationZYX((float)Math.toRadians(r[2]),(float)Math.toRadians(-r[1]),(float)Math.toRadians(-r[0])));stack.translate(p[0]/16.0,-p[1]/16.0,-p[2]/16.0);
            PoseStack.Pose pose=stack.last();for(AvatarGeometry.Quad quad:AvatarGeometry.bake(model,cube))for(float[]vertex:quad.vertices())addVertex(consumer,pose,vertex,light,quad.normal());
        }finally{stack.popPose();}}

    private static String formatUuid(String value) {
        String hex = value.replace("-", "");
        if (!hex.matches("[0-9a-fA-F]{32}")) throw new IllegalArgumentException("invalid UUID");
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
                + "-" + hex.substring(16, 20) + "-" + hex.substring(20);
    }

    /** Renders a 3D model attached to the player. */
    private void renderModel(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                             PlayerRenderState state, ResourceCache.CachedResource resource, CosmeticModel model, String slot, String cosmeticId) {

        poseStack.pushPose();
        try {
            getParentModel().root().translateAndRotate(poseStack);
            if ("HAT".equals(slot)) {
                getParentModel().head.translateAndRotate(poseStack);
                CustomHeadLayer.translateToHead(poseStack, CustomHeadLayer.Transforms.DEFAULT);
                ApiClient.TransformData serverHead = CosmeticsClient.instance().getTransform(cosmeticId, "hat");
                if (serverHead != null) {
                    // Head slot: no X/Z negation — head bone translateAndRotate already produces Y-up space.
                    poseStack.translate(serverHead.translation()[0]/16, serverHead.translation()[1]/16, serverHead.translation()[2]/16);
                    poseStack.mulPose(new Quaternionf().rotationXYZ(
                            (float)Math.toRadians(serverHead.rotation()[0]),
                            (float)Math.toRadians(serverHead.rotation()[1]),
                            (float)Math.toRadians(serverHead.rotation()[2])));
                    poseStack.scale(serverHead.scale()[0], serverHead.scale()[1], serverHead.scale()[2]);
                } else {
                    CosmeticModel.DisplayTransform head = model.head;
                    poseStack.translate(head.translation()[0]/16, head.translation()[1]/16, head.translation()[2]/16);
                    poseStack.mulPose(new Quaternionf().rotationXYZ(
                            (float)Math.toRadians(head.rotation()[0]),
                            (float)Math.toRadians(head.rotation()[1]),
                            (float)Math.toRadians(head.rotation()[2])));
                    poseStack.scale(head.scale()[0], head.scale()[1], head.scale()[2]);
                }
            } else if ("PET".equals(slot)) {
                // Companion beside the player's feet, not attached to the animated head/body.
                poseStack.translate(0.8, 1.0 + Math.sin(state.ageInTicks * 0.08) * 0.035, 0);
                poseStack.scale(0.55f, -0.55f, -0.55f);
                ApiClient.TransformData serverPet = CosmeticsClient.instance().getTransform(cosmeticId, "pet");
                if (serverPet != null) applyDisplayTransform(poseStack, serverPet);
                applyPetAnimation(poseStack,resource.petAnimation());
            } else {
                getParentModel().body.translateAndRotate(poseStack);
                poseStack.translate(0, 0.3, "BACKPACK".equals(slot) ? 0.30 : 0.16);
                // Java element models use Y-up; player ModelPart coordinates use Y-down.
                poseStack.scale(1, -1, -1);
                // Back-mounted Blockbench models otherwise show their front against the player's body.
                poseStack.mulPose(new Quaternionf().rotationY(CosmeticPlacement.backFacingYawRadians(slot)));
                ApiClient.TransformData serverTransform = CosmeticsClient.instance().getTransform(cosmeticId, slot.toLowerCase());
                if (serverTransform != null) {
                    applyDisplayTransform(poseStack, serverTransform);
                } else if ("BACKPACK".equals(slot)) {
                        var b = model.backpack;
                        poseStack.translate(-b.translation()[0]/16,b.translation()[1]/16,-b.translation()[2]/16);
                        poseStack.mulPose(new Quaternionf().rotationXYZ((float)Math.toRadians(-b.rotation()[0]),(float)Math.toRadians(b.rotation()[1]),(float)Math.toRadians(-b.rotation()[2])));
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

    /** Applies a server-side transform after the back-facing base rotation.
     *  X/Z are converted from the editor convention; scale remains absolute. */
    private static void applyDisplayTransform(PoseStack poseStack, ApiClient.TransformData t) {
        poseStack.translate(-t.translation()[0]/16, t.translation()[1]/16, -t.translation()[2]/16);
        poseStack.mulPose(new Quaternionf().rotationXYZ(
                (float)Math.toRadians(-t.rotation()[0]),
                (float)Math.toRadians(t.rotation()[1]),
                (float)Math.toRadians(-t.rotation()[2])));
        poseStack.scale(t.scale()[0], t.scale()[1], t.scale()[2]);
    }

    private static void applyPetAnimation(PoseStack poseStack, PetAnimation animation) {
        var pose=animation.sample(System.nanoTime()/1_000_000_000.0);
        poseStack.translate(pose.position()[0]/16,pose.position()[1]/16,pose.position()[2]/16);
        poseStack.mulPose(new Quaternionf().rotationXYZ((float)Math.toRadians(pose.rotation()[0]),
                (float)Math.toRadians(pose.rotation()[1]),(float)Math.toRadians(pose.rotation()[2])));
        poseStack.scale(pose.scale()[0],pose.scale()[1],pose.scale()[2]);
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
        if ("CAPE".equals(slot)) {
            poseStack.pushPose();
            try {
                if (!state.chestEquipment.isEmpty()) poseStack.translate(0,-0.053125f,0.06875f);
                getParentModel().copyPropertiesTo(capeModel);
                capeModel.setupAnim(state);
                capeModel.renderToBuffer(poseStack,bufferSource.getBuffer(RenderType.entitySolid(texture)),
                        packedLight,OverlayTexture.NO_OVERLAY);
            } finally { poseStack.popPose(); }
            return;
        }
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(texture));
        poseStack.pushPose();
        getParentModel().root().translateAndRotate(poseStack);
        try { switch (slot) {
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
