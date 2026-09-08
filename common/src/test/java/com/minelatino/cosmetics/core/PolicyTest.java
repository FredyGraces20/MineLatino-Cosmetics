package com.minelatino.cosmetics.core;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PolicyTest {
    @Test void permitsOnlyTrustedHttps() {
        assertEquals("minelatino.com", MenuPolicy.website("https://minelatino.com/tienda").getHost());
        for (String url : Set.of("http://minelatino.com", "https://minelatino.com.attacker.test", "file:///tmp/a",
            "https://attacker@minelatino.com", "https://minelatino.com:8080", "javascript:alert(1)"))
            assertThrows(IllegalArgumentException.class, () -> MenuPolicy.website(url));
    }
    @Test void restrictsLabelsAndVanillaTargets() {
        assertEquals("Cosméticos", MenuPolicy.label(" Cosméticos "));
        assertThrows(IllegalArgumentException.class, () -> MenuPolicy.label("\nComando"));
        assertThrows(IllegalArgumentException.class, () -> MenuPolicy.label("a".repeat(41)));
        assertTrue(MenuPolicy.canRename("menu.returnToGame"));
        assertFalse(MenuPolicy.canRename("arbitrary.translation"));
    }
    @Test void rejectsUnownedEquipment() {
        assertThrows(IllegalArgumentException.class, () -> new Wardrobe(UUID.randomUUID(), Set.of(), Map.of(Wardrobe.Slot.CAPE, "cape")));
    }
    @Test void ownershipSnapshotsAreImmutable() {
        var wardrobe = new Wardrobe(UUID.randomUUID(), Set.of("cape"), Map.of(Wardrobe.Slot.CAPE, "cape"));
        assertThrows(UnsupportedOperationException.class, () -> wardrobe.owned().clear());
        assertThrows(UnsupportedOperationException.class, () -> wardrobe.equipped().clear());
    }
}
