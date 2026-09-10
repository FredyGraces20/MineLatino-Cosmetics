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
    // Let each loader remap the named method. The old remap=false selector was not
    // present in Fabric's generated refmap, so vanilla geometry could remain visible.
    // Keep require=0 so a future mapping change fails open instead of crashing.
    @Inject(
        method="setupAnim",
        at=@At("TAIL"), require=0
    )
    private void minelatino$toggleVanillaBody(PlayerRenderState state, CallbackInfo ci){
        ((PlayerModel)(Object)this).setAllVisible(!CosmeticRenderer.hasReadySkin(state));
    }
}
