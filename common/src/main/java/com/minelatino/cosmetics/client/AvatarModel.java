package com.minelatino.cosmetics.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validated Bedrock/Blockbench geometry used by MineLatino full-character skins. */
public final class AvatarModel {
    public record Face(float u, float v, float width, float height, int rotation) {}
    public record Cube(float[] origin, float[] size, float[] pivot, float[] rotation,
                       float inflate, boolean mirror, boolean boxUv, Map<String, Face> faces) {}
    public record Bone(String name, String parent, float[] pivot, float[] rotation, List<Cube> cubes) {}

    private final List<Bone> roots;
    private final Map<String, List<Bone>> children;
    private final Set<String> names;
    private final int textureWidth;
    private final int textureHeight;

    private AvatarModel(List<Bone> roots, Map<String, List<Bone>> children, Set<String> names, int textureWidth, int textureHeight) {
        this.roots = List.copyOf(roots); this.children = Map.copyOf(children); this.names = Set.copyOf(names);
        this.textureWidth = textureWidth; this.textureHeight = textureHeight;
    }

    public List<Bone> roots() { return roots; }
    public List<Bone> children(String parent) { return children.getOrDefault(parent, List.of()); }
    public Set<String> boneNames() { return names; }
    public int textureWidth() { return textureWidth; }
    public int textureHeight() { return textureHeight; }

    public static AvatarModel parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray geometries = root.getAsJsonArray("minecraft:geometry");
        if (geometries == null || geometries.isEmpty()) throw new IllegalArgumentException("Missing minecraft:geometry");
        JsonObject geometry = geometries.get(0).getAsJsonObject();
        JsonObject description = geometry.getAsJsonObject("description");
        int width = integer(description, "texture_width", 64), height = integer(description, "texture_height", 64);
        if (width < 1 || height < 1 || width > 4096 || height > 4096) throw new IllegalArgumentException("Invalid avatar texture dimensions");
        JsonArray rawBones = geometry.getAsJsonArray("bones");
        if (rawBones == null || rawBones.isEmpty() || rawBones.size() > 512) throw new IllegalArgumentException("Expected 1-512 avatar bones");
        List<Bone> bones = new ArrayList<>(); Set<String> names = new HashSet<>(); int cubeCount = 0;
        for (JsonElement element : rawBones) {
            JsonObject raw = element.getAsJsonObject(); String name = raw.get("name").getAsString();
            if (!name.matches("[^\\p{Cntrl}]{1,128}") || !names.add(name)) throw new IllegalArgumentException("Invalid or duplicate bone name");
            String parent = raw.has("parent") ? raw.get("parent").getAsString() : null;
            float[] pivot = vector(raw.get("pivot"), new float[3]); float[] rotation = vector(raw.get("rotation"), new float[3]);
            boolean boneMirror = bool(raw, "mirror", false);
            float boneInflate = number(raw, "inflate", 0);
            List<Cube> cubes = new ArrayList<>();
            if (raw.has("cubes")) for (JsonElement cubeElement : raw.getAsJsonArray("cubes")) {
                if (++cubeCount > 8192) throw new IllegalArgumentException("Too many avatar cubes");
                JsonObject cube = cubeElement.getAsJsonObject();
                float[] origin = vector(cube.get("origin"), null), size = vector(cube.get("size"), null);
                float[] cubePivot = vector(cube.get("pivot"), pivot), cubeRotation = vector(cube.get("rotation"), new float[3]);
                boolean boxUv = cube.has("uv") && !cube.get("uv").isJsonObject();
                boolean mirror = bool(cube, "mirror", boneMirror);
                float inflate = number(cube, "inflate", boneInflate);
                Map<String, Face> faces = faces(cube, size);
                cubes.add(new Cube(origin, size, cubePivot, cubeRotation, inflate, mirror, boxUv, faces));
            }
            bones.add(new Bone(name, parent, pivot, rotation, List.copyOf(cubes)));
        }
        Map<String, List<Bone>> children = new LinkedHashMap<>(); List<Bone> roots = new ArrayList<>();
        for (Bone bone : bones) {
            if (bone.parent() == null || bone.parent().isBlank()) roots.add(bone);
            else { if (!names.contains(bone.parent())) throw new IllegalArgumentException("Unknown parent bone: " + bone.parent()); children.computeIfAbsent(bone.parent(), ignored -> new ArrayList<>()).add(bone); }
        }
        if (roots.isEmpty()) throw new IllegalArgumentException("Avatar bone hierarchy has no root");
        for (Bone rootBone : roots) verifyTree(rootBone, children, new HashSet<>());
        return new AvatarModel(roots, children, names, width, height);
    }

    private static void verifyTree(Bone bone, Map<String, List<Bone>> children, Set<String> path) {
        if (!path.add(bone.name())) throw new IllegalArgumentException("Avatar bone cycle");
        for (Bone child : children.getOrDefault(bone.name(), List.of())) verifyTree(child, children, path);
        path.remove(bone.name());
    }

    private static Map<String, Face> faces(JsonObject cube, float[] size) {
        Map<String, Face> result = new HashMap<>();
        if (cube.has("uv") && cube.get("uv").isJsonObject()) {
            JsonObject uv = cube.getAsJsonObject("uv");
            for (String direction : List.of("north", "south", "east", "west", "up", "down")) if (uv.has(direction)) {
                JsonObject face = uv.getAsJsonObject(direction); float[] at = vector2(face.get("uv"), null);
                float[] span = vector2(face.get("uv_size"), defaultFaceSize(direction, size));
                int rotation = integer(face, "uv_rotation", 0);
                if (rotation % 90 != 0) rotation = Math.round(rotation / 90f) * 90;
                result.put(direction, new Face(at[0], at[1], span[0], span[1], Math.floorMod(rotation, 360)));
            }
        } else if (cube.has("uv")) {
            float[] at = vector2(cube.get("uv"), null); float x=size[0], y=size[1], z=size[2];
            // Bedrock box-UV layout. WEST/EAST and DOWN are intentionally not
            // symmetrical; this is the same atlas convention used by GeckoLib.
            result.put("west", new Face(at[0]+z+x,at[1]+z,z,y,0));
            result.put("east", new Face(at[0],at[1]+z,z,y,0));
            result.put("north",new Face(at[0]+z,at[1]+z,x,y,0));
            result.put("south",new Face(at[0]+z+x+z,at[1]+z,x,y,0));
            result.put("up",new Face(at[0]+z,at[1],x,z,0));
            result.put("down",new Face(at[0]+z+x,at[1]+z,x,-z,0));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("Avatar cube has no UV faces"); return Map.copyOf(result);
    }

    private static float[] defaultFaceSize(String direction, float[] size) {
        return switch (direction) { case "up", "down" -> new float[]{size[0], size[2]}; case "east", "west" -> new float[]{size[2], size[1]}; default -> new float[]{size[0], size[1]}; };
    }
    private static int integer(JsonObject object, String key, int fallback) { return object != null && object.has(key) ? object.get(key).getAsInt() : fallback; }
    private static boolean bool(JsonObject object, String key, boolean fallback) { return object != null && object.has(key) ? object.get(key).getAsBoolean() : fallback; }
    private static float number(JsonObject object, String key, float fallback) {
        float value = object != null && object.has(key) ? object.get(key).getAsFloat() : fallback;
        if (!Float.isFinite(value) || Math.abs(value) > 1024) throw new IllegalArgumentException("Invalid cube inflation");
        return value;
    }
    private static float[] vector(JsonElement raw, float[] fallback) {
        if (raw == null || raw.isJsonNull()) { if (fallback == null) throw new IllegalArgumentException("Missing vector"); return fallback.clone(); }
        JsonArray a = raw.getAsJsonArray(); if (a.size()!=3) throw new IllegalArgumentException("Vector must contain three values");
        float[] v=new float[3]; for(int i=0;i<3;i++){v[i]=a.get(i).getAsFloat(); if(!Float.isFinite(v[i])||Math.abs(v[i])>65536)throw new IllegalArgumentException("Invalid avatar coordinate");} return v;
    }
    private static float[] vector2(JsonElement raw, float[] fallback) {
        if(raw==null||raw.isJsonNull()){if(fallback==null)throw new IllegalArgumentException("Missing UV");return fallback.clone();}
        JsonArray a=raw.getAsJsonArray();if(a.size()!=2)throw new IllegalArgumentException("UV must contain two values");return new float[]{a.get(0).getAsFloat(),a.get(1).getAsFloat()};
    }
}
