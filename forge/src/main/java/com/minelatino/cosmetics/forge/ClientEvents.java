package com.minelatino.cosmetics.forge;

import com.minelatino.cosmetics.client.CosmeticsClient;
import com.minelatino.cosmetics.client.PauseMenu;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge FORGE-bus event handlers for the cosmetics mod.
 * - Screen button injection via ScreenEvent
 * - Client tick delegation via ClientTickEvent
 *
 * Layer registration is handled in ClientModEvents (MOD bus) via EntityRenderersEvent.AddLayers.
 * UUID map population is handled in CosmeticsClient.tick() called from onClientTick below.
 */
@Mod.EventBusSubscriber(modid = "minelatino_cosmetics", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientEvents {

    @SubscribeEvent
    public static void afterScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof PauseScreen screen) PauseMenu.install(screen, event::addListener);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            try { CosmeticsClient.instance().tick(); } catch (Exception error) {
                com.minelatino.cosmetics.client.CosmeticsDiagnostics.changed("TICK_FAILED",com.minelatino.cosmetics.client.CosmeticsDiagnostics.failure(error));
            }
        }
    }
}
