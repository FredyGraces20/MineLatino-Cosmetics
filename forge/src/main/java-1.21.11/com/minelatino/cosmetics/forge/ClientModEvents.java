package com.minelatino.cosmetics.forge;

import com.minelatino.cosmetics.client.CosmeticRenderer;
import com.minelatino.cosmetics.client.CosmeticsClient;
import com.minelatino.cosmetics.client.ResourceCache;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "minelatino_cosmetics", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientModEvents {
    private ClientModEvents() {}

    @SubscribeEvent
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        ResourceCache cache = CosmeticsClient.instance().resources();
        for (PlayerModelType modelType : event.getModelTypes()) {
            try {
                AvatarRenderer renderer = (AvatarRenderer) event.getPlayerRenderer(modelType);
                if (renderer == null) continue;
                CosmeticRenderer layer = new CosmeticRenderer(
                        (RenderLayerParent<AvatarRenderState, PlayerModel>) (RenderLayerParent) renderer,
                        cache);
                renderer.addLayer(layer);
                org.slf4j.LoggerFactory.getLogger("MineLatino Cosmetics")
                        .info("Forge: cosmetic layer added for player model {}", modelType);
            } catch (Exception error) {
                org.slf4j.LoggerFactory.getLogger("MineLatino Cosmetics")
                        .error("Failed to add cosmetic layer for player model {}", modelType, error);
            }
        }
    }
}
