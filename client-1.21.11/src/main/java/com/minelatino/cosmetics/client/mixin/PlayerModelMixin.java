package com.minelatino.cosmetics.client.mixin;

import com.minelatino.cosmetics.client.CosmeticRenderer;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerModel.class)
public abstract class PlayerModelMixin {
    @Inject(
        method="setupAnim",
        at=@At("TAIL"), require=0
    )
    private void minelatino$toggleVanillaBody(AvatarRenderState state,CallbackInfo ci){((PlayerModel)(Object)this).setAllVisible(!CosmeticRenderer.hasReadySkin(state));}
}
