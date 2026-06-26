package com.hubertstudios.orbitalstrike.config;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Wolf;
import org.bukkit.potion.PotionEffect;

import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * DOG strike: no raycast target. Spawns tamed, non-sitting, neutral wolves
 * directly around the caster (or bomb target - except dog explicitly
 * disallows /orbital bomb regardless of config, enforced in the command layer
 * as well as here via allowBomb() always being forced false for safety).
 */
public final class DogConfig extends BaseStrikeConfig {

    private final int wolfCount;
    private final double minRadius;
    private final double maxRadius;
    private final long lifetimeSeconds;
    private final boolean adult;
    private final Wolf.Variant variant;
    private final boolean sitting;
    private final boolean tamed;
    private final boolean angryOnSpawn;
    private final boolean equipmentEnabled;
    private final Material bodyArmor;
    private final List<PotionEffect> effects;

    public DogConfig(ConfigurationSection section, Logger logger) {
        super(section, logger, 0);
        this.wolfCount = Math.max(1, section.getInt("wolf-count", 6));
        this.minRadius = Math.max(0, section.getDouble("min-radius", 5.0));
        this.maxRadius = Math.max(minRadius, section.getDouble("max-radius", 7.0));
        this.lifetimeSeconds = Math.max(0, section.getLong("lifetime-seconds", 60));
        this.adult = section.getBoolean("adult", true);
        String variantName = section.getString("variant", "PALE");
        Wolf.Variant v = Registry.WOLF_VARIANT.get(NamespacedKey.minecraft(variantName.toLowerCase(Locale.ROOT)));
        if (v == null) {
            logger.warning("[OrbitalStrike] Unknown wolf variant '" + variantName + "', using PALE");
            v = Wolf.Variant.PALE;
        }
        this.variant = v;
        this.sitting = section.getBoolean("sitting", false);
        this.tamed = section.getBoolean("tamed", true);
        this.angryOnSpawn = section.getBoolean("angry-on-spawn", false);
        this.equipmentEnabled = section.getBoolean("equipment.enabled", false);
        String armorName = section.getString("equipment.body-armor", "NONE");
        Material armor = null;
        if (armorName != null && !armorName.equalsIgnoreCase("NONE")) {
            armor = Material.matchMaterial(armorName);
            if (armor == null) {
                logger.warning("[OrbitalStrike] Unknown wolf armor material '" + armorName + "'");
            }
        }
        this.bodyArmor = armor;
        this.effects = parsePotionEffects(section, logger);
    }

    /** Dog strikes can never be used with /orbital bomb, regardless of any config value. */
    @Override
    public boolean allowBomb() {
        return false;
    }

    public int wolfCount() { return wolfCount; }
    public double minRadius() { return minRadius; }
    public double maxRadius() { return maxRadius; }
    public long lifetimeSeconds() { return lifetimeSeconds; }
    public boolean adult() { return adult; }
    public Wolf.Variant variant() { return variant; }
    public boolean sitting() { return sitting; }
    public boolean tamed() { return tamed; }
    public boolean angryOnSpawn() { return angryOnSpawn; }
    public boolean equipmentEnabled() { return equipmentEnabled; }
    public Material bodyArmor() { return bodyArmor; }
    public List<PotionEffect> effects() { return effects; }
}
