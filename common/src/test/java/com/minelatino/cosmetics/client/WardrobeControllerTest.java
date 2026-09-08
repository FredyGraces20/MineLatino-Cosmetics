package com.minelatino.cosmetics.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WardrobeControllerTest {
    private static final String UUID="1234567890abcdef1234567890abcdef";
    private static final Session SESSION=new Session("test",UUID,"Test",Long.MAX_VALUE);
    private static final ApiClient.CosmeticItem HAT=new ApiClient.CosmeticItem("hat","Sombrero","HAT","published",1);
    private static class Queue implements Executor {
        final ArrayDeque<Runnable> tasks=new ArrayDeque<>();
        public void execute(Runnable task) { tasks.add(task); }
        void run() { tasks.remove().run(); }
    }
    private static class Gateway implements WardrobeController.Gateway {
        List<ApiClient.EquippedEntry> equipment=List.of();
        String owner=UUID;
        int writes;
        Exception error;
        boolean ignoreWrite;
        public ApiClient.WardrobeResponse wardrobe(String token) throws Exception {
            if(error!=null) throw error;
            return new ApiClient.WardrobeResponse(owner,List.of(HAT),equipment);
        }
        public ApiClient.EquipResponse equip(String token,String slot,String id) throws Exception {
            writes++; if(error!=null) throw error;
            if(!ignoreWrite) equipment=id==null ? List.of() : List.of(new ApiClient.EquippedEntry(slot,id));
            return new ApiClient.EquipResponse(equipment);
        }
    }
    private final Gateway gateway=new Gateway();
    private final Queue worker=new Queue();
    private final List<List<ApiClient.EquippedEntry>> published=new ArrayList<>();
    private final WardrobeController controller=new WardrobeController(gateway,worker,Runnable::run,(id,items)->{
        assertEquals(UUID,id); published.add(items);
    });
    private void load() { controller.connect(SESSION); worker.run(); }

    @Test void openingPublishesPreviouslyEquippedItemsToWorldCache() {
        gateway.equipment=List.of(new ApiClient.EquippedEntry("HAT","hat")); load();
        assertEquals("hat",controller.snapshot().equipped().get("HAT"));
        assertEquals(gateway.equipment,published.getFirst());
    }
    @Test void doubleClickAndResizeCannotStartAnotherWrite() {
        load(); assertTrue(controller.equip("HAT","hat"));
        assertFalse(controller.equip("HAT",null)); controller.connect(SESSION); controller.reload();
        assertEquals(1,worker.tasks.size());
        assertTrue(controller.snapshot().equipped().isEmpty());
        worker.run(); assertEquals(1,gateway.writes);
        assertEquals("hat",controller.snapshot().equipped().get("HAT"));
    }
    @Test void completionPublishesWithoutAnyScreenOrTickPolling() {
        load(); controller.equip("HAT","hat"); worker.run();
        assertEquals(2,published.size()); assertEquals("hat",published.getLast().getFirst().cosmeticId());
    }
    @Test void unequipIsConfirmedAndRemovesPreviewSource() {
        load(); controller.equip("HAT","hat"); worker.run();
        assertTrue(controller.equip("HAT",null)); worker.run();
        assertTrue(controller.snapshot().equipped().isEmpty()); assertTrue(published.getLast().isEmpty());
    }
    @Test void failedWritePreservesSavedEquipmentAndAllowsReload() {
        gateway.equipment=List.of(new ApiClient.EquippedEntry("HAT","hat")); load();
        gateway.error=new ApiClient.ApiException(409,"test"); controller.equip("HAT",null); worker.run();
        assertEquals(WardrobeController.Phase.ERROR,controller.snapshot().phase());
        assertEquals("hat",controller.snapshot().equipped().get("HAT")); assertEquals(1,published.size());
        gateway.error=null; controller.reload(); worker.run();
        assertEquals(WardrobeController.Phase.READY,controller.snapshot().phase());
    }
    @Test void expiredBackendSessionRequiresRelinking() {
        load(); gateway.error=new ApiClient.ApiException(401,"test"); controller.equip("HAT","hat"); worker.run();
        assertEquals(WardrobeController.Phase.DISCONNECTED,controller.snapshot().phase());
        assertTrue(controller.snapshot().message().contains("caducada"));
    }
    @Test void disconnectedOldResponseIsIgnored() {
        load(); controller.equip("HAT","hat"); controller.disconnect(); worker.run();
        assertEquals(WardrobeController.Phase.DISCONNECTED,controller.snapshot().phase());
        assertEquals(1,published.size());
    }
    @Test void unknownCategoryAndUnownedItemNeverReachNetwork() {
        load(); assertFalse(controller.equip("PET","hat"));
        assertFalse(controller.equip("HAT","not-owned")); assertFalse(controller.equip("INVALID",null));
        assertEquals(0,worker.tasks.size());
    }
    @Test void ownerMismatchCannotPopulateTheWorldCache() {
        gateway.owner="abcdef1234567890abcdef1234567890"; load();
        assertEquals(WardrobeController.Phase.ERROR,controller.snapshot().phase()); assertTrue(published.isEmpty());
    }
    @Test void normalizeDashedUppercaseIdentity() {
        assertEquals(UUID,WardrobeController.normalize("12345678-90AB-CDEF-1234-567890ABCDEF"));
    }
    @Test void slotsIncludeBackpackAndPet() {
        assertEquals("Mochila",CosmeticSlot.label("BACKPACK"));
        assertEquals("Mascota",CosmeticSlot.label("PET")); assertTrue(CosmeticSlot.from("OTHER").isEmpty());
    }
    @Test void successfulHttpWithoutConfirmedEquipmentIsNotReportedAsSaved() {
        load(); gateway.ignoreWrite=true; controller.equip("HAT","hat"); worker.run();
        assertEquals(WardrobeController.Phase.ERROR,controller.snapshot().phase());
        assertTrue(controller.snapshot().message().contains("no confirmó"));
        assertEquals(1,published.size());
    }
}
