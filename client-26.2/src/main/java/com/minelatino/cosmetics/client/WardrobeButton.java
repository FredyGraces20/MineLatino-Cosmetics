package com.minelatino.cosmetics.client;

import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/** Keyboard/narrator-aware widget with the launcher's charcoal/amber palette. */
class WardrobeButton extends AbstractButton {
    static final int TEXT=0xFFE5E0D8, DIM=0xFFB4ACA0, ACCENT=0xFFE8A32E;
    private final Runnable action;
    private final BooleanSupplier selected;
    private float glow;
    WardrobeButton(int x,int y,int w,int h,String label,Runnable action,BooleanSupplier selected) {
        super(x,y,w,h,Component.literal(label)); this.action=action; this.selected=selected;
    }
    @Override public void onPress(net.minecraft.client.input.InputWithModifiers input) { action.run(); }
    @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    @Override protected void extractContents(GuiGraphicsExtractor g,int mx,int my,float delta) {
        glow += ((isHoveredOrFocused() ? 1f : 0f)-glow)*Math.min(1,delta*.22f+.08f);
        int x=getX(), y=getY(), w=getWidth(), h=getHeight();
        boolean chosen=selected.getAsBoolean();
        panel(g,x,y,w,h,chosen ? 0xFF8C5E22 : isHoveredOrFocused() ? 0xFF916224 : 0xFF404343,
                chosen ? 0xFF654820 : glow>.25 ? 0xFF36362F : 0xFF2B2D2D);
        var font=Minecraft.getInstance().font;
        String label=font.plainSubstrByWidth(getMessage().getString(),Math.max(1,w-10));
        g.centeredText(font,label,x+w/2,y+(h-8)/2,active ? (chosen ? 0xFFFFF6E6 : TEXT) : 0xFF77736D);
    }
    static void panel(GuiGraphicsExtractor g,int x,int y,int w,int h,int border,int fill) {
        rounded(g,x,y,w,h,border);
        rounded(g,x+1,y+1,w-2,h-2,fill);
        if(w>12) g.fill(x+5,y+1,x+w-5,y+2,0x20FFFFFF);
    }
    private static void rounded(GuiGraphicsExtractor g,int x,int y,int w,int h,int color) {
        if(w<6 || h<6) { g.fill(x,y,x+w,y+h,color); return; }
        g.fill(x+3,y,x+w-3,y+h,color);
        g.fill(x+1,y+1,x+w-1,y+h-1,color);
        g.fill(x,y+3,x+w,y+h-3,color);
    }
}
