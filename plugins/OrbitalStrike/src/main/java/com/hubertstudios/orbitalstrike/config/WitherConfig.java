package com.hubertstudios.orbitalstrike.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.logging.Logger;

/**
 * WITHER strike: a single carrier wither skull flies toward the raycast
 * target. Once within {@code splitDistance} blocks of the target, it splits
 * into a half-sphere dome of skulls arranged in an even grid (not random) so
 * the circular area is fully, evenly covered. All dome skulls then launch
 * together toward the ground below them, each independently rolled as
 * blue (charged) or black (normal) by percentage.
 */
public final class WitherConfig extends BaseStrikeConfig {

    private final double carrierSpawnHeightOffset;
    private final double carrierFlySpeed;
    private final double carrierSplitDistance;

    private final int domeGridRows;
    private final int domeGridColumns;
    private final double domeRadius;
    private final double domeHeight;
    private final long domeHoldTicks;
    private final double domeLaunchSpeed;
    private final int bluePercentage;
    private final double blueExplosionPowerOverride;
    private final double blackExplosionPowerOverride;
    private final boolean fireOnImpact;

    public WitherConfig(ConfigurationSection section, Logger logger) {
        super(section, logger, 30);

        ConfigurationSection carrier = section.getConfigurationSection("carrier");
        this.carrierSpawnHeightOffset = carrier != null ? carrier.getDouble("spawn-height-offset", 50.0) : 50.0;
        this.carrierFlySpeed = carrier != null ? carrier.getDouble("fly-speed", 1.6) : 1.6;
        this.carrierSplitDistance = carrier != null ? carrier.getDouble("split-distance", 15.0) : 15.0;

        ConfigurationSection dome = section.getConfigurationSection("dome");
        this.domeGridRows = dome != null ? Math.max(1, dome.getInt("grid-rows", 6)) : 6;
        this.domeGridColumns = dome != null ? Math.max(1, dome.getInt("grid-columns", 6)) : 6;
        this.domeRadius = dome != null ? Math.max(0.5, dome.getDouble("radius", 6.0)) : 6.0;
        this.domeHeight = dome != null ? Math.max(0, dome.getDouble("dome-height", 4.0)) : 4.0;
        this.domeHoldTicks = dome != null ? Math.max(0, dome.getLong("hold-ticks", 15)) : 15;
        this.domeLaunchSpeed = dome != null ? dome.getDouble("launch-speed", 1.3) : 1.3;
        this.bluePercentage = dome != null ? clampPercentage(dome.getInt("blue-percentage", 35)) : 35;
        this.blueExplosionPowerOverride = dome != null ? dome.getDouble("blue-explosion-power-override", -1.0) : -1.0;
        this.blackExplosionPowerOverride = dome != null ? dome.getDouble("black-explosion-power-override", -1.0) : -1.0;
        this.fireOnImpact = dome != null && dome.getBoolean("fire-on-impact", false);
    }

    private static int clampPercentage(int value) {
        return Math.max(0, Math.min(100, value));
    }

    public double carrierSpawnHeightOffset() { return carrierSpawnHeightOffset; }
    public double carrierFlySpeed() { return carrierFlySpeed; }
    public double carrierSplitDistance() { return carrierSplitDistance; }

    public int domeGridRows() { return domeGridRows; }
    public int domeGridColumns() { return domeGridColumns; }
    public double domeRadius() { return domeRadius; }
    public double domeHeight() { return domeHeight; }
    public long domeHoldTicks() { return domeHoldTicks; }
    public double domeLaunchSpeed() { return domeLaunchSpeed; }
    public int bluePercentage() { return bluePercentage; }
    public double blueExplosionPowerOverride() { return blueExplosionPowerOverride; }
    public double blackExplosionPowerOverride() { return blackExplosionPowerOverride; }
    public boolean fireOnImpact() { return fireOnImpact; }
}
