package com.minelatino.cosmetics.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CosmeticModelTest {
    private static String cube(String face, String extra, String rotation) {
        return "{\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16]," + rotation
                + "\"faces\":{\"" + face + "\":{\"uv\":[2,4,10,12]" + extra + "}}}]}";
    }

    @Test void allFacesPointOutward() {
        String[] dirs={"north","south","east","west","up","down"};
        float[][] normals={{0,0,-1},{0,0,1},{1,0,0},{-1,0,0},{0,1,0},{0,-1,0}};
        for(int i=0;i<dirs.length;i++) {
            var q=CosmeticModel.parse(cube(dirs[i],"", "")).quads.getFirst();
            assertArrayEquals(normals[i], q.normal(), 0.00001f, dirs[i]);
            assertEquals(0.125f,q.v0()[3]);
            assertEquals(0.25f,q.v0()[4]);
            assertEquals(0.75f,q.v1()[4]);
        }
    }

    @Test void faceUvRotationUsesVanillaQuarterTurns() {
        float[][] uv={{.125f,.25f},{.125f,.75f},{.625f,.75f},{.625f,.25f}};
        for(int turn=0;turn<4;turn++) {
            var q=CosmeticModel.parse(cube("north",",\"rotation\":"+turn*90, "")).quads.getFirst();
            float[][] vertices={q.v0(),q.v1(),q.v2(),q.v3()};
            for(int i=0;i<4;i++) {
                assertEquals(uv[(i+turn)%4][0],vertices[i][3]);
                assertEquals(uv[(i+turn)%4][1],vertices[i][4]);
            }
        }
    }

    @Test void rotatedNormalsFollowGeometry() {
        var q=CosmeticModel.parse(cube("north","",
                "\"rotation\":{\"origin\":[8,8,8],\"angle\":90,\"axis\":\"y\"},")).quads.getFirst();
        assertArrayEquals(new float[]{-1,0,0},q.normal(),0.00001f);
        assertEquals(-.5f,q.v0()[0],0.00001f);
    }

    @Test void arbitraryBlockbenchAnglesSupported() {
        var model=CosmeticModel.parse(cube("north","",
                "\"rotation\":{\"origin\":[8,11,11.225],\"angle\":-2.5,\"axis\":\"x\"},"));
        var n=model.quads.getFirst().normal();
        assertEquals(-Math.cos(Math.toRadians(2.5)),n[2],0.00001);
        assertTrue(n[1]<0);
    }

    @Test void headDisplayPreservesBlockbenchUnitsAndDefaults() {
        String json=cube("north","", "");
        json=json.substring(0,json.length()-1)+",\"display\":{\"head\":{\"translation\":[0,14.4,0]}}}";
        var model=CosmeticModel.parse(json);
        assertEquals(14.4f,model.head.translation()[1]);
        assertArrayEquals(new float[]{1,1,1},model.head.scale());
        assertArrayEquals(new float[3],model.head.rotation());
    }

    @Test void backpackDisplayPreservesItsIntendedFullSize() {
        String json=cube("north","", "");
        json=json.substring(0,json.length()-1)+",\"display\":{\"minelatino_backpack\":{\"translation\":[0,38.8,0.66875],\"scale\":[2,2,2]}}}";
        var model=CosmeticModel.parse(json);
        assertArrayEquals(new float[]{0,38.8f,0.66875f}, model.backpack.translation());
        assertArrayEquals(new float[]{2,2,2}, model.backpack.scale());
        assertArrayEquals(new float[3], model.backpack.rotation());
    }

    @Test void missingModelIsDifferentFromInvalidModel() {
        assertTrue(CosmeticModel.empty().quads.isEmpty());
        assertThrows(RuntimeException.class,()->CosmeticModel.parse("{}"));
        assertThrows(RuntimeException.class,()->CosmeticModel.parse("not-json"));
        assertThrows(RuntimeException.class,()->CosmeticModel.parse(cube("north",",\"rotation\":45","")));
    }

    @Test void mirroredUvsArePreserved() {
        var q=CosmeticModel.parse(cube("north","", "").replace("[2,4,10,12]","[10,12,2,4]")).quads.getFirst();
        assertEquals(.625f,q.v0()[3]);
        assertEquals(.125f,q.v2()[3]);
    }

    @Test void rescaleKeepsUnitNormals() {
        var q=CosmeticModel.parse(cube("up","",
                "\"rotation\":{\"origin\":[8,8,8],\"angle\":45,\"axis\":\"x\",\"rescale\":true},")).quads.getFirst();
        assertArrayEquals(new float[]{0,(float)Math.sqrt(.5),(float)Math.sqrt(.5)},q.normal(),0.00001f);
    }

    @Test void flatElementsKeepTheirVisibleFacesWithoutInvalidNormals() {
        var front=CosmeticModel.parse(cube("north","", "").replace("[16,16,16]","[16,16,0]"));
        assertEquals(1,front.quads.size());
        assertArrayEquals(new float[]{0,0,-1},front.quads.getFirst().normal(),0.00001f);
        var edge=CosmeticModel.parse(cube("up","", "").replace("[16,16,16]","[16,16,0]"));
        assertTrue(edge.quads.isEmpty());
    }

    @Test void textureSizeMetadataDoesNotChangeJavaUvUnits() {
        String json="{\"texture_size\":[128,128],\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{\"north\":{\"uv\":[0,14.125,16,30.125]}}}]}";
        var model=CosmeticModel.parse(json);
        var q=model.quads.getFirst();
        assertEquals(0f, q.v0()[3], 0.00001f);
        assertEquals(14.125f/16, q.v0()[4], 0.00001f);
        assertEquals(30.125f/16, q.v1()[4], 0.00001f);
        assertEquals(1f, q.v2()[3], 0.00001f);
        assertEquals(128, model.textureWidth);
        assertEquals(128, model.textureHeight);
    }

    @Test void defaultTextureSizeIs16x16() {
        var model=CosmeticModel.parse(cube("north","", ""));
        assertEquals(16, model.textureWidth);
        assertEquals(16, model.textureHeight);
    }
}
