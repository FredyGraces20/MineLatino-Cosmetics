package com.minelatino.cosmetics.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Open MineLatino avatar ZIP: Bedrock geometry, PNG texture and animation JSON. */
public record AvatarPackage(AvatarModel model, AvatarAnimation animations, byte[] texturePng) {
    private static final int MAX_FILES=256, MAX_ENTRY=16*1024*1024, MAX_TOTAL=96*1024*1024;
    private static final Set<String> MOVEMENT=Set.of("idle","walk","run","jump","fly","elytra_fly","swim","swim_stand","sneak","sneaking","sit","boat","ride","attacked","death","riptide","sleep","climb","climbing","use_mainhand","use_offhand","swing_hand");
    public static AvatarPackage parse(byte[] zip) throws IOException {
        if(zip==null||zip.length==0||zip.length>32*1024*1024)throw new IOException("Avatar ZIP size is invalid");
        Map<String,byte[]>files=new LinkedHashMap<>();int count=0,total=0;
        try(ZipInputStream input=new ZipInputStream(new ByteArrayInputStream(zip))){ZipEntry entry;while((entry=input.getNextEntry())!=null){if(entry.isDirectory())continue;if(++count>MAX_FILES)throw new IOException("Too many avatar files");String name=entry.getName().replace('\\','/');if(name.startsWith("/")||name.matches("^[A-Za-z]:.*")||name.split("/").length>16)throw new IOException("Unsafe avatar path");for(String part:name.split("/"))if(part.equals(".."))throw new IOException("Unsafe avatar path");ByteArrayOutputStream out=new ByteArrayOutputStream();byte[]buf=new byte[8192];int n,entrySize=0;while((n=input.read(buf))>=0){entrySize+=n;total+=n;if(entrySize>MAX_ENTRY||total>MAX_TOTAL)throw new IOException("Expanded avatar ZIP is too large");out.write(buf,0,n);}files.put(name.toLowerCase(Locale.ROOT),out.toByteArray());}}
        byte[]model=unique(files,"main.json"),texture=texture(files);if(texture.length<8||texture[0]!=(byte)0x89||texture[1]!=0x50||texture[2]!=0x4e||texture[3]!=0x47)throw new IOException("Invalid avatar texture.png");
        List<String>animationSources=new ArrayList<>();for(var e:files.entrySet())if(e.getKey().endsWith(".animation.json"))animationSources.add(new String(e.getValue(),java.nio.charset.StandardCharsets.UTF_8));
        try{return new AvatarPackage(AvatarModel.parse(new String(model,java.nio.charset.StandardCharsets.UTF_8)),AvatarAnimation.parse(animationSources),texture);}catch(RuntimeException e){throw new IOException("Invalid avatar model or animation: "+e.getMessage(),e);}
    }
    private static byte[]unique(Map<String,byte[]>files,String suffix)throws IOException{List<byte[]>found=files.entrySet().stream().filter(e->e.getKey().equals(suffix)||e.getKey().endsWith("/"+suffix)).map(Map.Entry::getValue).toList();if(found.size()!=1)throw new IOException("Avatar ZIP needs exactly one "+suffix);return found.getFirst();}
    private static byte[]texture(Map<String,byte[]>files)throws IOException{List<byte[]>named=files.entrySet().stream().filter(e->e.getKey().equals("texture.png")||e.getKey().endsWith("/texture.png")).map(Map.Entry::getValue).toList();if(named.size()==1)return named.getFirst();List<byte[]>all=files.entrySet().stream().filter(e->e.getKey().endsWith(".png")).map(Map.Entry::getValue).toList();if(named.isEmpty()&&all.size()==1)return all.getFirst();throw new IOException("Avatar ZIP needs texture.png or exactly one PNG texture");}
    public List<String> emotes(){List<String>explicit=animations.names().stream().filter(name->{String simple=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);return simple.matches("extra\\d+")||simple.startsWith("emote");}).toList();if(!explicit.isEmpty())return explicit;return animations.names().stream().filter(name->{String simple=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);return !MOVEMENT.contains(simple)&&!simple.startsWith("pre_parallel")&&!simple.matches("parallel\\d+")&&!simple.startsWith("use_");}).toList();}
}
