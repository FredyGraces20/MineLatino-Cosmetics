package com.minelatino.cosmetics.client;

/**
 * Immutable snapshot of a verified premium session.
 * The token is opaque and short-lived; uuid is the canonical premium identity.
 */
public record Session(String token, String uuid, String name, long expiresAt) {
    public boolean isExpired() {
        return System.currentTimeMillis() >= expiresAt;
    }
}
