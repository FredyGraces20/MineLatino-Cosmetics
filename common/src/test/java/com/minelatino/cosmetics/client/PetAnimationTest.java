package com.minelatino.cosmetics.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PetAnimationTest {
    private static final String JSON = """
        {"animations":{"animation.pet.idle":{"loop":true,"animation_length":2,"bones":{"root":{
          "position":{"0":[0,0,0],"1":[0,4,0],"2":[0,0,0]},
          "rotation":{"0":[0,0,0],"1":[0,180,0]},"scale":[1,1,1]}}}}}
        """;

    @Test void selectsAndInterpolatesBlockbenchClip() {
        var animation=PetAnimation.parse(JSON,"animation.pet.idle");
        assertEquals(java.util.List.of("animation.pet.idle"),animation.names());
        assertEquals(2f,animation.sample(.5).position()[1],.0001f);
        assertEquals(90f,animation.sample(.5).rotation()[1],.0001f);
        assertEquals(2f,animation.sample(2.5).position()[1],.0001f);
    }

    @Test void rejectsMolangUntilItCanBeEvaluatedSafely() {
        assertThrows(IllegalArgumentException.class,()->PetAnimation.parse(
          "{\"animations\":{\"x\":{\"bones\":{\"root\":{\"rotation\":[\"query.x\",0,0]}}}}}","x"));
    }
}
