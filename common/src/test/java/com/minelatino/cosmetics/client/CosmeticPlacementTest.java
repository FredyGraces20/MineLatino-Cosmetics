package com.minelatino.cosmetics.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CosmeticPlacementTest {
    @Test void backMountedModelsFaceAwayFromThePlayer() {
        for (String slot : new String[]{"BACKPACK", "CAPE", "WINGS"}) {
            assertTrue(CosmeticPlacement.needsBackFacingRotation(slot));
            assertEquals((float) Math.PI, CosmeticPlacement.backFacingYawRadians(slot));
        }
    }

    @Test void headAndPetKeepTheirExistingOrientation() {
        for (String slot : new String[]{"HAT", "PET"}) {
            assertFalse(CosmeticPlacement.needsBackFacingRotation(slot));
            assertEquals(0f, CosmeticPlacement.backFacingYawRadians(slot));
        }
    }
}
