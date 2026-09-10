package com.minelatino.cosmetics.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;

/** Single-texture Minecraft Java element model. Geometry is baked once, off the render thread. */
public final class CosmeticModel {
    public final List<ModelElement> elements;
    public final List<Quad> quads;
    public final DisplayTransform head;
    public final DisplayTransform backpack;
    public final int textureWidth;
    public final int textureHeight;

    private CosmeticModel(List<ModelElement> elements, DisplayTransform head, DisplayTransform backpack, int textureWidth, int textureHeight) {
        this.elements = List.copyOf(elements);
        this.head = head;
        this.backpack = backpack;
        this.textureWidth = textureWidth;
        this.textureHeight = textureHeight;
        this.quads = elements.stream().flatMap(e -> generateQuads(e, 0, 0, 0, textureWidth, textureHeight).stream()).toList();
    }

    public static CosmeticModel empty() {
        return new CosmeticModel(List.of(), DisplayTransform.identity(), DisplayTransform.identity(), 16, 16);
    }

    /** Invalid JSON is an error, not a PNG-only cosmetic: keep the last good resource on failure. */
    public static CosmeticModel parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray array = root.getAsJsonArray("elements");
        if (array == null || array.isEmpty() || array.size() > 4096)
            throw new IllegalArgumentException("Expected 1–4096 model elements (Minecraft Java JSON)");
        List<ModelElement> elements = new ArrayList<>();
        for (var entry : array) {
            JsonObject obj = entry.getAsJsonObject();
            float[] from = vector(obj, "from", null, 3), to = vector(obj, "to", null, 3);
            Rotation rotation = null;
            if (obj.has("rotation")) {
                JsonObject r = obj.getAsJsonObject("rotation");
                String axis = r.has("axis") ? r.get("axis").getAsString() : "y";
                float angle = r.has("angle") ? r.get("angle").getAsFloat() : 0;
                if (!List.of("x", "y", "z").contains(axis) || !Float.isFinite(angle))
                    throw new IllegalArgumentException("Invalid element rotation");
                rotation = new Rotation(vector(r, "origin", null, 3), angle, axis,
                        r.has("rescale") && r.get("rescale").getAsBoolean());
            }
            List<ModelFace> faces = new ArrayList<>();
            JsonObject faceObjects = obj.getAsJsonObject("faces");
            JsonObject explicitVertices = obj.has("minelatino_vertices") ? obj.getAsJsonObject("minelatino_vertices") : null;
            if (faceObjects == null) throw new IllegalArgumentException("Missing faces");
            for (String direction : List.of("north", "south", "east", "west", "up", "down")) {
                if (!faceObjects.has(direction)) continue;
                JsonObject f = faceObjects.getAsJsonObject(direction);
                int turn = f.has("rotation") ? f.get("rotation").getAsInt() : 0;
                if (turn < 0 || turn > 270 || turn % 90 != 0)
                    throw new IllegalArgumentException("Face rotation must be 0, 90, 180 or 270");
                if (f.has("texture") && f.get("texture").isJsonNull()) continue;
                String reference = f.has("texture") ? f.get("texture").getAsString() : "";
                var visited = new java.util.HashSet<String>();
                while (reference.startsWith("#")) {
                    if (!visited.add(reference) || !root.has("textures") || !root.getAsJsonObject("textures").has(reference.substring(1)))
                        throw new IllegalArgumentException("Unresolved texture: " + reference);
                    reference = root.getAsJsonObject("textures").get(reference.substring(1)).getAsString();
                }
                String texture = reference.substring(reference.lastIndexOf('/') + 1).replaceFirst("\\.png$", "");
                if (!texture.isEmpty() && !texture.matches("[a-z0-9_]{1,32}")) throw new IllegalArgumentException("Invalid texture name");
                float[][] vertices = explicitVertices != null && explicitVertices.has(direction)
                        ? vertices(explicitVertices.getAsJsonArray(direction), direction) : null;
                faces.add(new ModelFace(direction, vector(f, "uv", defaultUv(direction, from, to), 4), turn, texture, vertices));
            }
            elements.add(new ModelElement(from, to, rotation, List.copyOf(faces)));
        }
        DisplayTransform head = DisplayTransform.identity();
        if (root.has("display") && root.getAsJsonObject("display").has("head")) {
            JsonObject h = root.getAsJsonObject("display").getAsJsonObject("head");
            head = new DisplayTransform(vector(h, "translation", new float[3], 3),
                    vector(h, "rotation", new float[3], 3), vector(h, "scale", new float[]{1, 1, 1}, 3));
        }
        DisplayTransform backpack = DisplayTransform.identity();
        if (root.has("display") && root.getAsJsonObject("display").has("minelatino_backpack")) {
            JsonObject b = root.getAsJsonObject("display").getAsJsonObject("minelatino_backpack");
            backpack = new DisplayTransform(vector(b,"translation",new float[3],3),vector(b,"rotation",new float[3],3),vector(b,"scale",new float[]{1,1,1},3));
        }
        int textureWidth = 16, textureHeight = 16;
        if (root.has("texture_size")) {
            JsonArray ts = root.getAsJsonArray("texture_size");
            if (ts.size() >= 2) { textureWidth = ts.get(0).getAsInt(); textureHeight = ts.get(1).getAsInt(); }
            if (textureWidth <= 0 || textureHeight <= 0 || textureWidth > 4096 || textureHeight > 4096)
                throw new IllegalArgumentException("Invalid texture_size");
        }
        return new CosmeticModel(elements, head, backpack, textureWidth, textureHeight);
    }

    private static float[] vector(JsonObject obj, String key, float[] fallback, int size) {
        if (!obj.has(key)) {
            if (fallback == null) throw new IllegalArgumentException("Missing " + key);
            return fallback;
        }
        JsonArray a = obj.getAsJsonArray(key);
        if (a.size() != size) throw new IllegalArgumentException("Invalid " + key);
        float[] values = new float[size];
        for (int i = 0; i < size; i++) {
            values[i] = a.get(i).getAsFloat();
            if (!Float.isFinite(values[i]) || Math.abs(values[i]) > 65536)
                throw new IllegalArgumentException("Invalid coordinate in " + key);
        }
        return values;
    }

    private static float[] defaultUv(String dir, float[] f, float[] t) {
        return switch (dir) {
            case "down" -> new float[]{f[0], 16-t[2], t[0], 16-f[2]};
            case "up" -> new float[]{f[0], f[2], t[0], t[2]};
            case "north" -> new float[]{16-t[0], 16-t[1], 16-f[0], 16-f[1]};
            case "south" -> new float[]{f[0], 16-t[1], t[0], 16-f[1]};
            case "west" -> new float[]{f[2], 16-t[1], t[2], 16-f[1]};
            default -> new float[]{16-t[2], 16-t[1], 16-f[2], 16-f[1]};
        };
    }

    private static float[][] vertices(JsonArray array, String direction) {
        if (array == null || array.size() != 4) throw new IllegalArgumentException("Invalid explicit vertices for " + direction);
        float[][] result = new float[4][];
        for (int i = 0; i < 4; i++) {
            if (!array.get(i).isJsonArray()) throw new IllegalArgumentException("Invalid explicit vertex for " + direction);
            JsonObject wrapper = new JsonObject();
            wrapper.add("value", array.get(i));
            result[i] = vector(wrapper, "value", null, 3);
        }
        return result;
    }

    public record DisplayTransform(float[] translation, float[] rotation, float[] scale) {
        public static DisplayTransform identity() {
            return new DisplayTransform(new float[3], new float[3], new float[]{1, 1, 1});
        }
    }
    public record ModelElement(float[] from, float[] to, Rotation rotation, List<ModelFace> faces) {}
    public record Rotation(float[] origin, float angle, String axis, boolean rescale) {}
    public record ModelFace(String direction, float[] uv, int rotation, String texture, float[][] vertices) {}
    public record Quad(float[] v0, float[] v1, float[] v2, float[] v3, float[] normal, String texture) {}

    public static List<Quad> generateQuads(ModelElement e, float ox, float oy, float oz, int texW, int texH) {
        float x1=e.from()[0], y1=e.from()[1], z1=e.from()[2];
        float x2=e.to()[0], y2=e.to()[1], z2=e.to()[2];
        List<Quad> result = new ArrayList<>();
        for (ModelFace face : e.faces()) {
            // Vanilla FaceInfo order: top-left, bottom-left, bottom-right, top-right.
            float[][] positions = face.vertices() != null ? face.vertices() : switch (face.direction()) {
                case "north" -> new float[][]{{x2,y2,z1},{x2,y1,z1},{x1,y1,z1},{x1,y2,z1}};
                case "south" -> new float[][]{{x1,y2,z2},{x1,y1,z2},{x2,y1,z2},{x2,y2,z2}};
                case "east" -> new float[][]{{x2,y2,z2},{x2,y1,z2},{x2,y1,z1},{x2,y2,z1}};
                case "west" -> new float[][]{{x1,y2,z1},{x1,y1,z1},{x1,y1,z2},{x1,y2,z2}};
                case "up" -> new float[][]{{x1,y2,z1},{x1,y2,z2},{x2,y2,z2},{x2,y2,z1}};
                case "down" -> new float[][]{{x1,y1,z2},{x1,y1,z1},{x2,y1,z1},{x2,y1,z2}};
                default -> throw new IllegalArgumentException("Unknown face");
            };
            float[][] vertices = new float[4][];
            for (int i=0; i<4; i++) {
                float[] p = positions[i].clone();
                if (face.vertices() == null) rotate(p, e.rotation());
                int uvIndex = (i + face.rotation()/90) % 4;
                // Java model UVs are in 16-unit coordinates; texture_size is editor metadata.
                float u = face.uv()[uvIndex < 2 ? 0 : 2] / 16;
                float v = face.uv()[uvIndex == 0 || uvIndex == 3 ? 1 : 3] / 16;
                vertices[i] = new float[]{(p[0]-8)/16+ox, (p[1]-8)/16+oy, (p[2]-8)/16+oz, u, v};
            }
            // Compute the normal AFTER rotation/rescale; guarantees agreement with winding.
            float[] a=vertices[0], b=vertices[1], c=vertices[2];
            float ax=b[0]-a[0], ay=b[1]-a[1], az=b[2]-a[2];
            float bx=c[0]-a[0], by=c[1]-a[1], bz=c[2]-a[2];
            float nx=ay*bz-az*by, ny=az*bx-ax*bz, nz=ax*by-ay*bx;
            float length=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);
            if (length > 0) result.add(new Quad(a,b,c,vertices[3],new float[]{nx/length,ny/length,nz/length},face.texture()));
        }
        return result;
    }

    private static void rotate(float[] p, Rotation r) {
        if (r == null || r.angle() == 0) return;
        float x=p[0]-r.origin()[0], y=p[1]-r.origin()[1], z=p[2]-r.origin()[2];
        float s=(float)Math.sin(Math.toRadians(r.angle())), c=(float)Math.cos(Math.toRadians(r.angle()));
        float factor=r.rescale() ? 1/Math.abs(c) : 1;
        if (!Float.isFinite(factor) || factor > 100) throw new IllegalArgumentException("Invalid rescale angle");
        switch(r.axis()) {
            case "x" -> { p[0]=x; p[1]=(y*c-z*s)*factor; p[2]=(y*s+z*c)*factor; }
            case "y" -> { p[0]=(x*c+z*s)*factor; p[1]=y; p[2]=(-x*s+z*c)*factor; }
            case "z" -> { p[0]=(x*c-y*s)*factor; p[1]=(x*s+y*c)*factor; p[2]=z; }
        }
        for(int i=0;i<3;i++) p[i]+=r.origin()[i];
    }
}
