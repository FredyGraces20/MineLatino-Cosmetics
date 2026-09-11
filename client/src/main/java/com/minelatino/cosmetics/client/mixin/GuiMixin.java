package com.minelatino.cosmetics.client.mixin;

import com.minelatino.cosmetics.client.HudOverlay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class GuiMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void minelatino$renderHud(GuiGraphics graphics, DeltaTracker tracker, CallbackInfo ci) {
        HudOverlay.render(graphics, false);
    }
}
