package com.minelatino.cosmetics.client.mixin;

import com.minelatino.cosmetics.client.ClickTracker;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "onPress", at = @At("HEAD"))
    private void minelatino$countClick(long window, int button, int action, int modifiers, CallbackInfo ci) {
        ClickTracker.press(button, action);
    }
}
