package com.hubertstudios.orbitalstrike.config;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Logger;

/**
 * Central, hot-reloadable configuration holder. Call {@link #reload()} to
 * re-read config.yml from disk. The volatile fields are swapped atomically
 * on reload; strike executions that are already in-flight (mid fire-delay
 * or mid repeat-sequence) read each StrikeConfig once at the point they need
 * it, so a reload won't corrupt a strike that's already underway, though it
 * may apply to the very next repeat iteration - that's an accepted tradeoff
 * for keeping the implementation simple and lock-free.
 */
public final class PluginConfig {

    private final JavaPlugin plugin;
    private final Logger logger;

    private volatile String prefix;
    private volatile long globalCooldownSeconds;
    private volatile boolean perTypeCooldownEnabled;
    private volatile long rodExpirySeconds;
    private volatile double maxTargetDistance;
    private volatile boolean fallbackMaxRange;
    private volatile long bombStrikeIntervalTicks;

    private volatile String targetingMode;
    private volatile String markerType;
    private volatile Material markerBlock;
    private volatile long raycastUpdateIntervalTicks;

    private volatile Sound soundRodGiven;
    private volatile Sound soundTargetLocked;
    private volatile Sound soundPreFireWarning;
    private volatile Sound soundFire;

    private volatile DogConfig dogConfig;
    private volatile WitherConfig witherConfig;
    private volatile NukeConfig nukeConfig;
    private volatile SingleStrikeConfig strikeConfig;

    public PluginConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();

        this.prefix = cfg.getString("general.prefix", "");
        this.globalCooldownSeconds = cfg.getLong("general.global-cooldown-seconds", 10);
        this.perTypeCooldownEnabled = cfg.getBoolean("general.per-type-cooldown-enabled", false);
        this.rodExpirySeconds = cfg.getLong("general.rod-expiry-seconds", 30);
        this.maxTargetDistance = cfg.getDouble("general.max-target-distance", 150);
        this.fallbackMaxRange = "max-range".equalsIgnoreCase(cfg.getString("general.fallback-on-miss", "cancel"));
        this.bombStrikeIntervalTicks = Math.max(1, cfg.getLong("general.bomb-strike-interval-ticks", 20));

        this.targetingMode = safeLower(cfg.getString("targeting.mode", "raycast"));
        this.markerType = safeLower(cfg.getString("targeting.marker-type", "block-display"));
        this.markerBlock = parseMaterial(cfg.getString("targeting.marker-block", "REDSTONE_BLOCK"), Material.REDSTONE_BLOCK);
        this.raycastUpdateIntervalTicks = Math.max(1, cfg.getLong("targeting.raycast-update-interval-ticks", 4));

        this.soundRodGiven = parseSound(cfg.getString("sounds.rod-given"));
        this.soundTargetLocked = parseSound(cfg.getString("sounds.target-locked"));
        this.soundPreFireWarning = parseSound(cfg.getString("sounds.pre-fire-warning"));
        this.soundFire = parseSound(cfg.getString("sounds.fire"));

        this.dogConfig = new DogConfig(requireSection(cfg, "strikes.dog"), logger);
        this.witherConfig = new WitherConfig(requireSection(cfg, "strikes.wither"), logger);
        this.nukeConfig = new NukeConfig(requireSection(cfg, "strikes.nuke"), logger);
        this.strikeConfig = new SingleStrikeConfig(requireSection(cfg, "strikes.strike"), logger);
    }

    private org.bukkit.configuration.ConfigurationSection requireSection(FileConfiguration cfg, String path) {
        org.bukkit.configuration.ConfigurationSection section = cfg.getConfigurationSection(path);
        if (section == null) {
            throw new IllegalStateException("config.yml is missing required section: " + path);
        }
        return section;
    }

    private static String safeLower(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT);
    }

    private Material parseMaterial(String name, Material fallback) {
        if (name == null) return fallback;
        Material m = Material.matchMaterial(name);
        if (m == null) {
            logger.warning("[OrbitalStrike] Unknown material '" + name + "', falling back to " + fallback);
            return fallback;
        }
        return m;
    }

    private Sound parseSound(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return Sound.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            logger.warning("[OrbitalStrike] Unknown sound key: " + name);
            return null;
        }
    }

    public String prefix() { return prefix; }
    public long globalCooldownSeconds() { return globalCooldownSeconds; }
    public boolean perTypeCooldownEnabled() { return perTypeCooldownEnabled; }
    public long rodExpirySeconds() { return rodExpirySeconds; }
    public double maxTargetDistance() { return maxTargetDistance; }
    public boolean fallbackMaxRange() { return fallbackMaxRange; }
    public long bombStrikeIntervalTicks() { return bombStrikeIntervalTicks; }

    public String targetingMode() { return targetingMode; }
    public String markerType() { return markerType; }
    public Material markerBlock() { return markerBlock; }
    public long raycastUpdateIntervalTicks() { return raycastUpdateIntervalTicks; }

    public Sound soundRodGiven() { return soundRodGiven; }
    public Sound soundTargetLocked() { return soundTargetLocked; }
    public Sound soundPreFireWarning() { return soundPreFireWarning; }
    public Sound soundFire() { return soundFire; }

    public DogConfig dog() { return dogConfig; }
    public WitherConfig wither() { return witherConfig; }
    public NukeConfig nuke() { return nukeConfig; }
    public SingleStrikeConfig strike() { return strikeConfig; }

    public BaseStrikeConfig forType(StrikeType type) {
        return switch (type) {
            case DOG -> dogConfig;
            case WITHER -> witherConfig;
            case NUKE -> nukeConfig;
            case STRIKE -> strikeConfig;
        };
    }
}
