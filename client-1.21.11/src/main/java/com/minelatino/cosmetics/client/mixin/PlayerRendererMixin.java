package com.minelatino.cosmetics.client.mixin;

import com.minelatino.cosmetics.client.CosmeticRenderer;
import com.minelatino.cosmetics.client.CosmeticsClient;
import com.minelatino.cosmetics.client.ResourceCache;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into AvatarRenderer (1.21.11 renamed from PlayerRenderer) to:
 * 1. Add the CosmeticRenderer layer after construction
 * 2. Capture player UUID during extractRenderState for cosmetic lookup
 */
@Mixin(AvatarRenderer.class)
public abstract class PlayerRendererMixin {

    @Inject(method = "<init>", at = @At("TAIL"))
    private void minelatino$addCosmeticLayer(CallbackInfo ci) {
        try {
            @SuppressWarnings("unchecked")
            LivingEntityRendererAccessor accessor = (LivingEntityRendererAccessor) this;
            ResourceCache cache = CosmeticsClient.instance().resources();
            accessor.invokeAddLayer(new CosmeticRenderer(
                    (net.minecraft.client.renderer.entity.RenderLayerParent<AvatarRenderState, PlayerModel>) this,
                    cache));
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger("MineLatino Cosmetics")
                    .error("Failed to add cosmetic layer", e);
        }
    }

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL"))
    private void minelatino$captureUuid(net.minecraft.world.entity.Avatar avatar, AvatarRenderState state, float partialTick, CallbackInfo ci) {
        if (avatar instanceof AbstractClientPlayer player) {
            CosmeticRenderer.putEntityUuid(state.id, player.getGameProfile().id());
        }
    }
}
