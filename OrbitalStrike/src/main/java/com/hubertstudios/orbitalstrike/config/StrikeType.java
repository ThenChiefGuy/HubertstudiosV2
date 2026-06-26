package com.hubertstudios.orbitalstrike.config;

import java.util.Locale;
import java.util.Optional;

public enum StrikeType {
    DOG("dog"),
    WITHER("wither"),
    NUKE("nuke"),
    STRIKE("strike");

    private final String key;

    StrikeType(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static Optional<StrikeType> fromString(String s) {
        if (s == null) return Optional.empty();
        String lower = s.toLowerCase(Locale.ROOT);
        for (StrikeType t : values()) {
            if (t.key.equals(lower)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
