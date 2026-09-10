package com.minelatino.cosmetics.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.List;

/** Radial emote picker backed by the animation clips in the equipped avatar ZIP. */
public final class EmoteWheelScreen extends Screen {
    private final Screen parent;private final EmoteKeyConfig keys;private List<String>clips=List.of();private AvatarPackage avatar;private boolean capturing;
    public EmoteWheelScreen(Screen parent,EmoteKeyConfig keys){super(Component.literal("Emojis MineLatino"));this.parent=parent;this.keys=keys;}
    @Override protected void init(){reload();addRenderableWidget(new WardrobeButton(width/2-60,height-30,120,20,"Tecla: "+keyName(),()->capturing=true,()->capturing));}
    private void reload(){String id=CosmeticsClient.instance().wardrobe().snapshot().equipped().get("SKIN");if(id==null)return;var resource=CosmeticsClient.instance().resources().getOrDownload(id);if(resource!=null){avatar=resource.avatar();if(avatar!=null)clips=avatar.emotes().stream().limit(12).toList();}}
    private String keyName(){return InputConstants.getKey(keys.key(),-1).getDisplayName().getString();}
    @Override public void tick(){if(avatar==null)reload();}
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick){graphics.fillGradient(0,0,width,height,0xd810151b,0xee070a0f);super.render(graphics,mouseX,mouseY,partialTick);int cx=width/2,cy=height/2-5;graphics.drawCenteredString(font,title,cx,18,0xff59e6ff);if(capturing)graphics.drawCenteredString(font,"Pulsa la nueva tecla",cx,height-48,0xffffc247);if(avatar==null){graphics.drawCenteredString(font,"Equipa una Skin animada para usar sus emojis",cx,cy,0xffb8c6d1);return;}if(clips.isEmpty()){graphics.drawCenteredString(font,"Este personaje no contiene animaciones de emoji",cx,cy,0xffb8c6d1);return;}for(int i=0;i<clips.size();i++){double a=Math.PI*2*i/clips.size()-Math.PI/2;int x=cx+(int)(Math.cos(a)*Math.min(120,width/4)),y=cy+(int)(Math.sin(a)*Math.min(75,height/4));boolean hover=Math.abs(mouseX-x)<46&&Math.abs(mouseY-y)<10;graphics.fill(x-45,y-9,x+45,y+9,hover?0xff23657a:0xd9232a33);String label=simple(clips.get(i));graphics.drawCenteredString(font,label,x,y-4,hover?0xffffffff:0xffc9d5df);}graphics.fill(cx-22,cy-22,cx+22,cy+22,0xff111820);graphics.drawCenteredString(font,"EMOTE",cx,cy-4,0xff9ff5ff);}
    @Override public boolean mouseClicked(double mouseX,double mouseY,int button){if(button==0&&avatar!=null){int cx=width/2,cy=height/2-5;for(int i=0;i<clips.size();i++){double a=Math.PI*2*i/clips.size()-Math.PI/2;int x=cx+(int)(Math.cos(a)*Math.min(120,width/4)),y=cy+(int)(Math.sin(a)*Math.min(75,height/4));if(Math.abs(mouseX-x)<46&&Math.abs(mouseY-y)<10){String clip=clips.get(i);CosmeticsClient.instance().playEmote(clip,avatar.animations().length(clip));onClose();return true;}}}return super.mouseClicked(mouseX,mouseY,button);}
    @Override public boolean keyPressed(int keyCode,int scanCode,int modifiers){if(capturing&&keyCode!=256){keys.set(keyCode);capturing=false;rebuildWidgets();return true;}return super.keyPressed(keyCode,scanCode,modifiers);}
    @Override public void onClose(){minecraft.setScreen(parent);}
    private static String simple(String value){String v=value.substring(value.lastIndexOf('.')+1).replace('_',' ');return v.length()>18?v.substring(0,18):v;}
}
