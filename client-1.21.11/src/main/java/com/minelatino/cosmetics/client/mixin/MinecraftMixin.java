package com.minelatino.cosmetics.client.mixin;

import com.minelatino.cosmetics.client.CosmeticsClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void minelatino$onTick(CallbackInfo ci) {
        try { CosmeticsClient.instance().tick(); } catch (Exception error) {
            com.minelatino.cosmetics.client.CosmeticsDiagnostics.changed("TICK_FAILED",com.minelatino.cosmetics.client.CosmeticsDiagnostics.failure(error));
        }
    }
}
