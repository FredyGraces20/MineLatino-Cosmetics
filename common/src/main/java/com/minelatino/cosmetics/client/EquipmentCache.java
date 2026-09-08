package com.minelatino.cosmetics.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    /**
     * Fetches appearances for the given UUIDs in a single batch request.
     * Skips UUIDs that are already cached and not expired.
     */
    public void refresh(List<String> uuids) {
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
            ApiClient.AppearanceResponse response = api.appearance(needed);
            long now = System.currentTimeMillis();
            synchronized (this) {
            // A late public read must never undo a newer wardrobe write or invalidation.
            if (requestVersion != mutationVersion) {
                CosmeticsDiagnostics.event("CACHE_STALE_RESPONSE","ignored after newer equipment change");
                return;
            }
            for (ApiClient.PlayerAppearance player : response.players()) {
                List<EquippedItem> items = player.equipped() == null ? List.of()
                    : player.equipped().stream().map(e -> new EquippedItem(e.slot(), e.cosmeticId())).toList();
                cache.put(WardrobeController.normalize(player.uuid()), new CachedEntry(items, now));
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
        cache.put(WardrobeController.normalize(uuid), new CachedEntry(List.copyOf(items), System.currentTimeMillis()));
    }

    /**
     * Clears the entire cache.
     */
    public synchronized void clear() {
        mutationVersion++;
        cache.clear();
    }
}
