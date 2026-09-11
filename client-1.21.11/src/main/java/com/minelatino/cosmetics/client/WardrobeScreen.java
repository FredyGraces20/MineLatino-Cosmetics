package com.minelatino.cosmetics.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.input.MouseButtonEvent;

/** Native 3D wardrobe. Selecting previews; the explicit Equip button writes to the API. */
public final class WardrobeScreen extends Screen {
    private final Screen parent;
    private final CosmeticsClient client=CosmeticsClient.instance();
    private final WardrobeController controller=client.wardrobe();
    private final CosmeticPreview preview=new CosmeticPreview();
    private WardrobeController.Snapshot displayed;
    private CompletableFuture<Session> authFuture;
    private String authError, selectedId, query="";
    private CosmeticSlot filter;
    private WardrobeButton equip, connect, refresh, previous, next;
    private final List<WardrobeButton> cards=new ArrayList<>();
    private int page, pages=1, rows=1;
    private int top,bottom,previewX,previewW,collectionX,collectionW;
    private float orbit=-18, zoom=1;
    private boolean dragging;
    private String diagnosticMessage;
    private boolean exportingDiagnostics;

    public WardrobeScreen(Screen parent) {
        super(Component.literal("Armario MineLatino")); this.parent=parent;
    }
    @Override protected void init() {
        boolean wide=width>=560;
        top=wide ? 48 : 66; bottom=height-28;
        int nav=wide ? 96 : 0;
        previewX=8+nav;
        previewW=Math.max(100,(width-24-nav)*2/5);
        collectionX=previewX+previewW+8;
        collectionW=width-8-collectionX;
        var diagnosticButton=addRenderableWidget(button(previewX+previewW-57,top+3,50,14,"Registro",this::exportDiagnostics));
        diagnosticButton.setTooltip(Tooltip.create(Component.literal("Guardar diagnóstico en la carpeta logs de esta instancia")));
        addRenderableWidget(button(width-56,10,48,20,"Cerrar",this::onClose));
        refresh=addRenderableWidget(button(width-128,10,68,20,"Actualizar",()-> {
            controller.reload();
            client.forceRefreshTransforms();
            if(selectedId!=null) client.resources().retry(selectedId);
        }));
        connect=addRenderableWidget(button(width-200,10,68,20,"Vincular",this::startAuth));
        List<CosmeticSlot> slots=new ArrayList<>(); slots.add(null); slots.addAll(List.of(CosmeticSlot.values()));
        for(int i=0;i<slots.size();i++) {
            CosmeticSlot slot=slots.get(i);
            int tabW=wide ? 88 : (width-16)/slots.size();
            int tabX=wide ? 8 : 8+i*tabW, tabY=wide ? top+22+i*Math.min(25,Math.max(20,(bottom-top-24)/slots.size())) : 40;
            addRenderableWidget(new WardrobeButton(tabX,tabY,tabW-2,20,slot==null ? "Todos" : slot.label(),
                    ()->{ filter=slot; page=0; rebuildCards(); },()->filter==slot));
        }
        EditBox search=addRenderableWidget(new EditBox(font,collectionX+8,top+20,collectionW-16,18,Component.literal("Buscar cosmético")));
        search.setMaxLength(80); search.setHint(Component.literal("Buscar cosmético…"));
        search.setValue(query);
        search.setResponder(value->{ query=value; page=0; rebuildCards(); });
        equip=addRenderableWidget(button(collectionX+8,bottom-25,collectionW-16,20,"Selecciona un cosmético",this::equipSelected));
        previous=addRenderableWidget(button(collectionX+collectionW-46,top+3,18,14,"<",()->changePage(-1)));
        next=addRenderableWidget(button(collectionX+collectionW-25,top+3,18,14,">",()->changePage(1)));
        addRenderableWidget(button(previewX+8,bottom-25,(previewW-20)/2,20,"Frente",()->{orbit=0;zoom=1;}));
        addRenderableWidget(button(previewX+12+(previewW-20)/2,bottom-25,(previewW-20)/2,20,"Espalda",()->{orbit=180;zoom=1;}));
        displayed=controller.snapshot();
        rebuildCards();
        // Resizing/reopening must not replace a pending write or launch duplicate reads.
        if(client.auth().isConnected() && !controller.snapshot().busy()) controller.connect(client.auth().session());
        updateButtons();
    }
    private WardrobeButton button(int x,int y,int w,int h,String label,Runnable action) {
        return new WardrobeButton(x,y,w,h,label,action,()->false);
    }
    private void startAuth() {
        if(authFuture!=null) return;
        if(client.auth().isConnected() && controller.snapshot().phase()!=WardrobeController.Phase.DISCONNECTED) {
            controller.reload(); return;
        }
        authError=null;
        if(client.auth().isConnected()) client.auth().logout();
        authFuture=client.auth().authenticate();
        // Independent from tick/onClose: a completed authentication still loads the shared armario.
        authFuture.whenCompleteAsync((session,error)-> {
            if(error==null) controller.connect(session);
        },task->minecraft.execute(task));
    }
    private void exportDiagnostics() {
        if(exportingDiagnostics) return;
        exportingDiagnostics=true;
        diagnosticMessage="Guardando registro…";
        String report=client.diagnosticReport();
        var directory=minecraft.gameDirectory.toPath();
        CompletableFuture.supplyAsync(()->{
            try { return CosmeticsDiagnostics.export(directory,report); }
            catch(java.io.IOException e) { throw new java.util.concurrent.CompletionException(e); }
        }).whenCompleteAsync((path,error)->{
            exportingDiagnostics=false;
            diagnosticMessage=error==null ? "Registro guardado: "+path.toAbsolutePath() : "No se pudo guardar el registro; consulta logs/latest.log";
            if(error!=null) CosmeticsDiagnostics.event("EXPORT_FAILED",CosmeticsDiagnostics.failure(error));
        },task->minecraft.execute(task));
    }
    private ApiClient.CosmeticItem selected() {
        return controller.snapshot().owned().stream().filter(i->i.id().equals(selectedId)).findFirst().orElse(null);
    }
    private void equipSelected() {
        var item=selected();
        if(item==null) return;
        boolean saved=item.id().equals(controller.snapshot().equipped().get(item.slot()));
        if(controller.equip(item.slot(),saved ? null : item.id()) && saved) selectedId=null;
        updateButtons();
    }
    private void changePage(int amount) { page=Math.max(0,Math.min(pages-1,page+amount)); rebuildCards(); }
    private void rebuildCards() {
        for(var card:cards) removeWidget(card);
        cards.clear();
        var state=controller.snapshot();
        var filtered=state.owned().stream().filter(i->filter==null || i.slot().equals(filter.name()))
                .filter(i->i.name().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).toList();
        if(selected()==null && !filtered.isEmpty()) {
            selectedId=filtered.getFirst().id();
            orbit=List.of("BACKPACK","CAPE","WINGS").contains(filtered.getFirst().slot()) ? 180 : -18;
        }
        rows=Math.max(1,(bottom-top-82)/38);
        pages=Math.max(1,(filtered.size()+rows-1)/rows);
        page=Math.min(page,pages-1);
        for(int n=page*rows;n<Math.min(filtered.size(),(page+1)*rows);n++) {
            var item=filtered.get(n);
            int y=top+43+(n-page*rows)*38;
            var card=new WardrobeButton(collectionX+8,y,collectionW-16,34,item.name(),()->{
                selectedId=item.id();
                orbit=List.of("BACKPACK","CAPE","WINGS").contains(item.slot()) ? 180 : -18;
                updateButtons();
            },()->item.id().equals(selectedId)) {
                @Override protected void renderContents(GuiGraphics g,int mx,int my,float delta) {
                    WardrobeButton.panel(g,getX(),getY(),getWidth(),getHeight(),item.id().equals(selectedId) ? ACCENT : 0xFF404343,
                            isHoveredOrFocused() ? 0xFF38372F : 0xFF2B2D2D);
                    // Model PNGs are UV atlases, not product thumbnails. In 1.21.11
                    // retained GUI rendering could expand this raw atlas over the whole
                    // wardrobe. Keep materials exclusively in the isolated 3D preview.
                    var resource=client.resources().getOrDownload(item.id());
                    drawModelBadge(g,getX()+4,getY()+4,item.slot(),resource!=null,client.resources().error(item.id())!=null);
                    g.drawString(font,font.plainSubstrByWidth(item.name(),Math.max(8,getWidth()-39)),getX()+35,getY()+5,TEXT,false);
                    boolean saved=item.id().equals(controller.snapshot().equipped().get(item.slot()));
                    String label=saved ? "Equipado · "+CosmeticSlot.label(item.slot()) : CosmeticSlot.label(item.slot());
                    g.drawString(font,font.plainSubstrByWidth(label,Math.max(8,getWidth()-39)),getX()+35,getY()+20,saved ? ACCENT : DIM,false);
                }
            };
            card.setTooltip(Tooltip.create(Component.literal(item.name()+" · "+CosmeticSlot.label(item.slot()))));
            cards.add(addRenderableWidget(card));
        }
    }
    @Override public void tick() {
        super.tick();
        if(authFuture!=null && authFuture.isDone()) {
            try { authFuture.join(); } catch(Exception e) { authError=client.auth().errorMessage(); }
            authFuture=null;
        }
        if(displayed!=controller.snapshot()) { displayed=controller.snapshot(); rebuildCards(); }
        updateButtons();
    }
    private void updateButtons() {
        if(equip==null) return;
        var state=controller.snapshot(); var item=selected();
        boolean saved=item!=null && item.id().equals(state.equipped().get(item.slot()));
        equip.active=state.phase()==WardrobeController.Phase.READY && item!=null
                && CosmeticSlot.from(item.slot()).isPresent() && (saved || "published".equals(item.status()));
        equip.setMessage(Component.literal(state.phase()==WardrobeController.Phase.SAVING ? "Guardando…" :
                item==null ? "Selecciona un cosmético" : saved ? "Quitar cosmético" : "Equipar · "+CosmeticSlot.label(item.slot())));
        connect.active=!state.busy() && authFuture==null;
        connect.setMessage(Component.literal(authFuture!=null ? "Verificando…" : client.auth().isConnected() ? "Mi cuenta" : "Vincular"));
        refresh.active=!state.busy() && client.auth().isConnected();
        previous.active=page>0; next.active=page<pages-1;
    }
    private List<EquipmentCache.EquippedItem> previewItems() {
        var selection=selected();
        if(selection==null || !"published".equals(selection.status())) return List.of();
        // Match the launcher fitting room: one selected product at a time. Rendering
        // every equipped slot together hides back items and creates an atlas-like pile.
        return List.of(new EquipmentCache.EquippedItem(selection.slot(),selection.id()));
    }
    @Override public void renderBackground(GuiGraphics g,int mx,int my,float delta) {
        // Replace the default blurred background + dirt texture with a clean opaque gradient.
        // The default renderBackground calls renderBlurredBackground() which overwrites the
        // screen with a blurred game-world screenshot, causing the preview to look blurred
        // and a "ghost menu" to appear behind the wardrobe UI.
        g.fillGradient(0,0,width,height,0xFF262726,0xFF171919);
    }
    @Override public void render(GuiGraphics g,int mx,int my,float delta) {
        g.drawString(font,width>=440 ? "MINELATINO / ARMARIO" : "ML / ARMARIO",10,16,WardrobeButton.ACCENT,false);
        WardrobeButton.panel(g,previewX,top,previewW,bottom-top,0xFF404343,0xFF202020);
        WardrobeButton.panel(g,collectionX,top,collectionW,bottom-top,0xFF404343,0xFF242525);
        var state=controller.snapshot(); var item=selected();
        boolean trying=item!=null && !item.id().equals(state.equipped().get(item.slot()));
        String previewTitle=trying ? "PROBANDO · sin guardar" : minecraft.getUser().getName();
        g.drawString(font,font.plainSubstrByWidth(previewTitle,Math.max(1,previewW-70)),previewX+8,top+8,WardrobeButton.DIM,false);
        g.drawString(font,font.plainSubstrByWidth("COLECCIÓN "+(page+1)+"/"+pages,collectionW-56),collectionX+8,top+6,WardrobeButton.DIM,false);
        int stageH=Math.max(40,bottom-top-51);
        if(minecraft.player!=null) {
            g.fill(previewX+previewW/5,top+stageH-1,previewX+previewW*4/5,top+stageH,0xFF6F5229);
            preview.render(g,previewX+3,top+19,previewW-6,stageH,orbit,zoom,previewItems());
        } else g.drawWordWrap(font,Component.literal("Entra a un mundo para ver tu skin en 3D."),previewX+10,top+45,previewW-20,WardrobeButton.DIM);
        if(cards.isEmpty()) {
            String empty=state.phase()==WardrobeController.Phase.DISCONNECTED ? "Vincula tu cuenta para cargar tus cosméticos." :
                    state.busy() ? "Cargando colección…" : state.phase()==WardrobeController.Phase.ERROR ? "No se pudo cargar. Pulsa Actualizar." :
                    state.owned().isEmpty() ? "Aún no tienes cosméticos asignados a esta cuenta." : "No hay resultados en esta categoría.";
            g.drawWordWrap(font,Component.literal(empty),collectionX+12,top+47,collectionW-24,WardrobeButton.DIM);
        }
        String detail=item==null ? "Elige uno para probarlo en 3D" : item.name();
        g.drawString(font,font.plainSubstrByWidth(detail,collectionW-16),collectionX+8,bottom-37,WardrobeButton.DIM,false);
        super.render(g,mx,my,delta);
        String message=authError!=null ? authError : authFuture!=null ? "Verificando la sesión de Minecraft…" : state.message();
        boolean warning=authError!=null || state.phase()==WardrobeController.Phase.ERROR;
        if(!warning && minecraft.player!=null && !state.uuid().isEmpty()
                && !state.uuid().equals(WardrobeController.normalize(minecraft.player.getUUID().toString()))) {
            message="Cosmético visible localmente. El servidor usa otro UUID; otros jugadores necesitan una vinculación de identidad."; warning=true;
        } else if(!warning && item!=null) {
            String error=client.resources().error(item.id());
            if(error!=null) { message=error+" · Actualizar para reintentar"; warning=true; }
            else if(client.resources().get(item.id())==null) message="Descargando modelo y textura…";
            else if(minecraft.player!=null && !preview.layerAvailable()) { message="Render no registrado. Revisa el cargador y latest.log."; warning=true; }
            else if(trying) message="Solo vista previa. Pulsa Equipar para guardar.";
        }
        if(diagnosticMessage!=null) message=diagnosticMessage;
        g.drawString(font,font.plainSubstrByWidth(message==null ? "" : message,width-16),8,height-17,
                warning ? 0xFFFFB080 : WardrobeButton.DIM,false);
        // tooltip rendering adapted for 1.21.11 API (non-critical)
    }
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean doubleClick) {
        double x=event.x(),y=event.y();
        if(event.button()==0 && x>=previewX && x<previewX+previewW && y>=top+20 && y<bottom-28) { dragging=true; return true; }
        return super.mouseClicked(event,doubleClick);
    }
    @Override public boolean mouseDragged(MouseButtonEvent event,double dx,double dy) {
        if(dragging && event.button()==0) { orbit+=(float)dx*1.2f; return true; }
        return super.mouseDragged(event,dx,dy);
    }
    @Override public boolean mouseReleased(MouseButtonEvent event) { dragging=false; return super.mouseReleased(event); }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {
        if(x>=previewX && x<previewX+previewW && y>=top && y<bottom) { zoom=Math.max(.6f,Math.min(1.5f,zoom+(float)vertical*.08f)); return true; }
        if(x>=collectionX && y>=top && y<bottom) { changePage(vertical<0 ? 1 : -1); return true; }
        return super.mouseScrolled(x,y,horizontal,vertical);
    }
    private void drawModelBadge(GuiGraphics g,int x,int y,String slot,boolean ready,boolean failed) {
        int stateColor=failed ? 0xFFFF6B6B : ready ? 0xFF62E8C6 : 0xFFE8A32E;
        WardrobeButton.panel(g,x,y,26,26,0xFF54585B,0xFF181B1D);
        g.fill(x+5,y+5,x+21,y+18,0xFF303538);
        g.fill(x+7,y+3,x+19,y+5,0xFF454C50);
        String symbol=switch(slot) { case "HAT" -> "H"; case "CAPE" -> "C"; case "WINGS" -> "A"; case "BACKPACK" -> "M"; case "PET" -> "P"; default -> "3D"; };
        g.drawCenteredString(font,symbol,x+13,y+7,WardrobeButton.TEXT);
        g.fill(x+5,y+21,x+21,y+23,0xFF292D30);
        g.fill(x+5,y+21,x+(ready ? 21 : 10),y+23,stateColor);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
