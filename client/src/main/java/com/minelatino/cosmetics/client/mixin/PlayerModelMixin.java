package com.minelatino.cosmetics.client.mixin;

import com.minelatino.cosmetics.client.CosmeticRenderer;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces, rather than overlays, the vanilla player after the animated skin is ready. */
@Mixin(PlayerModel.class)
public abstract class PlayerModelMixin {
    @Inject(method="setupAnim(Lnet/minecraft/client/renderer/entity/state/PlayerRenderState;)V",at=@At("TAIL"))
    private void minelatino$toggleVanillaBody(PlayerRenderState state, CallbackInfo ci){
        ((PlayerModel)(Object)this).setAllVisible(!CosmeticRenderer.hasReadySkin(state));
    }
}
