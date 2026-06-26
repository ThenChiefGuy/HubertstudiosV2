package com.hubertstudios.orbitalstrike.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.logging.Logger;

/**
 * STRIKE type: a single concentrated impact at the raycast target - no
 * carrier entity, no spread pattern. Direct damage + explosion + an optional
 * purely-visual beam (a column of BlockDisplay entities, no particles).
 */
public final class SingleStrikeConfig extends BaseStrikeConfig {

    private final double radius;
    private final double damage;
    private final double explosionPower;
    private final boolean breakBlocks;
    private final boolean setFire;

    private final boolean beamEnabled;
    private final double beamHeight;
    private final Material beamBlock;
    private final long beamDurationTicks;

    public SingleStrikeConfig(ConfigurationSection section, Logger logger) {
        super(section, logger, 15);

        this.radius = Math.max(0.5, section.getDouble("radius", 4.0));
        this.damage = Math.max(0, section.getDouble("damage", 40.0));
        this.explosionPower = Math.max(0, section.getDouble("explosion-power", 6.0));
        this.breakBlocks = section.getBoolean("break-blocks", true);
        this.setFire = section.getBoolean("set-fire", true);

        ConfigurationSection beam = section.getConfigurationSection("beam");
        this.beamEnabled = beam != null && beam.getBoolean("enabled", true);
        this.beamHeight = beam != null ? beam.getDouble("height", 40.0) : 40.0;
        String blockName = beam != null ? beam.getString("block", "BEACON") : "BEACON";
        Material block = Material.matchMaterial(blockName != null ? blockName : "BEACON");
        this.beamBlock = block != null ? block : Material.BEACON;
        this.beamDurationTicks = beam != null ? Math.max(1, beam.getLong("duration-ticks", 10)) : 10;
    }

    public double radius() { return radius; }
    public double damage() { return damage; }
    public double explosionPower() { return explosionPower; }
    public boolean breakBlocks() { return breakBlocks; }
    public boolean setFire() { return setFire; }

    public boolean beamEnabled() { return beamEnabled; }
    public double beamHeight() { return beamHeight; }
    public Material beamBlock() { return beamBlock; }
    public long beamDurationTicks() { return beamDurationTicks; }
}
