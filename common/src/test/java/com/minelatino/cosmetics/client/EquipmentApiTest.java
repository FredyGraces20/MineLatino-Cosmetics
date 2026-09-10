package com.minelatino.cosmetics.client;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EquipmentApiTest {
    private static final String UUID="1234567890abcdef1234567890abcdef";
    private static final String OFFLINE_UUID="abcdef1234567890abcdef1234567890";
    @Test void delayedPublicAppearanceCannotUndoNewEquip() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        server.createContext("/v1/cosmetics/appearance",exchange->{
            entered.countDown();
            try { release.await(5,TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            byte[] body=("{\"players\":[{\"uuid\":\""+UUID+"\",\"equipped\":[]}]} ").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try {
            var cache=new EquipmentCache(new ApiClient("http://127.0.0.1:"+server.getAddress().getPort()));
            var read=CompletableFuture.runAsync(()->cache.refresh(List.of(UUID)));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            cache.setEquipped(UUID,List.of(new EquipmentCache.EquippedItem("HAT","hat")));
            release.countDown(); read.get(5,TimeUnit.SECONDS);
            assertEquals("hat",cache.get(UUID).getFirst().cosmeticId());
        } finally { release.countDown(); server.stop(0); }
    }
    @Test void equipSendsNullForRemovalAndReadsRealResponseContract() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicReference<String> input=new AtomicReference<>(), auth=new AtomicReference<>(), method=new AtomicReference<>();
        server.createContext("/v1/cosmetics/me/equipment",exchange->{
            input.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization")); method.set(exchange.getRequestMethod());
            byte[] data="{\"equipped\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,data.length); exchange.getResponseBody().write(data); exchange.close();
        }); server.start();
        try {
            var api=new ApiClient("http://127.0.0.1:"+server.getAddress().getPort());
            assertTrue(api.equip("local-test-token","BACKPACK",null).equipped().isEmpty());
            assertEquals("PUT",method.get()); assertEquals("Bearer local-test-token",auth.get());
            assertTrue(input.get().contains("\"cosmeticId\":null")); assertTrue(input.get().contains("BACKPACK"));
        } finally { server.stop(0); }
    }
    @Test void verifiedNameMapsPremiumAppearanceToOfflineServerUuid() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicReference<String> query=new AtomicReference<>();
        server.createContext("/v1/cosmetics/appearance",exchange->{
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] body=("{\"players\":[{\"uuid\":\""+OFFLINE_UUID+"\",\"name\":null,\"equipped\":[]},"
                    +"{\"uuid\":\""+UUID+"\",\"name\":\"TestPlayer\",\"equipped\":[{\"slot\":\"BACKPACK\",\"cosmeticId\":\"pack\"}]}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try {
            var cache=new EquipmentCache(new ApiClient("http://127.0.0.1:"+server.getAddress().getPort()));
            cache.refresh(List.of(OFFLINE_UUID), java.util.Map.of(OFFLINE_UUID,"TestPlayer"));
            assertTrue(query.get().contains("names=TestPlayer"));
            assertEquals("pack",cache.get(OFFLINE_UUID).getFirst().cosmeticId());
            assertEquals("pack",cache.get(UUID).getFirst().cosmeticId());
        } finally { server.stop(0); }
    }
}
