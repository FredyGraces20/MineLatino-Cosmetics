package com.minelatino.cosmetics.client.mixin;

import net.minecraft.client.renderer.entity.layers.RenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Accessor mixin to expose the protected addLayer method from LivingEntityRenderer.
 * Ported for 1.21.11: generic bounds updated to AvatarRenderState hierarchy.
 */
@Mixin(net.minecraft.client.renderer.entity.LivingEntityRenderer.class)
public interface LivingEntityRendererAccessor {
    @Invoker("addLayer")
    <S extends net.minecraft.client.renderer.entity.state.EntityRenderState> boolean invokeAddLayer(
            RenderLayer<S, ?> layer);
}
