package com.minelatino.cosmetics.forge;

import com.minelatino.cosmetics.client.CosmeticRenderer;
import com.minelatino.cosmetics.client.CosmeticsClient;
import com.minelatino.cosmetics.client.ResourceCache;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Forge MOD-bus event handler for client-side layer registration.
 * EntityRenderersEvent.AddLayers fires after player renderers are created,
 * giving us access to add our custom CosmeticRenderer layer.
 */
@Mod.EventBusSubscriber(modid = "minelatino_cosmetics", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientModEvents {

    private ClientModEvents() {}

    @SubscribeEvent
    public static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        ResourceCache cache = CosmeticsClient.instance().resources();
        for (PlayerSkin.Model skinModel : event.getSkins()) {
            try {
                PlayerRenderer renderer = (PlayerRenderer) event.getPlayerSkin(skinModel);
                addLayer(renderer, cache);
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger("MineLatino Cosmetics")
                        .error("Failed to add cosmetic layer for skin {}", skinModel, e);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void addLayer(PlayerRenderer renderer, ResourceCache cache) throws Exception {
        if (renderer == null) return;

        CosmeticRenderer layer = new CosmeticRenderer(
                (RenderLayerParent<PlayerRenderState, PlayerModel>) (RenderLayerParent) renderer,
                cache);

        // Access the private 'layers' field from LivingEntityRenderer via reflection
        Class<?> clazz = renderer.getClass().getSuperclass(); // LivingEntityRenderer
        Field layersField = null;
        while (clazz != null) {
            try {
                layersField = clazz.getDeclaredField("layers");
                break;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        if (layersField == null) throw new NoSuchFieldException("layers");

        layersField.setAccessible(true);
        List layers = (List) layersField.get(renderer);
        // Run before Minecraft's CapeLayer (also patched by OptiFine), so the
        // MineLatino layer can hide it when our CAPE slot is occupied.
        layers.add(0, layer);
        org.slf4j.LoggerFactory.getLogger("MineLatino Cosmetics")
                .info("Forge: CosmeticRenderer layer added before built-in cape layers");
    }
}
