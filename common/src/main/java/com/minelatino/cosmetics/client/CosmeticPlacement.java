package com.minelatino.cosmetics.client;

/** Shared placement rules used by every supported Minecraft renderer. */
public final class CosmeticPlacement {
    private CosmeticPlacement() {}

    /**
     * Companion anchor in player-model space.  Keep it outside the maximum arm
     * swing so shoulder-height pets do not appear behind the player's hand.
     */
    public static final float PET_X = 1.15f;
    public static final float PET_Y = 1.0f;
    public static final float PET_Z = 0.0f;
    public static final float PET_SCALE = 0.55f;

    /** Blockbench's front faces the player after attachment unless back-mounted models are turned around. */
    public static boolean needsBackFacingRotation(String slot) {
        return "BACKPACK".equals(slot) || "CAPE".equals(slot) || "WINGS".equals(slot);
    }

    public static float backFacingYawRadians(String slot) {
        return needsBackFacingRotation(slot) ? (float) Math.PI : 0f;
    }
}
