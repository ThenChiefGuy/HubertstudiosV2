package com.HubertStudios.coreguard.util;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TimeParser {
    private static final Pattern TOKEN = Pattern.compile("(\\d+)(mo|s|m|h|d|w)", Pattern.CASE_INSENSITIVE);

    private TimeParser() {}

    public static Duration parse(String input) {
        if (input == null || input.isBlank()) return Duration.ZERO;
        String clean = input.trim().toLowerCase(Locale.ROOT);
        if (clean.equals("perm") || clean.equals("permanent") || clean.equals("forever")) return Duration.ZERO;
        if (!looksLikeDuration(clean)) throw new IllegalArgumentException("Invalid duration: " + input);

        Matcher matcher = TOKEN.matcher(clean);
        long seconds = 0;
        while (matcher.find()) {
            long amount = Long.parseLong(matcher.group(1));
            String unit = matcher.group(2).toLowerCase(Locale.ROOT);
            seconds += switch (unit) {
                case "s" -> amount;
                case "m" -> amount * 60;
                case "h" -> amount * 3600;
                case "d" -> amount * 86400;
                case "w" -> amount * 604800;
                case "mo" -> amount * 2592000;
                default -> 0;
            };
        }
        return Duration.ofSeconds(seconds);
    }

    public static boolean looksLikeDuration(String input) {
        if (input == null) return false;
        String clean = input.trim().toLowerCase(Locale.ROOT);
        if (clean.equals("perm") || clean.equals("permanent") || clean.equals("forever")) return true;
        int pos = 0;
        Matcher matcher = TOKEN.matcher(clean);
        while (matcher.find()) {
            if (matcher.start() != pos) return false;
            pos = matcher.end();
        }
        return pos == clean.length() && pos > 0;
    }
}
