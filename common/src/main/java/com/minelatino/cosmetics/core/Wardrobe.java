package com.minelatino.cosmetics.core;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** DTO contract for backend responses, not an authentication or ownership authority. */
public record Wardrobe(UUID owner, Set<String> owned, Map<Slot, String> equipped) {
    public enum Slot { CAPE, HAT, WINGS, BACKPACK, PET }
    public Wardrobe {
        java.util.Objects.requireNonNull(owner);
        owned = Set.copyOf(owned);
        equipped = Map.copyOf(equipped);
        if (!owned.containsAll(equipped.values()))
            throw new IllegalArgumentException("Equipped cosmetic is not owned");
    }
}
