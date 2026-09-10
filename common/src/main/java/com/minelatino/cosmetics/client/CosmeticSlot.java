package com.minelatino.cosmetics.client;

import java.util.Optional;

/** Wire IDs must agree with the service; labels are presentation only. */
public enum CosmeticSlot {
    HAT("Cabeza"), CAPE("Capa"), WINGS("Alas"), BACKPACK("Mochila"), PET("Mascota"), SKIN("Skins");
    private final String label;
    CosmeticSlot(String label) { this.label = label; }
    public String label() { return label; }
    public static Optional<CosmeticSlot> from(String value) {
        try { return Optional.of(valueOf(value)); }
        catch (IllegalArgumentException | NullPointerException ignored) { return Optional.empty(); }
    }
    public static String label(String value) { return from(value).map(CosmeticSlot::label).orElse("No compatible"); }
}
