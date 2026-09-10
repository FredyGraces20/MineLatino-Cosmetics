package com.minelatino.cosmetics.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Bakes Bedrock/Blockbench cubes into the coordinate and UV convention used by
 * YSM and GeckoLib. Keeping this renderer-independent also lets every loader
 * render exactly the same geometry.
 */
public final class AvatarGeometry {
    public record Quad(float[][] vertices, float[] normal) {}

    private AvatarGeometry() {}

    public static List<Quad> bake(AvatarModel model, AvatarModel.Cube cube) {
        float[] origin = cube.origin(), size = cube.size();
        float inflation = cube.inflate() / 16f;

        // Bedrock's model X points opposite Minecraft's baked model X. The
        // lower X corner must include the cube width before it is negated.
        float x1 = -(origin[0] + size[0]) / 16f - inflation;
        float x2 = -origin[0] / 16f + inflation;
        float y1 = origin[1] / 16f - inflation;
        float y2 = (origin[1] + size[1]) / 16f + inflation;
        float z1 = origin[2] / 16f - inflation;
        float z2 = (origin[2] + size[2]) / 16f + inflation;

        float[][] west = positions(x1,y2,z2, x1,y2,z1, x1,y1,z1, x1,y1,z2);
        float[][] east = positions(x2,y2,z1, x2,y2,z2, x2,y1,z2, x2,y1,z1);
        float[][] north = positions(x1,y2,z1, x2,y2,z1, x2,y1,z1, x1,y1,z1);
        float[][] south = positions(x2,y2,z2, x1,y2,z2, x1,y1,z2, x2,y1,z2);
        float[][] up = positions(x1,y2,z2, x2,y2,z2, x2,y2,z1, x1,y2,z1);
        float[][] down = positions(x1,y1,z1, x1,y1,z2, x2,y1,z2, x2,y1,z1);

        List<Quad> result = new ArrayList<>(6);
        for (var entry : cube.faces().entrySet()) {
            String direction = entry.getKey();
            if (zeroSizedFace(size, direction)) continue;
            float[][] positions = switch (direction) {
                case "west" -> cube.mirror() ? east : west;
                case "east" -> cube.mirror() ? west : east;
                case "north" -> north;
                case "south" -> south;
                case "up" -> cube.mirror() && !cube.boxUv() ? down : up;
                case "down" -> cube.mirror() && !cube.boxUv() ? up : down;
                default -> throw new IllegalArgumentException("Unknown cube face: " + direction);
            };

            float[] normal = switch (direction) {
                case "west" -> new float[]{-1,0,0};
                case "east" -> new float[]{1,0,0};
                case "north" -> new float[]{0,0,-1};
                case "south" -> new float[]{0,0,1};
                case "up" -> new float[]{0,1,0};
                default -> new float[]{0,-1,0};
            };
            if (cube.mirror()) normal[0] *= -1;

            AvatarModel.Face face = entry.getValue();
            float u = face.u() / model.textureWidth();
            float v = face.v() / model.textureHeight();
            float uEnd = (face.u() + face.width()) / model.textureWidth();
            float vEnd = (face.v() + face.height()) / model.textureHeight();
            if (!cube.mirror()) { float swap=uEnd; uEnd=u; u=swap; }
            float[][] uv = rotatedUvs(u, v, uEnd, vEnd, face.rotation());

            float[][] vertices = new float[4][5];
            for (int i=0;i<4;i++) {
                vertices[i][0]=positions[i][0]; vertices[i][1]=positions[i][1]; vertices[i][2]=positions[i][2];
                vertices[i][3]=uv[i][0]; vertices[i][4]=uv[i][1];
            }
            result.add(new Quad(vertices, normal));
        }
        return List.copyOf(result);
    }

    private static boolean zeroSizedFace(float[] size, String direction) {
        if (size[0] == 0) return !direction.equals("west") && !direction.equals("east");
        if (size[1] == 0) return !direction.equals("up") && !direction.equals("down");
        if (size[2] == 0) return !direction.equals("north") && !direction.equals("south");
        return false;
    }

    private static float[][] rotatedUvs(float u, float v, float uEnd, float vEnd, int rotation) {
        return switch (Math.floorMod(rotation,360)) {
            case 90 -> new float[][]{{uEnd,v},{uEnd,vEnd},{u,vEnd},{u,v}};
            case 180 -> new float[][]{{uEnd,vEnd},{u,vEnd},{u,v},{uEnd,v}};
            case 270 -> new float[][]{{u,vEnd},{u,v},{uEnd,v},{uEnd,vEnd}};
            default -> new float[][]{{u,v},{uEnd,v},{uEnd,vEnd},{u,vEnd}};
        };
    }

    private static float[][] positions(float... values) {
        float[][] result = new float[4][3];
        for (int i=0;i<4;i++) System.arraycopy(values,i*3,result[i],0,3);
        return result;
    }
}
