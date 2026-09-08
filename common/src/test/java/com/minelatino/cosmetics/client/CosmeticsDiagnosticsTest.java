package com.minelatino.cosmetics.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

class CosmeticsDiagnosticsTest {
    @TempDir Path game;
    @Test void exceptionMessagesAndTokensAreNotIncluded() {
        var error=new RuntimeException("Bearer secret",new ApiClient.ApiException(401,"private-token"));
        String result=CosmeticsDiagnostics.failure(error);
        assertEquals("ApiException http=401",result);
        assertFalse(result.contains("secret")); assertFalse(result.contains("private-token"));
    }
    @Test void repeatedFramesAreDeduplicatedAndHistoryIsBounded() {
        String key="TEST_"+java.util.UUID.randomUUID();
        for(int i=0;i<100;i++) CosmeticsDiagnostics.changed(key,"same frame");
        assertEquals(1,CosmeticsDiagnostics.report("").lines().filter(l->l.contains(key)).count());
        for(int i=0;i<410;i++) CosmeticsDiagnostics.event("TEST_EVENT","index="+i);
        assertEquals(400,CosmeticsDiagnostics.report("").lines().filter(l->l.contains("[ML-DIAG]")).count());
    }
    @Test void exportIsReadableAndDoesNotOverwriteEarlierReports() throws Exception {
        String report=CosmeticsDiagnostics.report("identityMismatch=true");
        Path first=CosmeticsDiagnostics.export(game,report), second=CosmeticsDiagnostics.export(game,report);
        assertNotEquals(first,second);
        assertEquals(game.resolve("logs"),first.getParent());
        assertEquals(report,Files.readString(first));
    }
}
