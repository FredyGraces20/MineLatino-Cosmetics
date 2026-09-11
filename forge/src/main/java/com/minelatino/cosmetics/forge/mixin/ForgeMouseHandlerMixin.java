package com.minelatino.cosmetics.forge.mixin;

import com.minelatino.cosmetics.client.ClickTracker;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class ForgeMouseHandlerMixin {
    /** Official name in development, SRG name in a packaged Forge client. */
    @Inject(method = {"onPress", "m_91530_"}, at = @At("HEAD"), remap = false)
    private void minelatino$countClick(long window, int button, int action, int modifiers, CallbackInfo ci) {
        ClickTracker.press(button, action);
    }
}
