package com.minelatino.cosmetics.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** User-editable emote wheel key, stored per Minecraft instance. */
public final class EmoteKeyConfig {
    public static final int DEFAULT_KEY=66; // GLFW_KEY_B
    private final Path path;private int key;
    private EmoteKeyConfig(Path path,int key){this.path=path;this.key=key;}
    public static EmoteKeyConfig read(Path gameDir){Path path=gameDir.resolve("config/minelatino-cosmetics/emotes.properties");int key=DEFAULT_KEY;try{if(Files.exists(path)){Properties p=new Properties();try(var in=Files.newInputStream(path)){p.load(in);}key=Integer.parseInt(p.getProperty("wheelKey",String.valueOf(DEFAULT_KEY)));}if(key<32||key>348)key=DEFAULT_KEY;}catch(Exception ignored){key=DEFAULT_KEY;}return new EmoteKeyConfig(path,key);}
    public int key(){return key;}
    public void set(int next){if(next<32||next>348)return;key=next;try{Files.createDirectories(path.getParent());Properties p=new Properties();p.setProperty("wheelKey",String.valueOf(key));try(var out=Files.newOutputStream(path)){p.store(out,"MineLatino emote wheel");}}catch(Exception ignored){}}
}
