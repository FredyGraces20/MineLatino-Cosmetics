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

    @Test void selectsIdleWalkAndAttackClipsByConventionalNames() {
        var animation=PetAnimation.parse("""
          {"animations":{
            "animation.pet.idle":{"bones":{"root":{"position":[0,1,0]}}},
            "animation.pet.walk":{"bones":{"root":{"position":[0,2,0]}}},
            "animation.pet.attack":{"bones":{"root":{"position":[0,3,0]}}}
          }}
          """, "animation.pet.idle");
        assertEquals("animation.pet.idle", animation.clipName(PetAnimation.State.IDLE));
        assertEquals("animation.pet.walk", animation.clipName(PetAnimation.State.WALK));
        assertEquals("animation.pet.attack", animation.clipName(PetAnimation.State.ATTACK));
        assertEquals(2f, animation.sample(PetAnimation.State.WALK, 0).position()[1], .0001f);
        assertEquals(3f, animation.sample(PetAnimation.State.ATTACK, 0).position()[1], .0001f);
    }

    @Test void fallsBackToConfiguredClipWhenRequestedStateIsMissing() {
        var animation=PetAnimation.parse(JSON,"animation.pet.idle");
        assertEquals("animation.pet.idle", animation.clipName(PetAnimation.State.ATTACK));
    }
}
