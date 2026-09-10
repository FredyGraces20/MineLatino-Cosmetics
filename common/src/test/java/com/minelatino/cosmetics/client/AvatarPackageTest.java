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
    @Test void rejectsTraversal()throws Exception{ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(bytes)){put(zip,"../main.json","{}".getBytes(StandardCharsets.UTF_8));}assertThrows(java.io.IOException.class,()->AvatarPackage.parse(bytes.toByteArray()));}
}
