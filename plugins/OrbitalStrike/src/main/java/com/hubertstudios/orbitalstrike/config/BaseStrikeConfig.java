package com.hubertstudios.orbitalstrike.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Fields and parsing logic shared by every strike type: permission, rod
 * appearance, repeat behavior, and the pre-fire warning. Type-specific
 * payload settings live in the subclasses (DogConfig, WitherConfig, etc).
 */
public abstract class BaseStrikeConfig {

    private final String permission;
    private final boolean allowBomb;
    private final RodConfig rod;
    private final long fireDelayTicks;
    private final int repeatCount;
    private final long repeatIntervalTicks;
    private final long bombIntervalTicks;
    private final WarningConfig warning;

    protected BaseStrikeConfig(ConfigurationSection section, Logger logger, long defaultBombIntervalTicks) {
        if (section == null) {
            throw new IllegalArgumentException("Missing strike config section");
        }
        this.permission = section.getString("permission", "orbitalstrike.use");
        this.allowBomb = section.getBoolean("allow-bomb", true);
        ConfigurationSection rodSection = section.getConfigurationSection("rod");
        this.rod = new RodConfig(rodSection != null ? rodSection : section, logger);
        this.fireDelayTicks = Math.max(0, section.getLong("fire-delay-ticks", 20));
        this.repeatCount = Math.max(1, section.getInt("repeat-count", 1));
        this.repeatIntervalTicks = Math.max(1, section.getLong("repeat-interval-ticks", 20));
        this.bombIntervalTicks = Math.max(1, section.getLong("bomb-interval-ticks", defaultBombIntervalTicks));
        ConfigurationSection warnSection = section.getConfigurationSection("warning");
        this.warning = new WarningConfig(warnSection);
    }

    public String permission() {
        return permission;
    }

    public boolean allowBomb() {
        return allowBomb;
    }

    public RodConfig rod() {
        return rod;
    }

    public long fireDelayTicks() {
        return fireDelayTicks;
    }

    public int repeatCount() {
        return repeatCount;
    }

    public long repeatIntervalTicks() {
        return repeatIntervalTicks;
    }

    public long bombIntervalTicks() {
        return bombIntervalTicks;
    }

    public WarningConfig warning() {
        return warning;
    }

    /** Shared helper: parses an "effects"."list" potion-effect list, gated by "effects"."enabled". */
    protected static List<PotionEffect> parsePotionEffects(ConfigurationSection section, Logger logger) {
        List<PotionEffect> result = new ArrayList<>();
        if (section == null || !section.getBoolean("effects.enabled", false)) {
            return result;
        }
        List<?> rawList = section.getList("effects.list");
        if (rawList == null) return result;
        for (Object o : rawList) {
            PotionEffect parsed = parseSingleEffect(o, logger);
            if (parsed != null) result.add(parsed);
        }
        return result;
    }

    private static PotionEffect parseSingleEffect(Object o, Logger logger) {
        String typeName;
        int amplifier;
        int durationSeconds;
        if (o instanceof ConfigurationSection cs) {
            typeName = cs.getString("type");
            amplifier = cs.getInt("amplifier", 0);
            durationSeconds = cs.getInt("duration-seconds", 30);
        } else if (o instanceof java.util.Map<?, ?> map) {
            Object t = map.get("type");
            typeName = t != null ? t.toString() : null;
            amplifier = map.get("amplifier") instanceof Number n ? n.intValue() : 0;
            durationSeconds = map.get("duration-seconds") instanceof Number n ? n.intValue() : 30;
        } else {
            return null;
        }
        if (typeName == null) return null;
        PotionEffectType type = PotionEffectType.getByName(typeName);
        if (type == null) {
            logger.warning("[OrbitalStrike] Unknown potion effect type: " + typeName);
            return null;
        }
        return new PotionEffect(type, durationSeconds * 20, amplifier);
    }
}
