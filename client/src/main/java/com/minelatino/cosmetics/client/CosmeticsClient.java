package com.minelatino.cosmetics.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Singleton entry point for the cosmetics backend connection.
 * Holds the ApiClient, AuthManager, EquipmentCache and ResourceCache.
 * Initialised lazily from the game directory on first access.
 */
public final class CosmeticsClient {
    private static final Logger LOG = LoggerFactory.getLogger("MineLatino Cosmetics");
    private static volatile CosmeticsClient instance;

    /** Maximum number of UUIDs per appearance batch request (backend limit). */
    private static final int BATCH_SIZE = 50;

    private final ApiClient api;
    private final AuthManager auth;
    private final EquipmentCache equipment;
    private final ResourceCache resources;
    private final WardrobeController wardrobe;
    private long lastEquipmentRefresh = 0;
    private static final long EQUIPMENT_REFRESH_INTERVAL = 30_000; // 30 seconds

    /** Server-side cosmetic transform overrides (cosmeticId -> slot -> transform). */
    private volatile Map<String, Map<String, ApiClient.TransformData>> transforms = Map.of();
    private long lastTransformRefresh = 0;
    private static final long TRANSFORM_REFRESH_INTERVAL = 60_000; // 60 seconds
    private volatile boolean transformsRefreshing = false;

    /** Tracks whether an async equipment refresh is in progress. */
    private volatile boolean refreshing = false;

    private CosmeticsClient(String backendUrl, Path cacheDir) {
        this.api = new ApiClient(backendUrl);
        this.auth = new AuthManager(api);
        this.equipment = new EquipmentCache(api);
        this.resources = new ResourceCache(backendUrl, cacheDir);
        this.wardrobe = new WardrobeController(api, java.util.concurrent.ForkJoinPool.commonPool(),
                task -> Minecraft.getInstance().execute(task), (uuid, entries) -> equipment.setEquipped(uuid,
                entries.stream().map(e -> new EquipmentCache.EquippedItem(e.slot(), e.cosmeticId())).toList()));
    }

    public static CosmeticsClient instance() {
        if (instance == null) {
            synchronized (CosmeticsClient.class) {
                if (instance == null) {
                    CosmeticsConfig config = CosmeticsConfig.read(Minecraft.getInstance().gameDirectory.toPath());
                    Path cacheDir = Minecraft.getInstance().gameDirectory.toPath().resolve("cache").resolve("minelatino-cosmetics");
                    instance = new CosmeticsClient(config.backendUrl(), cacheDir);
                    CosmeticsDiagnostics.event("START","build=alpha.6 minecraft=1.21.4 java="+System.getProperty("java.version"));
                    LOG.info("Cosmetics backend: {}", config.backendUrl());
                }
            }
        }
        return instance;
    }

    /**
     * Called every client tick. Populates the entity UUID map and periodically
     * refreshes equipment data for nearby players.
     * The refresh runs asynchronously to avoid blocking the game thread.
     */
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        Session diagnosticSession=auth.session();
        CosmeticsDiagnostics.changed("IDENTITY","account="+mc.getUser().getProfileId()+
                " server="+(mc.player==null ? "none" : mc.player.getUUID())+
                " session="+(diagnosticSession==null ? "none" : CosmeticsDiagnostics.id(diagnosticSession.uuid()))+
                " auth="+auth.state()+" valid="+auth.isConnected());
        CosmeticRenderer.ENTITY_UUID_MAP.clear();
        if (mc.level == null) return;

        // Always populate the entity ID → UUID map from the current player list
        for (AbstractClientPlayer player : mc.level.players()) {
            UUID uuid = player.getGameProfile().getId();
            CosmeticRenderer.putEntityUuid(player.getId(), uuid);
        }

        // The local player can have a server-side offline UUID different from the
        // verified MineLatino account UUID. Bind only this entity to the verified
        // session; never infer another player's identity from their nickname.
        if (mc.player != null && auth.isConnected() && auth.session() != null) {
            try {
                CosmeticRenderer.putEntityUuid(mc.player.getId(), UUID.fromString(
                        formatUuid(auth.session().uuid())));
            } catch (IllegalArgumentException ignored) {
                LOG.warn("Ignoring invalid verified session UUID for local cosmetics");
            }
        }

        // Periodic transform refresh (async, non-blocking)
        refreshTransformsIfNeeded();

        // Periodic equipment refresh (async, non-blocking)
        // Appearance is public: spectators with the mod need no wardrobe session.
        long now = System.currentTimeMillis();
        if (now - lastEquipmentRefresh < EQUIPMENT_REFRESH_INTERVAL) return;
        if (refreshing) return; // previous refresh still running
        lastEquipmentRefresh = now;

        List<String> nearbyUuids = mc.level.players().stream()
                .map(p -> p.getGameProfile().getId().toString().replace("-", ""))
                .collect(Collectors.toList());

        if (nearbyUuids.isEmpty()) return;

        refreshing = true;
        CompletableFuture.runAsync(() -> {
            try {
                // Split into batches of BATCH_SIZE
                for (int i = 0; i < nearbyUuids.size(); i += BATCH_SIZE) {
                    List<String> batch = nearbyUuids.subList(i, Math.min(i + BATCH_SIZE, nearbyUuids.size()));
                    equipment.refresh(batch);
                }
            } catch (Exception e) {
                LOG.debug("Equipment refresh failed", e);
            } finally {
                refreshing = false;
            }
        });
    }

    public ApiClient api() { return api; }
    public AuthManager auth() { return auth; }
    public EquipmentCache equipment() { return equipment; }
    public ResourceCache resources() { return resources; }
    public WardrobeController wardrobe() { return wardrobe; }

    /**
     * Get the server-side transform override for a cosmetic slot.
     * Returns null if no override exists.
     */
    public ApiClient.TransformData getTransform(String cosmeticId, String slot) {
        Map<String, ApiClient.TransformData> slotTransforms = transforms.get(cosmeticId);
        if (slotTransforms == null) return null;
        return slotTransforms.get(slot);
    }

    /**
     * Refresh transforms from the server if enough time has passed.
     * Called from tick() to keep transforms up to date.
     */
    public void refreshTransformsIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastTransformRefresh < TRANSFORM_REFRESH_INTERVAL) return;
        if (transformsRefreshing) return;
        transformsRefreshing = true;
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                ApiClient.CosmeticTransformsResponse response = api.cosmeticTransforms();
                if (response != null && response.transforms() != null) {
                    transforms = response.transforms();
                }
                lastTransformRefresh = now;
            } catch (Exception e) {
                LOG.debug("Transform refresh failed", e);
            } finally {
                transformsRefreshing = false;
            }
        });
    }

    /** Capture on the game thread; file writing is performed by the UI off-thread. */
    public String diagnosticReport() {
        Minecraft mc=Minecraft.getInstance();
        Session s=auth.session();
        String server=mc.player==null ? "none" : WardrobeController.normalize(mc.player.getUUID().toString());
        String owner=s==null ? "none" : WardrobeController.normalize(s.uuid());
        String state="build=alpha.6 minecraft=1.21.4\naccountUuid="+mc.getUser().getProfileId()+
                "\nserverUuid="+server+"\nsessionUuid="+owner+"\nauth="+auth.state()+
                "\nsessionValid="+auth.isConnected()+"\nwardrobe="+wardrobe.snapshot().phase()+
                "\nowned="+wardrobe.snapshot().owned().size()+"\nconfirmed="+wardrobe.snapshot().equipped()+
                "\nserverCache="+(mc.player==null ? "none" : equipment.get(server))+
                "\nsessionCache="+(s==null ? "none" : equipment.get(owner))+
                "\nidentityMismatch="+(!server.equals("none") && !owner.equals("none") && !server.equals(owner))+
                "\nworldLoaded="+(mc.level!=null)+"\nrefreshInProgress="+refreshing+
                "\nplayerInvisible="+(mc.player!=null && mc.player.isInvisible())+
                "\nplayerSpectator="+(mc.player!=null && mc.player.isSpectator())+
                "\nNota: UUID distintos requieren vinculación verificada para otros clientes.";
        return CosmeticsDiagnostics.report(state);
    }

    private static String formatUuid(String value) {
        String hex = value.replace("-", "");
        if (!hex.matches("[0-9a-fA-F]{32}")) throw new IllegalArgumentException("invalid UUID");
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
                + "-" + hex.substring(16, 20) + "-" + hex.substring(20);
    }

    /**
     * Resets the singleton (for testing or config reload).
     * Disconnects any active session and clears caches.
     */
    public static synchronized void reset() {
        if (instance != null) {
            instance.auth.logout();
            instance.wardrobe.disconnect();
            instance.equipment.clear();
            instance.resources.clear();
            instance = null;
        }
    }
}
