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

import java.lang.reflect.Field;
import java.util.List;

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
                addLayerFirst(renderer, layer);
                org.slf4j.LoggerFactory.getLogger("MineLatino Cosmetics")
                        .info("Forge: cosmetic layer added before built-in cape layers for player model {}", modelType);
            } catch (Exception error) {
                org.slf4j.LoggerFactory.getLogger("MineLatino Cosmetics")
                        .error("Failed to add cosmetic layer for player model {}", modelType, error);
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void addLayerFirst(AvatarRenderer renderer, CosmeticRenderer layer) throws Exception {
        Class<?> type = renderer.getClass();
        Field layersField = null;
        while (type != null) {
            try {
                layersField = type.getDeclaredField("layers");
                break;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        if (layersField == null) throw new NoSuchFieldException("layers");
        layersField.setAccessible(true);
        ((List) layersField.get(renderer)).add(0, layer);
    }
}
