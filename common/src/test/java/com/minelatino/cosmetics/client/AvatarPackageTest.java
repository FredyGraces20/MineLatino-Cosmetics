package com.minelatino.cosmetics.client;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class AvatarPackageTest {
    private static byte[] zip(String model,String animation)throws Exception{ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(bytes)){put(zip,"converted/main.json",model.getBytes(StandardCharsets.UTF_8));put(zip,"converted/texture.png",new byte[]{(byte)0x89,0x50,0x4e,0x47,13,10,26,10});if(animation!=null)put(zip,"converted/main.animation.json",animation.getBytes(StandardCharsets.UTF_8));}return bytes.toByteArray();}
    private static void put(ZipOutputStream zip,String name,byte[]data)throws Exception{zip.putNextEntry(new ZipEntry(name));zip.write(data);zip.closeEntry();}
    @Test void parsesOpenAvatarZipAndSamplesMolang()throws Exception{String model="""
      {"minecraft:geometry":[{"description":{"texture_width":64,"texture_height":64},"bones":[{"name":"root","pivot":[0,0,0],"cubes":[{"origin":[-1,0,-1],"size":[2,2,2],"uv":{"north":{"uv":[0,0],"uv_size":[2,2]}}}]}]}]}
      """;String animation="""
      {"animations":{"wave":{"loop":true,"animation_length":2,"bones":{"root":{"rotation":[0,"20*math.sin(query.anim_time*90)",0]}}}}}
      """;AvatarPackage parsed=AvatarPackage.parse(zip(model,animation));assertEquals(1,parsed.model().roots().size());assertEquals("wave",parsed.emotes().getFirst());assertEquals(20,parsed.animations().sample("wave","root",1).rotation()[1],.01);}
    @Test void layersPrePrimaryAndParallelAnimationsWithYsmPriority()throws Exception{String animation="""
      {"animations":{
        "pre_parallel0":{"bones":{"fx":{"position":[1,0,0],"rotation":[10,0,0],"scale":0}}},
        "idle":{"bones":{"fx":{"rotation":[20,0,0]}}},
        "attack":{"bones":{"fx":{"rotation":[30,0,0],"scale":1}}},
        "parallel0":{"bones":{"fx":{"position":[3,0,0],"rotation":[5,0,0]}}}
      }}
      """;AvatarAnimation animations=AvatarAnimation.parse(java.util.List.of(animation));
      AvatarAnimation.Pose idle=animations.sampleLayered("idle","fx",0,0,0,0);
      assertArrayEquals(new float[]{3,0,0},idle.position(),.001f);
      assertArrayEquals(new float[]{25,0,0},idle.rotation(),.001f);
      assertArrayEquals(new float[]{0,0,0},idle.scale(),.001f);
      AvatarAnimation.Pose attack=animations.sampleLayered("attack","fx",0,0,0,0);
      assertArrayEquals(new float[]{1,1,1},attack.scale(),.001f);
    }
    @Test void rejectsTraversal()throws Exception{ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(bytes)){put(zip,"../main.json","{}".getBytes(StandardCharsets.UTF_8));}assertThrows(java.io.IOException.class,()->AvatarPackage.parse(bytes.toByteArray()));}

    @Test void bakesBedrockAxesFacesUvsInflationAndMirroringLikeGeckoLib(){
      String model="""
        {"minecraft:geometry":[{"description":{"texture_width":64,"texture_height":64},"bones":[
          {"name":"root","cubes":[
            {"origin":[-1,2,3],"size":[2,4,6],"inflate":1,"uv":{"north":{"uv":[0,0],"uv_size":[2,4]}}},
            {"origin":[-1,2,3],"size":[2,4,6],"mirror":true,"uv":{"west":{"uv":[4,8],"uv_size":[6,4]}}}
          ]}
        ]}]}
        """;
      AvatarModel parsed=AvatarModel.parse(model);
      AvatarModel.Cube normal=parsed.roots().getFirst().cubes().getFirst();
      AvatarGeometry.Quad north=AvatarGeometry.bake(parsed,normal).getFirst();
      assertArrayEquals(new float[]{-.125f,.4375f,.125f,.03125f,0},north.vertices()[0],.00001f);
      assertArrayEquals(new float[]{.125f,.4375f,.125f,0,0},north.vertices()[1],.00001f);
      assertArrayEquals(new float[]{0,0,-1},north.normal(),.00001f);

      AvatarModel.Cube mirrored=parsed.roots().getFirst().cubes().get(1);
      AvatarGeometry.Quad west=AvatarGeometry.bake(parsed,mirrored).getFirst();
      assertEquals(.0625f,west.vertices()[0][0],.00001f); // mirrored WEST uses the EAST plane
      assertEquals(4/64f,west.vertices()[0][3],.00001f);  // mirrored UVs do not reverse U
      assertArrayEquals(new float[]{1,0,0},west.normal(),.00001f);
    }

    @Test void supportsYsmPostKeyframesCatmullRomAndPlayOnceDefault(){
      String animation="""
        {"animations":{"motion":{"animation_length":3,"bones":{"root":{"position":{
          "0":{"post":[0,0,0],"lerp_mode":"catmullrom"},
          "1":{"post":[0,0,0],"lerp_mode":"catmullrom"},
          "2":{"post":[10,0,0],"lerp_mode":"catmullrom"},
          "3":{"post":[0,0,0],"lerp_mode":"catmullrom"}
        }}}}}}
        """;
      AvatarAnimation parsed=AvatarAnimation.parse(java.util.List.of(animation));
      assertEquals(5.625f,parsed.sample("motion","root",1.500001).position()[0],.01f);
      assertEquals(0,parsed.sample("motion","root",6).position()[0],.001f); // no loop field means play once
    }

    @Test void evaluatesYsmMovementAndEquipmentMolangContext(){
      String animation="""
        {"animations":{"context":{"bones":{"root":{"rotation":[
          "math.exp(0)+query.ground_speed", "math.pow(2,3)+ysm.has_mainhand", "ysm.has_offand+ysm.has_helmet"
        ]}}}}}
        """;
      AvatarAnimation parsed=AvatarAnimation.parse(java.util.List.of(animation));
      AvatarAnimation.Context context=new AvatarAnimation.Context(0,0,.4f,0,true,true,true);
      assertArrayEquals(new float[]{1.4f,9,2},parsed.sample("context","root",0,context).rotation(),.001f);
    }

    @Test void validatesAnExternalConvertedYsmFixtureWhenConfigured()throws Exception{
      String fixture=System.getenv("MINELATINO_AVATAR_FIXTURE");
      org.junit.jupiter.api.Assumptions.assumeTrue(fixture!=null&&!fixture.isBlank());
      AvatarPackage parsed=AvatarPackage.parse(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(fixture)));
      assertTrue(parsed.model().boneNames().size()>20);
      assertTrue(parsed.animations().has("idle"));
      for(AvatarModel.Bone root:parsed.model().roots())validateBone(parsed.model(),root);
    }

    private static void validateBone(AvatarModel model,AvatarModel.Bone bone){
      for(AvatarModel.Cube cube:bone.cubes())for(AvatarGeometry.Quad quad:AvatarGeometry.bake(model,cube))
        for(float[]vertex:quad.vertices())for(float value:vertex)assertTrue(Float.isFinite(value));
      for(AvatarModel.Bone child:model.children(bone.name()))validateBone(model,child);
    }
}
