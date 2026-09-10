package com.minelatino.cosmetics.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bedrock animation clips with linear keyframes and a safe Molang expression subset. */
public final class AvatarAnimation {
    public record Pose(float[] position, float[] rotation, float[] scale) {}
    private static final Pose IDENTITY = new Pose(new float[3],new float[3],new float[]{1,1,1});
    private final Map<String,Clip> clips;
    private AvatarAnimation(Map<String,Clip> clips){this.clips=Map.copyOf(clips);}
    public static AvatarAnimation none(){return new AvatarAnimation(Map.of());}
    public List<String> names(){return List.copyOf(clips.keySet());}
    public boolean has(String name){return clips.containsKey(name);}
    public double length(String name){Clip c=clips.get(name);return c==null?0:c.length;}
    public Pose sample(String clipName,String bone,double seconds){return sample(clipName,bone,seconds,0,0);}
    public Pose sample(String clipName,String bone,double seconds,float headYaw,float headPitch){Clip clip=clips.get(clipName);if(clip==null)return IDENTITY;BoneTracks tracks=clip.bones.get(bone);if(tracks==null)return IDENTITY;double time=clip.length<=0?0:clip.loop?seconds%clip.length:Math.min(seconds,clip.length);return new Pose(tracks.position.sample(time,headYaw,headPitch),tracks.rotation.sample(time,headYaw,headPitch),tracks.scale.sample(time,headYaw,headPitch));}

    public static AvatarAnimation parse(List<String> sources){
        Map<String,Clip> result=new LinkedHashMap<>();
        for(String source:sources){if(source==null||source.isBlank())continue;JsonObject root=JsonParser.parseString(source).getAsJsonObject();JsonObject animations=root.getAsJsonObject("animations");if(animations==null)continue;
            for(var entry:animations.entrySet()){
                if(result.size()>=512)throw new IllegalArgumentException("Too many avatar animations");
                JsonObject raw=entry.getValue().getAsJsonObject();double length=raw.has("animation_length")?raw.get("animation_length").getAsDouble():0;if(!Double.isFinite(length)||length<0||length>3600)throw new IllegalArgumentException("Invalid avatar animation length");
                boolean loop=!raw.has("loop")||(raw.get("loop").isJsonPrimitive()&&raw.get("loop").getAsBoolean());Map<String,BoneTracks>bones=new LinkedHashMap<>();JsonObject rawBones=raw.getAsJsonObject("bones");
                if(rawBones!=null)for(var b:rawBones.entrySet()){JsonObject o=b.getValue().getAsJsonObject();Track p=track(o,"position",new String[]{"0","0","0"}),r=track(o,"rotation",new String[]{"0","0","0"}),s=track(o,"scale",new String[]{"1","1","1"});length=Math.max(length,Math.max(p.end(),Math.max(r.end(),s.end())));bones.put(b.getKey(),new BoneTracks(p,r,s));}
                result.put(entry.getKey(),new Clip(length,loop,Map.copyOf(bones)));
            }
        }return new AvatarAnimation(result);
    }
    private static Track track(JsonObject bone,String channel,String[] fallback){if(!bone.has(channel))return new Track(List.of(new Key(0,fallback)));JsonElement raw=bone.get(channel);if(raw.isJsonArray()||raw.isJsonPrimitive())return new Track(List.of(new Key(0,vector(raw,fallback))));JsonObject keyed=raw.getAsJsonObject();List<Key>keys=new ArrayList<>();for(var e:keyed.entrySet()){double time;try{time=Double.parseDouble(e.getKey());}catch(NumberFormatException ex){continue;}JsonElement value=e.getValue();if(value.isJsonObject()){JsonObject obj=value.getAsJsonObject();value=obj.has("post")?obj.get("post"):obj.get("pre");}if(value!=null)keys.add(new Key(time,vector(value,fallback)));}if(keys.isEmpty())keys.add(new Key(0,fallback));keys.sort(Comparator.comparingDouble(Key::time));if(keys.size()>4096)throw new IllegalArgumentException("Too many keyframes");return new Track(List.copyOf(keys));}
    private static String[] vector(JsonElement element,String[] fallback){if(element==null||element.isJsonNull())return fallback.clone();if(element.isJsonPrimitive()){String scalar=element.getAsString();return new String[]{scalar,scalar,scalar};}if(!element.isJsonArray())return fallback.clone();JsonArray a=element.getAsJsonArray();if(a.size()!=3)throw new IllegalArgumentException("Animation vector needs three values");String[]r=new String[3];for(int i=0;i<3;i++){JsonElement v=a.get(i);if(!v.isJsonPrimitive())throw new IllegalArgumentException("Invalid animation expression");r[i]=v.getAsString();if(r[i].length()>512)throw new IllegalArgumentException("Molang expression too long");}return r;}
    private record BoneTracks(Track position,Track rotation,Track scale){}
    private record Clip(double length,boolean loop,Map<String,BoneTracks>bones){}
    private record Key(double time,String[]value){}
    private record Track(List<Key>keys){double end(){return keys.getLast().time;}float[]sample(double time,float yaw,float pitch){if(keys.size()==1)return eval(keys.getFirst().value,time,yaw,pitch);Key left=keys.getFirst(),right=keys.getLast();for(int i=1;i<keys.size();i++)if(time<=keys.get(i).time){left=keys.get(i-1);right=keys.get(i);break;}float[]a=eval(left.value,time,yaw,pitch),b=eval(right.value,time,yaw,pitch);float t=right.time==left.time?0:(float)((time-left.time)/(right.time-left.time));t=Math.max(0,Math.min(1,t));return new float[]{a[0]+(b[0]-a[0])*t,a[1]+(b[1]-a[1])*t,a[2]+(b[2]-a[2])*t};}}
    private static float[] eval(String[]v,double time,float yaw,float pitch){float[]r=new float[3];for(int i=0;i<3;i++){double n=new Molang(v[i],time,yaw,pitch).parse();r[i]=Double.isFinite(n)?(float)Math.max(-65536,Math.min(65536,n)):0;}return r;}

    /** No reflection/eval: unknown variables are zero; trigonometric functions use degrees like Molang. */
    private static final class Molang{
        final String s;final double time,yaw,pitch;int p;Molang(String s,double time,float yaw,float pitch){this.s=s;this.time=time;this.yaw=yaw;this.pitch=pitch;}
        double parse(){try{return conditional();}catch(RuntimeException ignored){return 0;}}
        double conditional(){double c=comparison();skip();if(take('?')){double yes=conditional();expect(':');double no=conditional();return c!=0?yes:no;}return c;}
        double comparison(){double a=add();while(true){skip();if(match(">=")){double b=add();a=a>=b?1:0;}else if(match("<=")){double b=add();a=a<=b?1:0;}else if(match("==")){double b=add();a=a==b?1:0;}else if(match("!=")){double b=add();a=a!=b?1:0;}else if(take('>')){double b=add();a=a>b?1:0;}else if(take('<')){double b=add();a=a<b?1:0;}else return a;}}
        double add(){double a=mul();while(true){skip();if(take('+'))a+=mul();else if(take('-'))a-=mul();else return a;}}
        double mul(){double a=unary();while(true){skip();if(take('*'))a*=unary();else if(take('/')){double b=unary();a=b==0?0:a/b;}else if(take('%')){double b=unary();a=b==0?0:a%b;}else return a;}}
        double unary(){skip();if(take('-'))return-unary();if(take('+'))return unary();if(take('!'))return unary()==0?1:0;return atom();}
        double atom(){skip();if(take('(')){double v=conditional();expect(')');return v;}int start=p;if(p<s.length()&&(Character.isDigit(s.charAt(p))||s.charAt(p)=='.')){while(p<s.length()&&(Character.isDigit(s.charAt(p))||".eE+-".indexOf(s.charAt(p))>=0)){if((s.charAt(p)=='+'||s.charAt(p)=='-')&&p>start&&s.charAt(p-1)!='e'&&s.charAt(p-1)!='E')break;p++;}return Double.parseDouble(s.substring(start,p));}while(p<s.length()&&(Character.isLetterOrDigit(s.charAt(p))||s.charAt(p)=='_'||s.charAt(p)=='.'))p++;String id=s.substring(start,p).toLowerCase();skip();if(take('(')){List<Double>a=new ArrayList<>();if(!peek(')'))do{a.add(conditional());skip();}while(take(','));expect(')');return function(id,a);}return switch(id){case"query.anim_time","query.life_time"->time;case"ysm.head_yaw"->yaw;case"ysm.head_pitch"->pitch;case"ysm.food_level"->20;case"ysm.is_close_eyes"->((int)(time*2.3)%17==0&&time%1<.08)?1:0;case"true"->1;default->0;};}
        double function(String id,List<Double>a){double x=a.isEmpty()?0:a.get(0);return switch(id){case"math.sin"->Math.sin(Math.toRadians(x));case"math.cos"->Math.cos(Math.toRadians(x));case"math.abs"->Math.abs(x);case"math.sqrt"->Math.sqrt(Math.max(0,x));case"math.floor"->Math.floor(x);case"math.ceil"->Math.ceil(x);case"math.min"->a.stream().mapToDouble(Double::doubleValue).min().orElse(0);case"math.max"->a.stream().mapToDouble(Double::doubleValue).max().orElse(0);case"math.clamp"->a.size()<3?x:Math.max(a.get(1),Math.min(a.get(2),x));default->0;};}
        void skip(){while(p<s.length()&&Character.isWhitespace(s.charAt(p)))p++;}boolean take(char c){skip();if(p<s.length()&&s.charAt(p)==c){p++;return true;}return false;}boolean peek(char c){skip();return p<s.length()&&s.charAt(p)==c;}boolean match(String m){skip();if(s.startsWith(m,p)){p+=m.length();return true;}return false;}void expect(char c){if(!take(c))throw new IllegalArgumentException();}
    }
}
