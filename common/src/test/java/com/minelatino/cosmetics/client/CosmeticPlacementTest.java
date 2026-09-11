package com.minelatino.cosmetics.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CosmeticPlacementTest {
    @Test void petAnchorClearsThePlayerArm() {
        assertTrue(CosmeticPlacement.PET_X >= 1.0f);
        assertEquals(1.0f, CosmeticPlacement.PET_Y);
        assertEquals(0.55f, CosmeticPlacement.PET_SCALE);
    }

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
