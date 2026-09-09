package com.minelatino.cosmetics.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lightweight reader for Blockbench/GeckoLib animation JSON files. */
public final class PetAnimation {
    public static final Pose IDENTITY = new Pose(new float[3], new float[3], new float[]{1, 1, 1});
    private final Map<String, Clip> clips;
    private final String selected;

    private PetAnimation(Map<String, Clip> clips, String selected) {
        this.clips = Map.copyOf(clips);
        this.selected = selected == null ? "" : selected;
    }

    public static PetAnimation none() { return new PetAnimation(Map.of(), ""); }
    public static PetAnimation parse(String json, String selected) {
        if (json == null || json.isBlank()) return none();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject animations = root.getAsJsonObject("animations");
        if (animations == null || animations.isEmpty() || animations.size() > 128)
            throw new IllegalArgumentException("Expected Blockbench animations object");
        Map<String, Clip> result = new LinkedHashMap<>();
        for (var entry : animations.entrySet()) {
            JsonObject value = entry.getValue().getAsJsonObject();
            double length = value.has("animation_length") ? value.get("animation_length").getAsDouble() : 0;
            if (!Double.isFinite(length) || length < 0 || length > 3600) throw new IllegalArgumentException("Invalid animation length");
            boolean loop = !value.has("loop") || value.get("loop").isJsonPrimitive() && value.get("loop").getAsBoolean();
            JsonObject bones = value.getAsJsonObject("bones");
            JsonObject bone = chooseRootBone(bones);
            Track position = track(bone, "position", new float[3]);
            Track rotation = track(bone, "rotation", new float[3]);
            Track scale = track(bone, "scale", new float[]{1, 1, 1});
            double inferred = Math.max(position.end(), Math.max(rotation.end(), scale.end()));
            result.put(entry.getKey(), new Clip(Math.max(length, inferred), loop, position, rotation, scale));
        }
        return new PetAnimation(result, selected);
    }

    public List<String> names() { return List.copyOf(clips.keySet()); }
    public String selected() { return selected; }
    public Pose sample(double seconds) {
        Clip clip = clips.get(selected);
        if (clip == null && !clips.isEmpty()) clip = clips.values().iterator().next();
        return clip == null ? IDENTITY : clip.sample(seconds);
    }

    private static JsonObject chooseRootBone(JsonObject bones) {
        if (bones == null || bones.isEmpty()) return new JsonObject();
        for (String name : List.of("root", "body", "pet")) if (bones.has(name)) return bones.getAsJsonObject(name);
        return bones.entrySet().iterator().next().getValue().getAsJsonObject();
    }

    private static Track track(JsonObject bone, String channel, float[] fallback) {
        if (bone == null || !bone.has(channel)) return new Track(List.of(new Key(0, fallback.clone())));
        JsonElement raw = bone.get(channel);
        if (raw.isJsonArray()) return new Track(List.of(new Key(0, vector(raw, fallback))));
        JsonObject keyed = raw.getAsJsonObject();
        List<Key> keys = new ArrayList<>();
        for (var entry : keyed.entrySet()) {
            double time;
            try { time = Double.parseDouble(entry.getKey()); } catch (NumberFormatException e) { continue; }
            JsonElement value = entry.getValue();
            if (value.isJsonObject()) {
                JsonObject object = value.getAsJsonObject();
                value = object.has("post") ? object.get("post") : object.get("pre");
            }
            if (value != null) keys.add(new Key(time, vector(value, fallback)));
        }
        if (keys.isEmpty()) keys.add(new Key(0, fallback.clone()));
        keys.sort(Comparator.comparingDouble(Key::time));
        if (keys.size() > 4096) throw new IllegalArgumentException("Too many animation keyframes");
        return new Track(List.copyOf(keys));
    }

    private static float[] vector(JsonElement element, float[] fallback) {
        var array = element.getAsJsonArray();
        if (array.size() != 3) throw new IllegalArgumentException("Animation vectors need three values");
        float[] result = new float[3];
        for (int i = 0; i < 3; i++) {
            if (!array.get(i).isJsonPrimitive() || !array.get(i).getAsJsonPrimitive().isNumber())
                throw new IllegalArgumentException("Molang expressions are not supported");
            result[i] = array.get(i).getAsFloat();
            if (!Float.isFinite(result[i]) || Math.abs(result[i]) > 65536) throw new IllegalArgumentException("Invalid animation value");
        }
        return result;
    }

    public record Pose(float[] position, float[] rotation, float[] scale) {}
    private record Key(double time, float[] value) {}
    private record Track(List<Key> keys) {
        double end() { return keys.getLast().time(); }
        float[] sample(double time) {
            if (keys.size() == 1 || time <= keys.getFirst().time()) return keys.getFirst().value().clone();
            if (time >= end()) return keys.getLast().value().clone();
            for (int i = 1; i < keys.size(); i++) {
                Key right = keys.get(i);
                if (time <= right.time()) {
                    Key left = keys.get(i - 1);
                    float amount = (float)((time - left.time()) / (right.time() - left.time()));
                    return new float[]{
                            left.value()[0] + (right.value()[0] - left.value()[0]) * amount,
                            left.value()[1] + (right.value()[1] - left.value()[1]) * amount,
                            left.value()[2] + (right.value()[2] - left.value()[2]) * amount};
                }
            }
            return keys.getLast().value().clone();
        }
    }
    private record Clip(double length, boolean loop, Track position, Track rotation, Track scale) {
        Pose sample(double seconds) {
            double time = length <= 0 ? 0 : loop ? seconds % length : Math.min(seconds, length);
            return new Pose(position.sample(time), rotation.sample(time), scale.sample(time));
        }
    }
}
