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
        method={
            "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V",
            "method_62110(Lnet/minecraft/class_10055;)V"
        },
        at=@At("TAIL"), require=0, remap=false
    )
    private void minelatino$toggleVanillaBody(AvatarRenderState state,CallbackInfo ci){((PlayerModel)(Object)this).setAllVisible(!CosmeticRenderer.hasReadySkin(state));}
}
