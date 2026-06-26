package com.hubertstudios.orbitalstrike.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;
import java.util.logging.Logger;

/**
 * Per-strike-type rod appearance. Each strike type owns one of these
 * independently (no shared/global rod definition), per the confirmed design.
 */
public final class RodConfig {

    private final Material material;
    private final String customName;
    private final boolean loreEnabled;
    private final List<String> lore;
    private final boolean glow;

    public RodConfig(ConfigurationSection section, Logger logger) {
        String matName = section.getString("material", "FISHING_ROD");
        Material mat = Material.matchMaterial(matName);
        if (mat == null) {
            logger.warning("[OrbitalStrike] Unknown rod material '" + matName + "', using FISHING_ROD");
            mat = Material.FISHING_ROD;
        }
        this.material = mat;
        this.customName = section.getString("custom-name", "&fOrbital Targeting System");
        this.loreEnabled = section.getBoolean("lore-enabled", true);
        this.lore = section.getStringList("lore");
        this.glow = section.getBoolean("glow", true);
    }

    public Material material() {
        return material;
    }

    public String customName() {
        return customName;
    }

    public boolean loreEnabled() {
        return loreEnabled;
    }

    public List<String> lore() {
        return lore;
    }

    public boolean glow() {
        return glow;
    }
}
