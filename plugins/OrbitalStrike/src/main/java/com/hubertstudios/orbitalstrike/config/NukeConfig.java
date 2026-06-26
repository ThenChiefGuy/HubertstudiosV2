package com.hubertstudios.orbitalstrike.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;
import java.util.logging.Logger;

/**
 * NUKE strike: a single carrier falls from the sky toward the raycast target
 * and splits into N primed TNT units once within {@code splitAltitude}
 * blocks of the target, arranged in the configured pattern (ring/grid/
 * random/line).
 */
public final class NukeConfig extends BaseStrikeConfig {

    public enum Pattern { RING, GRID, RANDOM, LINE }

    private final double carrierSpawnHeightOffset;
    private final double carrierFallSpeed;
    private final double carrierSplitAltitude;
    private final Material carrierDisplayBlock;

    private final Pattern pattern;
    private final double patternRadius;
    private final double patternSpacing;
    private final int unitCount;

    private final long fuseTicks;
    private final double yieldMultiplier;
    private final boolean breakBlocks;
    private final boolean setFire;

    public NukeConfig(ConfigurationSection section, Logger logger) {
        super(section, logger, 40);

        ConfigurationSection carrier = section.getConfigurationSection("carrier");
        this.carrierSpawnHeightOffset = carrier != null ? carrier.getDouble("spawn-height-offset", 80.0) : 80.0;
        this.carrierFallSpeed = carrier != null ? carrier.getDouble("fall-speed", 2.0) : 2.0;
        this.carrierSplitAltitude = carrier != null ? carrier.getDouble("split-altitude", 15.0) : 15.0;
        String displayName = carrier != null ? carrier.getString("display-block", "TNT") : "TNT";
        Material display = Material.matchMaterial(displayName != null ? displayName : "TNT");
        this.carrierDisplayBlock = display != null ? display : Material.TNT;

        String patternName = section.getString("pattern", "ring").toUpperCase(Locale.ROOT);
        Pattern p;
        try {
            p = Pattern.valueOf(patternName);
        } catch (IllegalArgumentException ex) {
            logger.warning("[OrbitalStrike] Unknown nuke pattern '" + patternName + "', using RING");
            p = Pattern.RING;
        }
        this.pattern = p;
        this.patternRadius = Math.max(0, section.getDouble("pattern-radius", 5.0));
        this.patternSpacing = Math.max(0.5, section.getDouble("pattern-spacing", 2.0));
        this.unitCount = Math.max(1, section.getInt("unit-count", 6));

        this.fuseTicks = Math.max(1, section.getLong("fuse-ticks", 40));
        this.yieldMultiplier = Math.max(0.01, section.getDouble("yield-multiplier", 3.0));
        this.breakBlocks = section.getBoolean("break-blocks", true);
        this.setFire = section.getBoolean("set-fire", false);
    }

    public double carrierSpawnHeightOffset() { return carrierSpawnHeightOffset; }
    public double carrierFallSpeed() { return carrierFallSpeed; }
    public double carrierSplitAltitude() { return carrierSplitAltitude; }
    public Material carrierDisplayBlock() { return carrierDisplayBlock; }

    public Pattern pattern() { return pattern; }
    public double patternRadius() { return patternRadius; }
    public double patternSpacing() { return patternSpacing; }
    public int unitCount() { return unitCount; }

    public long fuseTicks() { return fuseTicks; }
    public double yieldMultiplier() { return yieldMultiplier; }
    public boolean breakBlocks() { return breakBlocks; }
    public boolean setFire() { return setFire; }
}
