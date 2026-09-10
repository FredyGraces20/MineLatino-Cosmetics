package com.minelatino.cosmetics.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.model.player.PlayerCapeModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Quaternionf;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.WeakHashMap;

/**
 * Renders cosmetic overlays on players using 3D models (Blockbench JSON format).
 * Ported for Minecraft 1.21.11:
 *   PlayerRenderState -> AvatarRenderState
 *   PlayerModel -> net.minecraft.client.model.player.PlayerModel
 *   ResourceLocation -> Identifier
 *   RenderType -> RenderTypes (in rendertype subpackage)
 *   render() -> submit() with SubmitNodeCollector
 *   MultiBufferSource -> SubmitNodeCollector
 */
public final class CosmeticRenderer extends RenderLayer<AvatarRenderState, PlayerModel> {
    private static final Logger LOG = LoggerFactory.getLogger("MineLatino Cosmetics");

    static final Map<Integer, UUID> ENTITY_UUID_MAP = new ConcurrentHashMap<>();
    private static final Map<AvatarRenderState, CosmeticPreview.Frame> PREVIEW_FRAMES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static int renderCallCount = 0;
    private final ResourceCache resourceCache;
    private final PlayerCapeModel capeModel = new PlayerCapeModel(PlayerCapeModel.createCapeLayer().bakeRoot());

    public CosmeticRenderer(RenderLayerParent<AvatarRenderState, PlayerModel> parent, ResourceCache resourceCache) {
        super(parent);
        this.resourceCache = resourceCache;
        LOG.info("[CosmeticRenderer] Layer constructed (1.21.11)");
        CosmeticsDiagnostics.event("LAYER_CREATED","CosmeticRenderer");
    }

    public static void putEntityUuid(int entityId, UUID uuid) {
        ENTITY_UUID_MAP.put(entityId, uuid);
    }

    static void registerPreview(AvatarRenderState state, CosmeticPreview.Frame frame) {
        PREVIEW_FRAMES.put(state,frame);
    }

    public static boolean hasReadySkin(AvatarRenderState state){List<EquipmentCache.EquippedItem> equipped=equipmentFor(state);if(equipped==null)return false;for(var item:equipped)if("SKIN".equals(item.slot())){var resource=CosmeticsClient.instance().resources().getOrDownload(item.cosmeticId());return resource!=null&&resource.avatar()!=null;}return false;}

    private static List<EquipmentCache.EquippedItem> equipmentFor(AvatarRenderState state){
        CosmeticPreview.Frame preview=PREVIEW_FRAMES.get(state);
        if(preview!=null)return preview.items;
        UUID uuid=ENTITY_UUID_MAP.get(state.id);
        if(Minecraft.getInstance().player!=null&&Minecraft.getInstance().player.getId()==state.id&&CosmeticsClient.instance().auth().isConnected()&&CosmeticsClient.instance().auth().session()!=null)try{uuid=UUID.fromString(formatUuid(CosmeticsClient.instance().auth().session().uuid()));}catch(IllegalArgumentException ignored){}
        return uuid==null?null:CosmeticsClient.instance().equipment().get(uuid.toString());
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int packedLight,
                       AvatarRenderState renderState, float yaw, float partialTick) {
        renderCallCount++;
        boolean local=Minecraft.getInstance().player!=null && Minecraft.getInstance().player.getId()==renderState.id;
        if (renderState.isInvisible || renderState.isSpectator) {
            if(local) CosmeticsDiagnostics.changed("WORLD_RENDER","skipped invisible="+renderState.isInvisible+" spectator="+renderState.isSpectator);
            return;
        }

        CosmeticPreview.Frame preview = PREVIEW_FRAMES.remove(renderState);
        List<EquipmentCache.EquippedItem> equipped;
        if (preview != null) {
            preview.layerVisited = true;
            CosmeticsDiagnostics.changed("PREVIEW_RENDER","layer active; items="+preview.items.size());
            equipped = preview.items;
        } else {
            UUID uuid = ENTITY_UUID_MAP.get(renderState.id);
            if (Minecraft.getInstance().player != null
                    && Minecraft.getInstance().player.getId() == renderState.id
                    && CosmeticsClient.instance().auth().isConnected()
                    && CosmeticsClient.instance().auth().session() != null) {
                try {
                    uuid = UUID.fromString(formatUuid(CosmeticsClient.instance().auth().session().uuid()));
                } catch (IllegalArgumentException ignored) {}
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
                if(res.avatar()!=null){
                    // 1.21.11 defers submitted model nodes. Force the shared parent
                    // model invisible here as a second guard in case another layer
                    // changed visibility after PlayerModel.setupAnim.
                    getParentModel().setAllVisible(false);
                    renderAvatar(poseStack,collector,packedLight,renderState,res);submitted++;
                }else pendingResources++;
                continue;
            }

            CosmeticModel model = res.model();
            if (model != null && !model.elements.isEmpty()) {
                renderModel(poseStack, collector, packedLight, renderState, res, model, item.slot(), item.cosmeticId());
                if(!model.quads.isEmpty()) submitted++;
            } else {
                renderFallback(poseStack, collector, packedLight, renderState, res.texture(), item.slot());
                submitted++;
            }
        }
        if(local) CosmeticsDiagnostics.changed("WORLD_RENDER","equipped="+equipped.size()+" submitted="+submitted+
                " resourcesUnavailable="+pendingResources+" unsupportedSlots="+unsupported);
    }

    private void renderAvatar(PoseStack stack,SubmitNodeCollector collector,int light,AvatarRenderState state,ResourceCache.CachedResource resource){AvatarPackage avatar=resource.avatar();String clip=AvatarEmoteState.clip(state.id);double seconds=AvatarEmoteState.seconds(state.id);if(clip==null){clip=movementClip(avatar.animations(),state);seconds=state.ageInTicks/20.0;}stack.pushPose();try{stack.translate(0,1.5,0);stack.scale(-1,-1,1);for(AvatarModel.Bone root:avatar.model().roots())renderAvatarBone(stack,collector,light,resource.texture(),avatar,root,clip,seconds,state.ageInTicks/20.0,state.yRot,state.xRot);}finally{stack.popPose();}}
    private static String movementClip(AvatarAnimation animations,AvatarRenderState state){List<String>candidates=state.deathTime>0?List.of("death"):state.isUsingItem?List.of("use_mainhand","use_offhand"):state.attackTime>0?List.of("swing_hand"):state.isFallFlying?List.of("elytra_fly","fly"):state.isVisuallySwimming?List.of("swim"):state.isPassenger?List.of("ride","sit"):state.isCrouching?List.of(state.walkAnimationSpeed>.02f?"sneak":"sneaking"):state.walkAnimationSpeed>.75f?List.of("run","walk"):state.walkAnimationSpeed>.02f?List.of("walk","run"):List.of("idle");for(String c:candidates)for(String n:animations.names())if(n.equals(c)||n.endsWith("."+c))return n;return null;}
    private static void renderAvatarBone(PoseStack stack,SubmitNodeCollector collector,int light,Identifier texture,AvatarPackage avatar,AvatarModel.Bone bone,String clip,double seconds,double ambientSeconds,float headYaw,float headPitch){AvatarAnimation.Pose a=avatar.animations().sampleLayered(clip,bone.name(),seconds,ambientSeconds,headYaw,headPitch);float[]p=bone.pivot(),r=bone.rotation(),ar=a.rotation();stack.pushPose();try{stack.translate((p[0]+a.position()[0])/16.0,(p[1]+a.position()[1])/16.0,(p[2]+a.position()[2])/16.0);stack.mulPose(new Quaternionf().rotationZYX((float)Math.toRadians(-(r[2]+ar[2])),(float)Math.toRadians(-(r[1]+ar[1])),(float)Math.toRadians(r[0]+ar[0])));stack.scale(a.scale()[0],a.scale()[1],a.scale()[2]);stack.translate(-p[0]/16.0,-p[1]/16.0,-p[2]/16.0);for(AvatarModel.Cube cube:bone.cubes())renderAvatarCube(stack,collector,light,texture,avatar.model(),cube);for(AvatarModel.Bone child:avatar.model().children(bone.name()))renderAvatarBone(stack,collector,light,texture,avatar,child,clip,seconds,ambientSeconds,headYaw,headPitch);}finally{stack.popPose();}}
    private static void renderAvatarCube(PoseStack stack,SubmitNodeCollector collector,int light,Identifier texture,AvatarModel model,AvatarModel.Cube cube){stack.pushPose();try{float[]p=cube.pivot(),r=cube.rotation();stack.translate(p[0]/16.0,p[1]/16.0,p[2]/16.0);stack.mulPose(new Quaternionf().rotationZYX((float)Math.toRadians(-r[2]),(float)Math.toRadians(-r[1]),(float)Math.toRadians(r[0])));stack.translate(-p[0]/16.0,-p[1]/16.0,-p[2]/16.0);float x1=cube.origin()[0]/16,y1=cube.origin()[1]/16,z1=cube.origin()[2]/16,x2=(cube.origin()[0]+cube.size()[0])/16,y2=(cube.origin()[1]+cube.size()[1])/16,z2=(cube.origin()[2]+cube.size()[2])/16;for(var face:cube.faces().entrySet()){float[][]v=switch(face.getKey()){case"north"->new float[][]{{x2,y2,z1},{x2,y1,z1},{x1,y1,z1},{x1,y2,z1}};case"south"->new float[][]{{x1,y2,z2},{x1,y1,z2},{x2,y1,z2},{x2,y2,z2}};case"east"->new float[][]{{x2,y2,z2},{x2,y1,z2},{x2,y1,z1},{x2,y2,z1}};case"west"->new float[][]{{x1,y2,z1},{x1,y1,z1},{x1,y1,z2},{x1,y2,z2}};case"up"->new float[][]{{x1,y2,z1},{x1,y2,z2},{x2,y2,z2},{x2,y2,z1}};default->new float[][]{{x1,y1,z2},{x1,y1,z1},{x2,y1,z1},{x2,y1,z2}};};AvatarModel.Face uv=face.getValue();float u1=uv.u()/model.textureWidth(),vv1=uv.v()/model.textureHeight(),u2=(uv.u()+uv.width())/model.textureWidth(),v2=(uv.v()+uv.height())/model.textureHeight();float[]n=normal(v);float[][]vertices={{v[0][0],v[0][1],v[0][2],u1,vv1},{v[1][0],v[1][1],v[1][2],u1,v2},{v[2][0],v[2][1],v[2][2],u2,v2},{v[3][0],v[3][1],v[3][2],u2,vv1}};collector.submitCustomGeometry(stack,RenderTypes.entityCutoutNoCull(texture),(pose,consumer)->{for(float[]vertex:vertices)addVertex(consumer,pose,vertex,light,n);});}}finally{stack.popPose();}}
    private static float[]normal(float[][]v){float ax=v[1][0]-v[0][0],ay=v[1][1]-v[0][1],az=v[1][2]-v[0][2],bx=v[2][0]-v[0][0],by=v[2][1]-v[0][1],bz=v[2][2]-v[0][2];float x=ay*bz-az*by,y=az*bx-ax*bz,z=ax*by-ay*bx,l=(float)Math.sqrt(x*x+y*y+z*z);return l==0?new float[]{0,1,0}:new float[]{x/l,y/l,z/l};}

    private static String formatUuid(String value) {
        String hex = value.replace("-", "");
        if (!hex.matches("[0-9a-fA-F]{32}")) throw new IllegalArgumentException("invalid UUID");
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
                + "-" + hex.substring(16, 20) + "-" + hex.substring(20);
    }

    private void renderModel(PoseStack poseStack, SubmitNodeCollector collector, int packedLight,
                             AvatarRenderState state, ResourceCache.CachedResource resource, CosmeticModel model, String slot, String cosmeticId) {
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
                poseStack.translate(0.8, 1.0 + Math.sin(state.ageInTicks * 0.08) * 0.035, 0);
                poseStack.scale(0.55f, -0.55f, -0.55f);
                ApiClient.TransformData serverPet = CosmeticsClient.instance().getTransform(cosmeticId, "pet");
                if (serverPet != null) applyDisplayTransform(poseStack, serverPet);
                applyPetAnimation(poseStack,resource.petAnimation());
            } else {
                getParentModel().body.translateAndRotate(poseStack);
                poseStack.translate(0, 0.3, "BACKPACK".equals(slot) ? 0.30 : 0.16);
                poseStack.scale(1, -1, -1);
                // Keep 1.21.11 aligned with the 1.21.4 renderer for all back-mounted slots.
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
                if (material == null || (material.animation().rows() == 1 && material.animation().columns() == 1)) {
                    collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(texture), (pose, consumer) -> {
                        renderQuad(consumer, pose, packedLight, quad);
                    });
                } else {
                    var a = material.animation();
                    double ticks = System.nanoTime() / 50_000_000.0;
                    CosmeticModel.Quad animQuad = new CosmeticModel.Quad(a.vertex(quad.v0(),ticks),a.vertex(quad.v1(),ticks),
                            a.vertex(quad.v2(),ticks),a.vertex(quad.v3(),ticks),quad.normal(),quad.texture());
                    collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(texture), (pose, consumer) -> {
                        renderQuad(consumer, pose, packedLight, animQuad);
                    });
                }
            }
        } finally {
            poseStack.popPose();
        }
    }

    /** Applies a server-side transform after the back-facing base rotation. */
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

    private static void renderQuad(VertexConsumer consumer, PoseStack.Pose pose, int packedLight, CosmeticModel.Quad quad) {
        float[] n = quad.normal();
        addVertex(consumer, pose, quad.v0(), packedLight, n);
        addVertex(consumer, pose, quad.v1(), packedLight, n);
        addVertex(consumer, pose, quad.v2(), packedLight, n);
        addVertex(consumer, pose, quad.v3(), packedLight, n);
    }

    private static void addVertex(VertexConsumer consumer, PoseStack.Pose pose, float[] v, int light, float[] normal) {
        consumer.addVertex(pose, v[0], v[1], v[2])
                .setColor(255, 255, 255, 255)
                .setUv(v[3], v[4])
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, normal[0], normal[1], normal[2]);
    }

    private void renderFallback(PoseStack poseStack, SubmitNodeCollector collector, int packedLight,
                                AvatarRenderState state, Identifier texture, String slot) {
        if ("CAPE".equals(slot)) {
            poseStack.pushPose();
            try {
                if (!state.chestEquipment.isEmpty()) poseStack.translate(0,-0.053125f,0.06875f);
                collector.submitModel(capeModel,state,poseStack,RenderTypes.entitySolid(texture),packedLight,
                        OverlayTexture.NO_OVERLAY,state.outlineColor,null);
            } finally { poseStack.popPose(); }
            return;
        }
        poseStack.pushPose();
        getParentModel().root().translateAndRotate(poseStack);
        try { switch (slot) {
            case "HAT" -> collector.submitCustomGeometry(poseStack, RenderTypes.entityCutoutNoCull(texture), (pose, c) -> {
                drawQuad(c, pose, packedLight, -0.3f, 0.3f, -0.3f, 0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 0, 0, -1);
            });
            case "WINGS" -> collector.submitCustomGeometry(poseStack, RenderTypes.entityCutoutNoCull(texture), (pose, c) -> {
                drawQuad(c, pose, packedLight, -0.35f, 0.0f, -0.3f, 0.3f, 0.0f, 0.5f, 0.0f, 1.0f, 0, 0, -1);
                drawQuad(c, pose, packedLight, 0.0f, 0.35f, -0.3f, 0.3f, 0.5f, 1.0f, 0.0f, 1.0f, 0, 0, -1);
            });
            case "BACKPACK", "PET" -> {
                if ("BACKPACK".equals(slot)) {
                    getParentModel().body.translateAndRotate(poseStack);
                    poseStack.translate(0, 0.3, 0.16);
                } else poseStack.translate(0.8, 1.0, 0);
                collector.submitCustomGeometry(poseStack, RenderTypes.entityCutoutNoCull(texture), (pose, c) -> {
                    drawQuad(c, pose, packedLight, -.22f, .22f, -.22f, .22f, 0, 1, 0, 1, 0, 0, -1);
                });
            }
        } } finally { poseStack.popPose(); }
    }

    private static void drawQuad(VertexConsumer consumer, PoseStack.Pose pose, int packedLight,
                                  float x1, float x2, float y1, float y2,
                                  float u1, float u2, float v1, float v2,
                                  int nx, int ny, int nz) {
        consumer.addVertex(pose, x1, y1, 0).setColor(255, 255, 255, 255).setUv(u1, v1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(packedLight).setNormal(pose, nx, ny, nz);
        consumer.addVertex(pose, x1, y2, 0).setColor(255, 255, 255, 255).setUv(u1, v2).setOverlay(OverlayTexture.NO_OVERLAY).setLight(packedLight).setNormal(pose, nx, ny, nz);
        consumer.addVertex(pose, x2, y2, 0).setColor(255, 255, 255, 255).setUv(u2, v2).setOverlay(OverlayTexture.NO_OVERLAY).setLight(packedLight).setNormal(pose, nx, ny, nz);
        consumer.addVertex(pose, x2, y1, 0).setColor(255, 255, 255, 255).setUv(u2, v1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(packedLight).setNormal(pose, nx, ny, nz);
    }
}
