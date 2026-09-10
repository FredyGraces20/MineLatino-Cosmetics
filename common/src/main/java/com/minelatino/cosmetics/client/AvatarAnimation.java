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

/** Bedrock/YSM animation clips with layered playback, easing and a safe Molang expression subset. */
public final class AvatarAnimation {
    public record Pose(float[] position, float[] rotation, float[] scale) {}
    public record Context(float headYaw, float headPitch, float groundSpeed, float verticalSpeed,
                          boolean hasMainHand, boolean hasOffHand, boolean hasHelmet) {
        public static Context basic(float headYaw,float headPitch){return new Context(headYaw,headPitch,0,0,false,false,false);}
    }
    private static final Pose IDENTITY = new Pose(new float[3],new float[3],new float[]{1,1,1});
    private final Map<String,Clip> clips;
    private final List<String> preParallel;
    private final List<String> parallel;
    private AvatarAnimation(Map<String,Clip> clips){
        this.clips=Map.copyOf(clips);
        this.preParallel=layers(clips,"pre_parallel");
        this.parallel=layers(clips,"parallel");
    }
    public static AvatarAnimation none(){return new AvatarAnimation(Map.of());}
    public List<String> names(){return List.copyOf(clips.keySet());}
    public boolean has(String name){return clips.containsKey(name);}
    public double length(String name){Clip c=clips.get(name);return c==null?0:c.length;}
    public Pose sample(String clipName,String bone,double seconds){return sample(clipName,bone,seconds,0,0);}
    public Pose sample(String clipName,String bone,double seconds,float headYaw,float headPitch){return sample(clipName,bone,seconds,Context.basic(headYaw,headPitch));}
    public Pose sample(String clipName,String bone,double seconds,Context context){Clip clip=clips.get(clipName);if(clip==null)return IDENTITY;BoneTracks tracks=clip.bones.get(bone);if(tracks==null)return IDENTITY;double time=clip.length<=0?0:clip.loop?positiveModulo(seconds,clip.length):Math.min(Math.max(0,seconds),clip.length);return new Pose(tracks.position.sample(time,context),tracks.rotation.sample(time,context),tracks.scale.sample(time,context));}

    /**
     * Evaluates the legacy YSM/Bedrock layer order without ever mixing unrelated
     * movement clips: pre_parallel0..7, one primary clip, parallel0..7. Primary
     * channels override pre-parallel channels. High-priority parallel channels
     * override position/scale and add rotation, matching YSM's documented blend.
     */
    public Pose sampleLayered(String primary,String bone,double primarySeconds,double ambientSeconds,float headYaw,float headPitch){
        return sampleLayered(primary,bone,primarySeconds,ambientSeconds,Context.basic(headYaw,headPitch));
    }
    public Pose sampleLayered(String primary,String bone,double primarySeconds,double ambientSeconds,Context context){
        float[] position=new float[3],rotation=new float[3],scale=new float[]{1,1,1};
        for(String name:preParallel)apply(name,bone,ambientSeconds,context,position,rotation,scale,false);
        apply(primary,bone,primarySeconds,context,position,rotation,scale,false);
        for(String name:parallel)apply(name,bone,ambientSeconds,context,position,rotation,scale,true);
        return new Pose(position,rotation,scale);
    }

    private void apply(String clipName,String bone,double seconds,Context context,float[] position,float[] rotation,float[] scale,boolean additiveRotation){
        Clip clip=clips.get(clipName);if(clip==null)return;BoneTracks tracks=clip.bones.get(bone);if(tracks==null)return;
        double time=clip.length<=0?0:clip.loop?positiveModulo(seconds,clip.length):Math.min(Math.max(0,seconds),clip.length);
        if(tracks.position.present)copy(position,tracks.position.sample(time,context),false);
        if(tracks.rotation.present)copy(rotation,tracks.rotation.sample(time,context),additiveRotation);
        if(tracks.scale.present)copy(scale,tracks.scale.sample(time,context),false);
    }
    private static double positiveModulo(double value,double divisor){double result=value%divisor;return result<0?result+divisor:result;}
    private static void copy(float[] target,float[] source,boolean add){for(int i=0;i<3;i++)target[i]=add?target[i]+source[i]:source[i];}
    private static List<String> layers(Map<String,Clip> clips,String prefix){
        return clips.keySet().stream().filter(name->{String simple=simple(name);return simple.matches(prefix+"[0-7]");})
            .sorted(Comparator.comparingInt(name->simple(name).charAt(prefix.length())-'0')).toList();
    }
    private static String simple(String name){int dot=name.lastIndexOf('.');return (dot<0?name:name.substring(dot+1)).toLowerCase();}

    public static AvatarAnimation parse(List<String> sources){
        Map<String,Clip> result=new LinkedHashMap<>();
        for(String source:sources){if(source==null||source.isBlank())continue;JsonObject root=JsonParser.parseString(source).getAsJsonObject();JsonObject animations=root.getAsJsonObject("animations");if(animations==null)continue;
            for(var entry:animations.entrySet()){
                if(result.size()>=512)throw new IllegalArgumentException("Too many avatar animations");
                JsonObject raw=entry.getValue().getAsJsonObject();double length=raw.has("animation_length")?raw.get("animation_length").getAsDouble():0;if(!Double.isFinite(length)||length<0||length>3600)throw new IllegalArgumentException("Invalid avatar animation length");
                boolean loop=raw.has("loop")&&raw.get("loop").isJsonPrimitive()&&raw.get("loop").getAsJsonPrimitive().isBoolean()&&raw.get("loop").getAsBoolean();Map<String,BoneTracks>bones=new LinkedHashMap<>();JsonObject rawBones=raw.getAsJsonObject("bones");
                if(rawBones!=null)for(var b:rawBones.entrySet()){JsonObject o=b.getValue().getAsJsonObject();Track p=track(o,"position",new String[]{"0","0","0"}),r=track(o,"rotation",new String[]{"0","0","0"}),s=track(o,"scale",new String[]{"1","1","1"});length=Math.max(length,Math.max(p.end(),Math.max(r.end(),s.end())));bones.put(b.getKey(),new BoneTracks(p,r,s));}
                result.put(entry.getKey(),new Clip(length,loop,Map.copyOf(bones)));
            }
        }return new AvatarAnimation(result);
    }
    private static Track track(JsonObject bone,String channel,String[] fallback){
        if(!bone.has(channel))return new Track(List.of(new Key(0,fallback,"linear")),false);
        JsonElement raw=bone.get(channel);
        if(raw.isJsonArray()||raw.isJsonPrimitive())return new Track(List.of(new Key(0,vector(raw,fallback),"linear")),true);
        JsonObject keyed=raw.getAsJsonObject();
        if(keyed.has("vector"))return new Track(List.of(new Key(0,vector(keyed.get("vector"),fallback),mode(keyed))),true);
        List<Key>keys=new ArrayList<>();
        for(var e:keyed.entrySet()){
            double time;try{time=Double.parseDouble(e.getKey());}catch(NumberFormatException ex){continue;}
            JsonElement value=e.getValue();
            if(value.isJsonObject()){
                JsonObject obj=value.getAsJsonObject();String interpolation=mode(obj);
                if(obj.has("pre"))keys.add(new Key(Math.max(0,time-1.0e-6),keyframeVector(obj.get("pre"),fallback),nestedMode(obj.get("pre"),interpolation)));
                if(obj.has("vector"))keys.add(new Key(time,vector(obj.get("vector"),fallback),interpolation));
                if(obj.has("post"))keys.add(new Key(time+1.0e-6,keyframeVector(obj.get("post"),fallback),nestedMode(obj.get("post"),interpolation)));
                if(!obj.has("pre")&&!obj.has("post")&&!obj.has("vector"))keys.add(new Key(time,fallback,interpolation));
            } else keys.add(new Key(time,vector(value,fallback),"linear"));
        }
        if(keys.isEmpty())keys.add(new Key(0,fallback,"linear"));
        keys.sort(Comparator.comparingDouble(Key::time));if(keys.size()>4096)throw new IllegalArgumentException("Too many keyframes");return new Track(List.copyOf(keys),true);
    }
    private static String[] keyframeVector(JsonElement element,String[] fallback){if(element!=null&&element.isJsonObject()){JsonObject object=element.getAsJsonObject();if(object.has("vector"))return vector(object.get("vector"),fallback);}return vector(element,fallback);}
    private static String nestedMode(JsonElement element,String fallback){return element!=null&&element.isJsonObject()?mode(element.getAsJsonObject()):fallback;}
    private static String mode(JsonObject object){if(object.has("lerp_mode"))return object.get("lerp_mode").getAsString().toLowerCase();if(object.has("easing"))return object.get("easing").getAsString().toLowerCase();return "linear";}
    private static String[] vector(JsonElement element,String[] fallback){if(element==null||element.isJsonNull())return fallback.clone();if(element.isJsonPrimitive()){String scalar=element.getAsString();return new String[]{scalar,scalar,scalar};}if(!element.isJsonArray())return fallback.clone();JsonArray a=element.getAsJsonArray();if(a.size()!=3)throw new IllegalArgumentException("Animation vector needs three values");String[]r=new String[3];for(int i=0;i<3;i++){JsonElement v=a.get(i);if(!v.isJsonPrimitive())throw new IllegalArgumentException("Invalid animation expression");r[i]=v.getAsString();if(r[i].length()>512)throw new IllegalArgumentException("Molang expression too long");}return r;}
    private record BoneTracks(Track position,Track rotation,Track scale){}
    private record Clip(double length,boolean loop,Map<String,BoneTracks>bones){}
    private record Key(double time,String[]value,String mode){}
    private record Track(List<Key>keys,boolean present){
        double end(){return keys.getLast().time;}
        float[]sample(double time,Context context){
            if(keys.size()==1)return eval(keys.getFirst().value,time,context);
            int rightIndex=keys.size()-1;for(int i=1;i<keys.size();i++)if(time<=keys.get(i).time){rightIndex=i;break;}
            int leftIndex=Math.max(0,rightIndex-1);Key left=keys.get(leftIndex),right=keys.get(rightIndex);
            float[]a=eval(left.value,time,context),b=eval(right.value,time,context);float t=right.time==left.time?0:(float)((time-left.time)/(right.time-left.time));t=Math.max(0,Math.min(1,t));
            String interpolation=right.mode;
            if(interpolation.equals("step"))t=t>=1?1:0;
            float[]result=new float[3];
            if(interpolation.equals("catmullrom")){
                float[]p0=eval(keys.get(Math.max(0,leftIndex-1)).value,time,context),p3=eval(keys.get(Math.min(keys.size()-1,rightIndex+1)).value,time,context);
                for(int i=0;i<3;i++)result[i]=catmull(t,p0[i],a[i],b[i],p3[i]);
            }else{t=ease(interpolation,t);for(int i=0;i<3;i++)result[i]=a[i]+(b[i]-a[i])*t;}
            return result;
        }
    }
    private static float catmull(float t,float p0,float p1,float p2,float p3){return .5f*(2*p1+(p2-p0)*t+(2*p0-5*p1+4*p2-p3)*t*t+(3*p1-p0-3*p2+p3)*t*t*t);}
    private static float ease(String mode,float t){return switch(mode){case"easeinsine"->(float)(1-Math.cos(t*Math.PI/2));case"easeoutsine"->(float)Math.sin(t*Math.PI/2);case"easeinoutsine"->(float)(-(Math.cos(Math.PI*t)-1)/2);case"easeinquad"->t*t;case"easeoutquad"->1-(1-t)*(1-t);case"easeinoutquad"->t<.5f?2*t*t:1-(float)Math.pow(-2*t+2,2)/2;case"easeincubic"->t*t*t;case"easeoutcubic"->1-(float)Math.pow(1-t,3);case"easeinoutcubic"->t<.5f?4*t*t*t:1-(float)Math.pow(-2*t+2,3)/2;default->t;};}
    private static float[] eval(String[]v,double time,Context context){float[]r=new float[3];for(int i=0;i<3;i++){double n=new Molang(v[i],time,context).parse();r[i]=Double.isFinite(n)?(float)Math.max(-65536,Math.min(65536,n)):0;}return r;}

    /** No reflection/eval: unknown variables are zero; trigonometric functions use degrees like Molang. */
    private static final class Molang{
        final String s;final double time;final Context context;int p;Molang(String s,double time,Context context){this.s=s;this.time=time;this.context=context;}
        double parse(){try{return conditional();}catch(RuntimeException ignored){return 0;}}
        double conditional(){double c=comparison();skip();if(take('?')){double yes=conditional();expect(':');double no=conditional();return c!=0?yes:no;}return c;}
        double comparison(){double a=add();while(true){skip();if(match(">=")){double b=add();a=a>=b?1:0;}else if(match("<=")){double b=add();a=a<=b?1:0;}else if(match("==")){double b=add();a=a==b?1:0;}else if(match("!=")){double b=add();a=a!=b?1:0;}else if(take('>')){double b=add();a=a>b?1:0;}else if(take('<')){double b=add();a=a<b?1:0;}else return a;}}
        double add(){double a=mul();while(true){skip();if(take('+'))a+=mul();else if(take('-'))a-=mul();else return a;}}
        double mul(){double a=unary();while(true){skip();if(take('*'))a*=unary();else if(take('/')){double b=unary();a=b==0?0:a/b;}else if(take('%')){double b=unary();a=b==0?0:a%b;}else return a;}}
        double unary(){skip();if(take('-'))return-unary();if(take('+'))return unary();if(take('!'))return unary()==0?1:0;return atom();}
        double atom(){skip();if(take('(')){double v=conditional();expect(')');return v;}int start=p;if(p<s.length()&&(Character.isDigit(s.charAt(p))||s.charAt(p)=='.')){while(p<s.length()&&(Character.isDigit(s.charAt(p))||".eE+-".indexOf(s.charAt(p))>=0)){if((s.charAt(p)=='+'||s.charAt(p)=='-')&&p>start&&s.charAt(p-1)!='e'&&s.charAt(p-1)!='E')break;p++;}return Double.parseDouble(s.substring(start,p));}while(p<s.length()&&(Character.isLetterOrDigit(s.charAt(p))||s.charAt(p)=='_'||s.charAt(p)=='.'))p++;String id=s.substring(start,p).toLowerCase();skip();if(take('(')){List<Double>a=new ArrayList<>();if(!peek(')'))do{a.add(conditional());skip();}while(take(','));expect(')');return function(id,a);}return switch(id){case"query.anim_time","query.life_time","query.time_stamp"->time;case"query.ground_speed"->context.groundSpeed;case"query.vertical_speed"->context.verticalSpeed;case"ysm.head_yaw"->context.headYaw;case"ysm.head_pitch"->context.headPitch;case"ysm.food_level"->20;case"ysm.has_mainhand"->context.hasMainHand?1:0;case"ysm.has_offhand","ysm.has_offand"->context.hasOffHand?1:0;case"ysm.has_helmet"->context.hasHelmet?1:0;case"ysm.is_close_eyes"->((int)(time*2.3)%17==0&&time%1<.08)?1:0;case"true"->1;default->0;};}
        double function(String id,List<Double>a){double x=a.isEmpty()?0:a.get(0);return switch(id){case"math.sin"->Math.sin(Math.toRadians(x));case"math.cos"->Math.cos(Math.toRadians(x));case"math.abs"->Math.abs(x);case"math.sqrt"->Math.sqrt(Math.max(0,x));case"math.floor"->Math.floor(x);case"math.ceil"->Math.ceil(x);case"math.exp"->Math.exp(x);case"math.pow"->a.size()<2?x:Math.pow(x,a.get(1));case"math.min"->a.stream().mapToDouble(Double::doubleValue).min().orElse(0);case"math.max"->a.stream().mapToDouble(Double::doubleValue).max().orElse(0);case"math.clamp"->a.size()<3?x:Math.max(a.get(1),Math.min(a.get(2),x));case"math.random"->{double low=a.size()>1?x:0,high=a.size()>1?a.get(1):x;long bits=Double.doubleToLongBits(time*20)+31L*s.hashCode()+p;double random=(new java.util.Random(bits)).nextDouble();yield low+(high-low)*random;}default->0;};}
        void skip(){while(p<s.length()&&Character.isWhitespace(s.charAt(p)))p++;}boolean take(char c){skip();if(p<s.length()&&s.charAt(p)==c){p++;return true;}return false;}boolean peek(char c){skip();return p<s.length()&&s.charAt(p)==c;}boolean match(String m){skip();if(s.startsWith(m,p)){p+=m.length();return true;}return false;}void expect(char c){if(!take(c))throw new IllegalArgumentException();}
    }
}
