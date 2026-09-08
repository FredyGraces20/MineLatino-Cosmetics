package com.minelatino.cosmetics.client;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;

/** Frame-space UV mapping. Duration is in game ticks, including positive fractional pack timings. */
public record TextureAnimation(int columns, int rows, List<Integer> frames, List<Double> durations) {
    public static TextureAnimation still() { return new TextureAnimation(1,1,List.of(0),List.of(1.0)); }
    public static TextureAnimation parse(String json, int width, int height) {
        if (json == null) return still();
        var root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("animation")) return still();
        var a = root.getAsJsonObject("animation");
        int fw = a.has("width") ? a.get("width").getAsInt() : (a.has("height") ? width : Math.min(width,height));
        int fh = a.has("height") ? a.get("height").getAsInt() : (a.has("width") ? height : fw);
        if (fw <= 0 || fh <= 0 || width % fw != 0 || height % fh != 0) throw new IllegalArgumentException("Invalid animation dimensions");
        int count = (width/fw)*(height/fh);
        if (count > 4096) throw new IllegalArgumentException("Too many animation frames");
        double time = a.has("frametime") ? a.get("frametime").getAsDouble() : 1;
        if (!Double.isFinite(time) || time <= 0) throw new IllegalArgumentException("Invalid frametime");
        if (a.has("interpolate") && a.get("interpolate").getAsBoolean()) throw new IllegalArgumentException("Interpolated textures are not supported yet");
        var frames = new ArrayList<Integer>(); var durations = new ArrayList<Double>();
        if (a.has("frames")) {
            if (a.getAsJsonArray("frames").size() > 4096) throw new IllegalArgumentException("Too many animation frames");
            for (var entry : a.getAsJsonArray("frames")) {
                int index = entry.isJsonObject() ? entry.getAsJsonObject().get("index").getAsInt() : entry.getAsInt();
                double duration = entry.isJsonObject() && entry.getAsJsonObject().has("time") ? entry.getAsJsonObject().get("time").getAsDouble() : time;
                if (index < 0 || index >= count || !Double.isFinite(duration) || duration <= 0) throw new IllegalArgumentException("Invalid animation frame");
                frames.add(index); durations.add(duration);
            }
        }
        if (frames.isEmpty()) for (int i=0;i<count;i++) { frames.add(i); durations.add(time); }
        return new TextureAnimation(width/fw,height/fh,List.copyOf(frames),List.copyOf(durations));
    }
    public int frame(double ticks) {
        double total = durations.stream().mapToDouble(Double::doubleValue).sum();
        double t = ((ticks % total)+total)%total;
        for (int i=0;i<frames.size();i++) { if (t < durations.get(i)) return frames.get(i); t-=durations.get(i); }
        return frames.getFirst();
    }
    public float[] vertex(float[] v, double ticks) {
        int frame = frame(ticks);
        return new float[]{v[0],v[1],v[2],(v[3]+frame%columns)/columns,(v[4]+frame/columns)/rows};
    }
}
