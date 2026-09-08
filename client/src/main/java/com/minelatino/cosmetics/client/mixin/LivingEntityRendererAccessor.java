package com.minelatino.cosmetics.client.mixin;

import net.minecraft.client.renderer.entity.layers.RenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Accessor mixin to expose the protected addLayer method from LivingEntityRenderer.
 * This allows us to add custom render layers to the PlayerRenderer.
 */
@Mixin(net.minecraft.client.renderer.entity.LivingEntityRenderer.class)
public interface LivingEntityRendererAccessor {
    @Invoker("addLayer")
    <S extends net.minecraft.client.renderer.entity.state.EntityRenderState> boolean invokeAddLayer(
            RenderLayer<S, ?> layer);
}
