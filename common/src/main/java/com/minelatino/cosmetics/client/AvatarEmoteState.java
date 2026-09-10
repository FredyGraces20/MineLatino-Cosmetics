package com.minelatino.cosmetics.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Runtime emote selection. Network propagation can update this same state later. */
public final class AvatarEmoteState {
    private record Active(String clip,long started,double duration,long sourceStartedAt){}
    private static final Map<Integer,Active> ACTIVE=new ConcurrentHashMap<>();
    private AvatarEmoteState(){}
    public static void play(int entityId,String clip,double duration){if(clip==null||clip.length()>256)return;ACTIVE.put(entityId,new Active(clip,System.nanoTime(),Math.max(.25,Math.min(60,duration)),0));}
    public static void sync(int entityId,String clip,long startedAt,long expiresAt,long now){if(clip==null||clip.length()>256||expiresAt<=now)return;Active current=ACTIVE.get(entityId);if(current!=null&&current.sourceStartedAt==startedAt&&current.clip.equals(clip))return;double elapsed=Math.max(0,(now-startedAt)/1000.0),duration=Math.max(.25,(expiresAt-startedAt)/1000.0);ACTIVE.put(entityId,new Active(clip,System.nanoTime()-(long)(elapsed*1_000_000_000L),duration,startedAt));}
    public static String clip(int entityId){Active a=ACTIVE.get(entityId);if(a==null)return null;if(seconds(a)>a.duration){ACTIVE.remove(entityId,a);return null;}return a.clip;}
    public static double seconds(int entityId){Active a=ACTIVE.get(entityId);return a==null?0:seconds(a);}
    private static double seconds(Active a){return(System.nanoTime()-a.started)/1_000_000_000.0;}
    public static void clear(){ACTIVE.clear();}
}
