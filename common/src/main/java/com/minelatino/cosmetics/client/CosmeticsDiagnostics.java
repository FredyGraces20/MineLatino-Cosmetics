package com.minelatino.cosmetics.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.LoggerFactory;

/** Bounded support trace. Never pass tokens, sessions, HTTP bodies or exception messages. */
public final class CosmeticsDiagnostics {
    private static final ArrayDeque<String> EVENTS = new ArrayDeque<>();
    private static final Map<String,String> STATES = new LinkedHashMap<>();
    private CosmeticsDiagnostics() {}
    public static synchronized void event(String code,String details) {
        String line=Instant.now()+" [ML-DIAG] "+clean(code)+" "+clean(details);
        if(EVENTS.size()>=400) EVENTS.removeFirst();
        EVENTS.addLast(line);
        LoggerFactory.getLogger("MineLatino Cosmetics").info("{}",line);
    }
    public static synchronized void changed(String key,String details) {
        if(details.equals(STATES.get(key))) return;
        if(STATES.size()>=128 && !STATES.containsKey(key)) STATES.remove(STATES.keySet().iterator().next());
        STATES.put(key,details); event(key,details);
    }
    public static String failure(Throwable error) {
        for(int i=0;i<8 && error.getCause()!=null && error.getCause()!=error;i++) error=error.getCause();
        return error.getClass().getSimpleName()+(error instanceof ApiClient.ApiException api ? " http="+api.status : "");
    }
    public static String id(String value) {
        if(value==null) return "none";
        String safe=value.replaceAll("[^a-zA-Z0-9_-]","_");
        return safe.substring(0,Math.min(80,safe.length()));
    }
    private static String clean(String value) {
        String safe=value.replaceAll("[\\r\\n\\t]"," ");
        return safe.substring(0,Math.min(700,safe.length()));
    }
    public static synchronized String report(String snapshot) {
        return "MineLatino Cosmetics — diagnóstico\nFecha UTC: "+Instant.now()+
                "\nContiene UUID de tu cuenta/jugador; no tokens, contraseñas ni cuerpos HTTP.\n"+
                "Render registrado significa código ejecutado, no comprobación visual.\n\nESTADO ACTUAL\n"+
                snapshot+"\n\nÚLTIMOS EVENTOS (máximo 400)\n"+String.join("\n",EVENTS)+"\n";
    }
    public static Path export(Path gameDirectory,String report) throws java.io.IOException {
        Path directory=gameDirectory.resolve("logs"); Files.createDirectories(directory);
        Path target=Files.createTempFile(directory,"minelatino-diagnostico-",".txt");
        Files.writeString(target,report,StandardCharsets.UTF_8); return target;
    }
}
