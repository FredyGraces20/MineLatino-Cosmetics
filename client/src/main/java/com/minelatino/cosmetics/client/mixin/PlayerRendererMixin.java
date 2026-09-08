package com.minelatino.cosmetics.client.mixin;

import com.minelatino.cosmetics.client.CosmeticRenderer;
import com.minelatino.cosmetics.client.CosmeticsClient;
import com.minelatino.cosmetics.client.ResourceCache;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into PlayerRenderer to:
 * 1. Add the CosmeticRenderer layer after construction
 * 2. Capture player UUID during extractRenderState for cosmetic lookup
 */
@Mixin(PlayerRenderer.class)
public abstract class PlayerRendererMixin {

    @Inject(method = "<init>", at = @At("TAIL"))
    private void minelatino$addCosmeticLayer(CallbackInfo ci) {
        try {
            @SuppressWarnings("unchecked")
            LivingEntityRendererAccessor accessor = (LivingEntityRendererAccessor) this;
            ResourceCache cache = CosmeticsClient.instance().resources();
            accessor.invokeAddLayer(new CosmeticRenderer(
                    (net.minecraft.client.renderer.entity.RenderLayerParent<PlayerRenderState, PlayerModel>) this,
                    cache));
        } catch (Exception e) {
            // Don't crash the game if cosmetics fail to initialize
            org.slf4j.LoggerFactory.getLogger("MineLatino Cosmetics")
                    .error("Failed to add cosmetic layer", e);
        }
    }

    @Inject(method = "extractRenderState(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/client/renderer/entity/state/PlayerRenderState;F)V",
            at = @At("TAIL"))
    private void minelatino$captureUuid(AbstractClientPlayer player, PlayerRenderState state, float partialTick, CallbackInfo ci) {
        // Store UUID without hyphens to match backend format
        CosmeticRenderer.putEntityUuid(state.id, player.getGameProfile().getId());
    }
}
