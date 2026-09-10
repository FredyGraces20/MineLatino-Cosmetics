package com.minelatino.cosmetics.client;

import com.mojang.authlib.exceptions.AuthenticationException;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the premium authentication flow:
 * 1. Request a challenge from the backend (gets challengeId + serverId).
 * 2. Call Minecraft's session service joinServer with the player's credentials.
 * 3. Submit the challenge for verification; backend checks Mojang hasJoined.
 * 4. Store the resulting session for subsequent API calls.
 *
 * The Microsoft/Minecraft credential never leaves the client. The backend only
 * sees the challenge/verify exchange and checks Mojang's public session endpoint.
 */
public final class AuthManager {
    private static final Logger LOG = LoggerFactory.getLogger("MineLatino Cosmetics");

    public enum State { DISCONNECTED, AUTHENTICATING, CONNECTED, ERROR }

    private final ApiClient api;
    private final String accountToken;
    private final String accountId;
    private final long accountExpiresAt;
    private volatile Session session;
    private volatile State state = State.DISCONNECTED;
    private volatile String errorMessage;
    private CompletableFuture<Session> pendingAuth;

    public AuthManager(ApiClient api) {
        this(api, null, null, 0);
    }

    public AuthManager(ApiClient api, String accountToken, String accountId, long accountExpiresAt) {
        this.api = api;
        this.accountToken = accountToken;
        this.accountId = accountId;
        this.accountExpiresAt = accountExpiresAt;
    }

    public State state() { return state; }
    public Session session() { return session; }
    public String errorMessage() { return errorMessage; }

    public boolean isConnected() {
        return state == State.CONNECTED && session != null && !session.isExpired();
    }

    /**
     * Runs the full premium verification flow off-thread. There is deliberately
     * no UUID/nickname fallback: only Mojang's hasJoined response can establish
     * the owner of cosmetics.
     * Returns a future that completes with the session on success, or
     * completes exceptionally if any step fails.
     */
    public synchronized CompletableFuture<Session> authenticate() {
        if (pendingAuth != null && !pendingAuth.isDone()) return pendingAuth;
        state = State.AUTHENTICATING;
        errorMessage = null;

        Minecraft mc = Minecraft.getInstance();
        User user = mc.getUser();
        String username = user.getName();
        String profileId = user.getProfileId().toString().replace("-", "");
        CosmeticsDiagnostics.event("AUTH_START","accountUuid="+profileId);

        pendingAuth = CompletableFuture.supplyAsync(() -> {
            try {
                LOG.info("Attempting auth with backend URL: {}", api.getBaseUrl());
                if (accountToken != null && accountId != null && accountExpiresAt > System.currentTimeMillis()) {
                    ApiClient.AccountInfo account = api.accountSession(accountToken, profileId, username);
                    if (!accountId.equalsIgnoreCase(account.accountId()) || !"active".equals(account.status()))
                        throw new SecurityException("The cosmetics backend returned a different MineLatino account");
                    Session newSession = new Session(accountToken, accountId, account.nick(), accountExpiresAt);
                    this.session = newSession; this.state = State.CONNECTED; this.errorMessage = null;
                    CosmeticsDiagnostics.event("AUTH_CONNECTED", "mode=minelatino-account accountId=" + CosmeticsDiagnostics.id(accountId));
                    return newSession;
                }
                try {
                    ApiClient.HealthResponse health = api.health();
                    if (!health.premiumEnabled()) {
                        throw new ApiClient.ApiException(503, "premium-auth-disabled");
                    }
                    LOG.info("Backend health check: premiumEnabled=true, offlineAuthEnabled={}", health.offlineAuthEnabled());
                } catch (Exception e) {
                    LOG.warn("Health check failed, trying premium auth anyway. Error: {}", e.getMessage());
                }

                CosmeticsDiagnostics.event("AUTH_MODE","premium-fallback");
                ApiClient.ChallengeResponse challenge = api.challenge(username);
                joinServer(user, challenge.serverId());
                ApiClient.VerifyResponse verify = api.verify(challenge.challengeId());
                String verifiedProfile = WardrobeController.normalize(verify.uuid());
                if (!verifiedProfile.equalsIgnoreCase(profileId) || verify.token() == null || verify.token().isBlank()
                        || verify.expiresAt() <= System.currentTimeMillis()) {
                    throw new SecurityException("The cosmetics backend returned a different or expired Minecraft identity");
                }
                Session newSession = new Session(verify.token(), verify.uuid(), verify.name(), verify.expiresAt());
                this.session = newSession;
                this.state = State.CONNECTED;
                this.errorMessage = null;
                LOG.info("Premium verified: {} ({})", verify.name(), verify.uuid());
                CosmeticsDiagnostics.event("AUTH_CONNECTED","mode=premium sessionUuid="+CosmeticsDiagnostics.id(verify.uuid()));
                return newSession;
            } catch (Exception e) {
                this.state = State.ERROR;
                CosmeticsDiagnostics.event("AUTH_FAILED",CosmeticsDiagnostics.failure(e));
                this.errorMessage = describeError(e);
                LOG.warn("Premium authentication failed: {}", errorMessage);
                throw new RuntimeException(errorMessage, e);
            }
        });
        return pendingAuth;
    }

    /**
     * Calls MinecraftSessionService.joinServer with the player's credentials.
     * This is the standard authlib mechanism: it registers the player's session
     * with Mojang so the backend can verify via hasJoined.
     */
    private void joinServer(User user, String serverId) throws AuthenticationException {
        var sessionService = Minecraft.getInstance().getMinecraftSessionService();
        // joinServer(profileId, accessToken, serverId) — the credential stays between
        // this client and Mojang; the backend only gets the serverId back via hasJoined.
        sessionService.joinServer(user.getProfileId(), user.getAccessToken(), serverId);
    }

    public void logout() {
        CosmeticsDiagnostics.event("AUTH_LOGOUT","local session cleared");
        Session previous = session;
        session = null;
        state = State.DISCONNECTED;
        errorMessage = null;
        if (previous != null) CompletableFuture.runAsync(() -> {
            try { api.logout(previous.token()); } catch (Exception e) { LOG.debug("Logout request failed", e); }
        });
    }

    private static String describeError(Exception e) {
        if (e instanceof ApiClient.ApiException api) {
            return switch (api.status) {
                case 401 -> "Sesion de Minecraft no verificada";
                case 429 -> "Demasiados intentos; espera un minuto";
                case 503 -> "Verificacion premium no disponible";
                default -> "Error del servidor (" + api.status + ")";
            };
        }
        if (e instanceof AuthenticationException) {
            return "Se requiere una cuenta premium de Minecraft con una sesión válida";
        }
        if (e instanceof java.net.ConnectException || e instanceof java.net.http.HttpConnectTimeoutException) {
            return "No se pudo conectar con el servidor: " + e.getMessage();
        }
        if (e instanceof java.net.http.HttpTimeoutException) {
            return "Tiempo de espera agotado: " + e.getMessage();
        }
        if (e instanceof javax.net.ssl.SSLException) {
            return "Error SSL/TLS: " + e.getMessage();
        }
        return "Error de conexion (" + e.getClass().getSimpleName() + "): " + e.getMessage();
    }
}
