package com.hubertstudios.orbitalstrike.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Converts '&'-coded strings from config.yml into Adventure Components.
 * Centralizing this keeps the rest of the codebase free of repeated
 * serializer lookups and makes it trivial to swap to MiniMessage later
 * if desired, without touching call sites.
 */
public final class TextUtil {

    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.legacyAmpersand();

    private TextUtil() {
    }

    public static Component colorize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        return SERIALIZER.deserialize(raw);
    }

    public static Component prefixed(String prefix, String message) {
        String combined = (prefix == null ? "" : prefix) + (message == null ? "" : message);
        return colorize(combined);
    }
}
