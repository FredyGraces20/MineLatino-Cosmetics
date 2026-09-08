package com.minelatino.cosmetics.client;

/** Shared placement rules used by every supported Minecraft renderer. */
public final class CosmeticPlacement {
    private CosmeticPlacement() {}

    /** Blockbench's front faces the player after attachment unless back-mounted models are turned around. */
    public static boolean needsBackFacingRotation(String slot) {
        return "BACKPACK".equals(slot) || "CAPE".equals(slot) || "WINGS".equals(slot);
    }

    public static float backFacingYawRadians(String slot) {
        return needsBackFacingRotation(slot) ? (float) Math.PI : 0f;
    }
}
