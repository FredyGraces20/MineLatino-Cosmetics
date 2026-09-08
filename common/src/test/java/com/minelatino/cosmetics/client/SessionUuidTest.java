package com.minelatino.cosmetics.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionUuidTest {
    @Test void normalizedSessionUuidCanBeUsedAsMinecraftUuid() {
        var uuid = java.util.UUID.fromString(format("1234567890abcdef1234567890abcdef"));
        assertEquals("12345678-90ab-cdef-1234-567890abcdef", uuid.toString());
    }
    @Test void malformedSessionUuidIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> format("offline-player"));
    }
    private static String format(String value) {
        String hex=value.replace("-", "");
        if (!hex.matches("[0-9a-fA-F]{32}")) throw new IllegalArgumentException();
        return hex.substring(0,8)+"-"+hex.substring(8,12)+"-"+hex.substring(12,16)+"-"+hex.substring(16,20)+"-"+hex.substring(20);
    }
}
