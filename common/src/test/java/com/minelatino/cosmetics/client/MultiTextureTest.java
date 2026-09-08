package com.minelatino.cosmetics.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
class MultiTextureTest {
    @Test void resolvesTextureAliasesAndRejectsCycles() {
        String face = "\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{\"north\":{\"texture\":\"#2\"},\"south\":{\"texture\":\"#3\"}}}]";
        var model = CosmeticModel.parse("{\"textures\":{\"2\":\"#alias\",\"alias\":\"pack/bubbles\",\"3\":\"pack/main\"},"+face+"}");
        assertEquals("bubbles",model.quads.get(0).texture()); assertEquals("main",model.quads.get(1).texture());
        assertThrows(IllegalArgumentException.class,()->CosmeticModel.parse("{\"textures\":{\"2\":\"#2\"},"+face+"}"));
    }
    @Test void frameUvsAndFractionalTime() {
        var animation = TextureAnimation.parse("{\"animation\":{\"frametime\":1.8}}",16,320);
        assertEquals(20,animation.rows()); assertEquals(0,animation.frame(0)); assertEquals(1,animation.frame(1.81));
        assertEquals(0,animation.frame(36));
        assertEquals(1f/20,animation.vertex(new float[]{0,0,0,0,0},1.81)[4],0.00001);
        assertEquals(1,TextureAnimation.still().rows());
        assertThrows(IllegalArgumentException.class,()->TextureAnimation.parse("{\"animation\":{\"frametime\":0}}",16,32));
    }
    @Test void suppliedBackpackUsesBothTextures() throws Exception {
        String path = System.getenv("HEART_OF_SEA_MODEL");
        org.junit.jupiter.api.Assumptions.assumeTrue(path != null);
        var model = CosmeticModel.parse(Files.readString(Path.of(path)));
        assertEquals(java.util.Set.of("heart_of_the_sea_animation2","heart_of_the_sea_texture"),
                new java.util.HashSet<>(model.quads.stream().map(CosmeticModel.Quad::texture).toList()));
        assertFalse(model.quads.isEmpty());
    }
}
