package com.minelatino.cosmetics.client;

/** Shared placement rules used by every supported Minecraft renderer. */
public final class CosmeticPlacement {
    private CosmeticPlacement() {}

    /**
     * Companion anchor in Minecraft's player-model space. The web editor uses
     * a centered, Y-up player, so its Y=1 top anchor maps to Y=-0.5 here where
     * Y grows down from the model head. Keep X outside the maximum arm swing.
     */
    public static final float PET_X = 1.15f;
    public static final float PET_Y = -0.5f;
    public static final float PET_Z = 0.0f;
    public static final float PET_SCALE = 0.55f;

    /**
     * The direct geometry renderer used by both the armory preview and the
     * equipped companion has the same forward axis, so both need the same
     * half-turn from Blockbench space.
     */
    public static float petYawRadians(boolean previewRender) {
        return (float) Math.PI;
    }

    /** Blockbench's front faces the player after attachment unless back-mounted models are turned around. */
    public static boolean needsBackFacingRotation(String slot) {
        return "BACKPACK".equals(slot) || "CAPE".equals(slot) || "WINGS".equals(slot);
    }

    public static float backFacingYawRadians(String slot) {
        return needsBackFacingRotation(slot) ? (float) Math.PI : 0f;
    }
}
