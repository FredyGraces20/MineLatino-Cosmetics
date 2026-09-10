package com.minelatino.cosmetics.forge;

import com.minelatino.cosmetics.client.CosmeticsClient;
import com.minelatino.cosmetics.client.CosmeticsDiagnostics;
import com.minelatino.cosmetics.client.PauseMenu;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "minelatino_cosmetics", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientEvents {
    private ClientEvents() {}

    @SubscribeEvent
    public static void afterScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof PauseScreen screen) PauseMenu.install(screen, event::addListener);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent.Post event) {
        try {
            CosmeticsClient.instance().tick();
        } catch (Exception error) {
            CosmeticsDiagnostics.changed("TICK_FAILED", CosmeticsDiagnostics.failure(error));
        }
    }
}
