package com.minelatino.cosmetics.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Caches player appearances (equipped cosmetics) with a TTL.
 * Queries are batched: never one request per frame. The cache is populated
 * on demand and refreshed after the TTL expires.
 *
 * This is a read-through cache for rendering other players' cosmetics.
 * The local player's equipment is always authoritative from the wardrobe API.
 */
public final class EquipmentCache {
    private static final Logger LOG = LoggerFactory.getLogger("MineLatino Cosmetics");
    private static final long TTL_MS = 60_000; // 1 minute
    private static final long MAX_STALE_MS = 120_000;

    public record EquippedItem(String slot, String cosmeticId) {}

    private final ApiClient api;
    private final Map<String, CachedEntry> cache = new ConcurrentHashMap<>();
    private long mutationVersion;

    private record CachedEntry(List<EquippedItem> items, long fetchedAt) {}

    public EquipmentCache(ApiClient api) {
        this.api = api;
    }

    /**
     * Returns the cached appearance for a UUID, or null if not cached / expired.
     * Does not make a network request; call refresh() to populate.
     */
    public List<EquippedItem> get(String uuid) {
        uuid = WardrobeController.normalize(uuid);
        CachedEntry entry = cache.get(uuid);
        // Keep the last good appearance during revalidation; cap stale data on outages.
        if (entry == null || System.currentTimeMillis() - entry.fetchedAt > MAX_STALE_MS) {
            return List.of();
        }
        return entry.items;
    }

    /** Returns whether the cached appearance currently occupies a cosmetic slot. */
    public boolean hasEquippedSlot(String uuid, String slot) {
        if (slot == null) return false;
        return get(uuid).stream().anyMatch(item -> slot.equals(item.slot()));
    }

    /**
     * Fetches appearances for the given UUIDs in a single batch request.
     * Skips UUIDs that are already cached and not expired.
     */
    public void refresh(List<String> uuids) {
        refresh(uuids, Map.of());
    }

    /** Refreshes server UUIDs and, for offline-mode identities, their verified names. */
    public void refresh(List<String> uuids, Map<String, String> namesByServerUuid) {
        long requestVersion;
        synchronized (this) { requestVersion = mutationVersion; }
        List<String> needed = new ArrayList<>();
        for (String rawUuid : uuids) {
            String uuid = WardrobeController.normalize(rawUuid);
            CachedEntry entry = cache.get(uuid);
            if (entry == null || System.currentTimeMillis() - entry.fetchedAt > TTL_MS) {
                needed.add(uuid);
            }
        }
        if (needed.isEmpty()) return;

        try {
            List<String> names = needed.stream().map(namesByServerUuid::get)
                    .filter(name -> name != null && name.matches("[A-Za-z0-9_]{3,16}"))
                    .distinct().toList();
            ApiClient.AppearanceResponse response = api.appearance(needed, names);
            long now = System.currentTimeMillis();
            synchronized (this) {
            // A late public read must never undo a newer wardrobe write or invalidation.
            if (requestVersion != mutationVersion) {
                CosmeticsDiagnostics.event("CACHE_STALE_RESPONSE","ignored after newer equipment change");
                return;
            }
            Set<String> returned = new HashSet<>();
            for (ApiClient.PlayerAppearance player : response.players()) {
                if (player.uuid() == null || !WardrobeController.normalize(player.uuid()).matches("[a-f0-9]{32}")) {
                    throw new IllegalArgumentException("Appearance response contains an invalid UUID");
                }
                List<EquippedItem> items = player.equipped() == null ? List.of()
                    : player.equipped().stream().filter(e -> e != null && CosmeticSlot.from(e.slot()).isPresent()).map(e -> {
                        if (e == null || e.slot() == null || !Set.of("CAPE", "HAT", "WINGS", "BACKPACK", "PET").contains(e.slot())
                                || e.cosmeticId() == null || !e.cosmeticId().matches("[a-z0-9_-]{1,64}")) {
                            throw new IllegalArgumentException("Appearance response contains invalid equipment");
                        }
                        return new EquippedItem(e.slot(), e.cosmeticId());
                    }).toList();
                String premiumUuid = WardrobeController.normalize(player.uuid());
                cache.put(premiumUuid, new CachedEntry(items, now));
                returned.add(premiumUuid);
            }
            // Apply name aliases after direct UUID results so an offline UUID's
            // empty direct result can never overwrite its premium appearance.
            for (ApiClient.PlayerAppearance player : response.players()) {
                if (player.name() != null) {
                    for (var requested : namesByServerUuid.entrySet()) {
                        if (player.name().equalsIgnoreCase(requested.getValue())) {
                            String serverUuid = WardrobeController.normalize(requested.getKey());
                            CachedEntry premium = cache.get(WardrobeController.normalize(player.uuid()));
                            if (premium == null) throw new IllegalArgumentException("Appearance alias has no premium entry");
                            cache.put(serverUuid, premium);
                            returned.add(serverUuid);
                        }
                    }
                }
            }
            // Cache an empty appearance too. Otherwise players without cosmetics
            // are requested forever and multiply traffic in busy lobbies.
            for (String uuid : needed) {
                if (!returned.contains(uuid)) cache.put(uuid, new CachedEntry(List.of(), now));
            }
            }
            CosmeticsDiagnostics.event("APPEARANCE_REFRESH","requested="+needed.size()+" returned="+response.players().size());
        } catch (Exception e) {
            CosmeticsDiagnostics.event("APPEARANCE_FAILED",CosmeticsDiagnostics.failure(e));
            LOG.debug("Appearance batch fetch failed", e);
        }
    }

    /**
     * Invalidates a single UUID's cache (e.g., after equipment change).
     */
    public synchronized void invalidate(String uuid) {
        mutationVersion++;
        cache.remove(WardrobeController.normalize(uuid));
    }

    /**
     * Directly sets the equipment for a UUID (e.g., after equipping in wardrobe).
     */
    public synchronized void setEquipped(String uuid, List<EquippedItem> items) {
        mutationVersion++;
        cache.put(WardrobeController.normalize(uuid), new CachedEntry(items.stream()
                .filter(e -> e != null && CosmeticSlot.from(e.slot()).isPresent()).toList(), System.currentTimeMillis()));
    }

    /**
     * Clears the entire cache.
     */
    public synchronized void clear() {
        mutationVersion++;
        cache.clear();
    }
}
