package com.minelatino.cosmetics.client;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

/** Owns network operations independently of the lifetime/resizing of any Screen. */
public final class WardrobeController {
    public interface Gateway {
        ApiClient.WardrobeResponse wardrobe(String token) throws Exception;
        ApiClient.EquipResponse equip(String token, String slot, String id) throws Exception;
    }
    public enum Phase { DISCONNECTED, LOADING, READY, SAVING, ERROR }
    public record Snapshot(Phase phase, String uuid, List<ApiClient.CosmeticItem> owned,
                           Map<String, String> equipped, String message) {
        public boolean busy() { return phase == Phase.LOADING || phase == Phase.SAVING; }
    }
    private final Gateway gateway;
    private final Executor worker, main;
    private final BiConsumer<String, List<ApiClient.EquippedEntry>> publish;
    private Session session;
    private long generation;
    private volatile Snapshot state = new Snapshot(Phase.DISCONNECTED, "", List.of(), Map.of(), "Vincula tu cuenta");

    public WardrobeController(Gateway gateway, Executor worker, Executor main,
                               BiConsumer<String, List<ApiClient.EquippedEntry>> publish) {
        this.gateway=gateway; this.worker=worker; this.main=main; this.publish=publish;
    }
    public Snapshot snapshot() { return state; }
    public static String normalize(String uuid) { return uuid.replace("-", "").toLowerCase(Locale.ROOT); }

    public synchronized void connect(Session next) {
        Objects.requireNonNull(next);
        if (next.isExpired()) { disconnect(); return; }
        if (session != null && session.token().equals(next.token()) && state.busy()) return;
        if (session == null || !session.token().equals(next.token())) {
            generation++;
            state = new Snapshot(Phase.DISCONNECTED, normalize(next.uuid()), List.of(), Map.of(), "");
        }
        session=next;
        reload();
    }

    public synchronized void reload() {
        if (session == null || state.busy()) return;
        long request = generation;
        Session owner = session;
        CosmeticsDiagnostics.event("WARDROBE_LOAD","owner="+normalize(owner.uuid()));
        transition(Phase.LOADING, "Cargando tu colección…");
        CompletableFuture.supplyAsync(() -> {
            try { return gateway.wardrobe(owner.token()); }
            catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
        }, worker).whenCompleteAsync((data, error) -> {
            synchronized (this) {
                if (request != generation) return;
                if (error != null) { fail(error); return; }
                try {
                    if (data == null || data.uuid() == null || !normalize(owner.uuid()).equals(normalize(data.uuid())))
                        throw new IllegalStateException("El armario pertenece a otro UUID");
                    if (data.owned() == null || data.equipped() == null)
                        throw new IllegalStateException("Respuesta de armario incompleta");
                    var items = data.owned().stream().filter(i -> i != null && CosmeticSlot.from(i.slot()).isPresent()).toList();
                    accept(data.equipped(), items, "Armario sincronizado");
                } catch (Exception e) { fail(e); }
            }
        }, main);
    }

    /** Exactly one write at a time; never optimistic: only server-confirmed equipment is published. */
    public synchronized boolean equip(String slot, String id) {
        if (session == null || state.busy() || state.phase() != Phase.READY) return false;
        if (session.isExpired()) { disconnect(); return false; }
        if (CosmeticSlot.from(slot).isEmpty()) return false;
        if (id != null && state.owned().stream().noneMatch(i -> i.id().equals(id)
                && i.slot().equals(slot) && "published".equals(i.status()))) return false;
        long request=generation;
        Session owner=session;
        CosmeticsDiagnostics.event("EQUIP_REQUEST","owner="+normalize(owner.uuid())+" slot="+CosmeticsDiagnostics.id(slot)+" cosmetic="+CosmeticsDiagnostics.id(id));
        transition(Phase.SAVING, id == null ? "Quitando cosmético…" : "Guardando equipamiento…");
        CompletableFuture.supplyAsync(() -> {
            try { return gateway.equip(owner.token(), slot, id); }
            catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
        }, worker).whenCompleteAsync((data,error) -> {
            synchronized (this) {
                if (request != generation) return;
                if (error != null) { fail(error); return; }
                try {
                    if (data == null || data.equipped() == null) throw new IllegalStateException("Respuesta de equipamiento incompleta");
                    String confirmed = data.equipped().stream().filter(e -> slot.equals(e.slot()))
                            .map(ApiClient.EquippedEntry::cosmeticId).findFirst().orElse(null);
                    if (!Objects.equals(id, confirmed)) throw new IllegalStateException("El servidor no confirmó el cambio solicitado");
                    accept(data.equipped(), state.owned(), "Equipamiento guardado");
                } catch (Exception e) { fail(e); }
            }
        }, main);
        return true;
    }

    private void accept(List<ApiClient.EquippedEntry> entries, List<ApiClient.CosmeticItem> owned, String message) {
        var safe = (entries == null ? List.<ApiClient.EquippedEntry>of() : entries).stream()
                .filter(e -> e != null && CosmeticSlot.from(e.slot()).isPresent()).toList();
        Map<String,String> equipment = new LinkedHashMap<>();
        for (var item : safe) {
            if (item.slot() == null || item.cosmeticId() == null) throw new IllegalStateException("Equipamiento inválido");
            equipment.put(item.slot(),item.cosmeticId());
        }
        String uuid=normalize(session.uuid());
        publish.accept(uuid,safe);
        CosmeticsDiagnostics.event("EQUIPMENT_CONFIRMED","owner="+uuid+" items="+safe.stream()
                .map(e->CosmeticsDiagnostics.id(e.slot())+":"+CosmeticsDiagnostics.id(e.cosmeticId())).toList());
        state=new Snapshot(Phase.READY,uuid,owned,Map.copyOf(equipment),message);
    }
    private void transition(Phase phase,String message) {
        state=new Snapshot(phase,state.uuid(),state.owned(),state.equipped(),message);
    }
    private void fail(Throwable error) {
        CosmeticsDiagnostics.event("WARDROBE_FAILED","phase="+state.phase()+" "+CosmeticsDiagnostics.failure(error));
        while (error.getCause()!=null) error=error.getCause();
        if (error instanceof ApiClient.ApiException api && api.status==401) {
            disconnect();
            transition(Phase.DISCONNECTED,"Sesión caducada. Vuelve a vincular tu cuenta.");
        } else {
            String message=error instanceof ApiClient.ApiException api ? switch(api.status) {
                case 403 -> "No posees este cosmético o no tienes permiso.";
                case 409 -> "El cosmético no está publicado o su categoría no coincide.";
                case 429 -> "Demasiadas solicitudes. Espera un minuto y reintenta.";
                default -> "Error del servidor ("+api.status+"). Reintenta.";
            } : error instanceof IllegalStateException ? error.getMessage() : "No se pudo sincronizar. Comprueba la conexión y reintenta.";
            transition(Phase.ERROR,message);
        }
    }
    public synchronized void disconnect() {
        generation++; session=null;
        state=new Snapshot(Phase.DISCONNECTED,"",List.of(),Map.of(),"Vincula tu cuenta");
    }
}
