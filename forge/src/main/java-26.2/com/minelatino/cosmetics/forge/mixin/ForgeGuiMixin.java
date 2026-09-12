package com.minelatino.cosmetics.forge.mixin;

import com.minelatino.cosmetics.client.HudOverlay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public abstract class ForgeGuiMixin {
    /** Official name in development, SRG name in a packaged Forge client. */
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void minelatino$renderHud(GuiGraphicsExtractor graphics, DeltaTracker tracker, CallbackInfo ci) {
        HudOverlay.render(graphics, false);
    }
}
