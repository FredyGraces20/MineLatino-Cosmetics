package com.minelatino.cosmetics.core;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Closed action vocabulary: remote configuration never contains executable code. */
public final class MenuPolicy {
    private MenuPolicy() {}
    public enum Action { WARDROBE, WEBSITE }
    public static final int MAX_BUTTONS = 3;
    /** Allowed root domains — subdomains of these are also accepted. */
    private static final Set<String> ALLOWED_DOMAINS = Set.of("minelatino.com", "minelatino.shop", "discord.com");
    private static final Set<String> RENAMABLE = Set.of(
        "menu.returnToGame", "menu.options", "menu.disconnect", "menu.returnToMenu",
        "gui.advancements", "gui.stats", "menu.sendFeedback", "menu.reportBugs", "menu.shareToLan");
    public static boolean canRename(String key) { return RENAMABLE.contains(key); }
    public static String label(String input) {
        if (input == null || input.isBlank() || input.length() > 40 || input.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid button label");
        return input.strip();
    }
    public static URI website(String input) {
        URI uri = URI.create(input);
        String host = uri.getHost() != null ? uri.getHost().toLowerCase(Locale.ROOT) : "";
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
            || !isAllowedHost(host)
            || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443))
            throw new IllegalArgumentException("Website is not allowed");
        return uri;
    }
    private static boolean isAllowedHost(String host) {
        for (String domain : ALLOWED_DOMAINS) {
            if (host.equals(domain) || host.endsWith("." + domain)) return true;
        }
        return false;
    }
}
