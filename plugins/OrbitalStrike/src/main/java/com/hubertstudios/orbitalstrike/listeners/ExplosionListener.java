package com.hubertstudios.orbitalstrike.listeners;

import com.hubertstudios.orbitalstrike.config.PluginConfig;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;

/**
 * Applies the nuke strike's break-blocks/set-fire configuration to TNT
 * entities spawned by NukeStrike. TNTPrimed has no direct API to disable
 * block damage on detonation, so instead we tag our TNT with metadata at
 * spawn time and intercept EntityExplodeEvent here to clear the affected
 * block list when break-blocks is false (set-fire is handled by Bukkit's
 * own explosion fire mechanics, which we can't separately suppress per-TNT
 * without this same metadata-gated approach for consistency).
 */
public final class ExplosionListener implements Listener {

    public static final String METADATA_KEY = "orbitalstrike_nuke_unit";

    private final Plugin plugin;
    private final PluginConfig config;

    public ExplosionListener(Plugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof TNTPrimed tnt)) {
            return;
        }
        if (!tnt.hasMetadata(METADATA_KEY)) {
            return;
        }
        if (!config.nuke().breakBlocks()) {
            event.blockList().clear();
        } else if (config.nuke().setFire()) {
            // Vanilla TNTPrimed detonation never ignites blocks on its own (that's
            // governed by World#createExplosion's "incendiary" flag, which TNT's
            // own detonation always passes as false). To honor set-fire: true for
            // the nuke type, ignite the air block above each destroyed block.
            for (org.bukkit.block.Block block : event.blockList()) {
                org.bukkit.block.Block above = block.getRelative(org.bukkit.block.BlockFace.UP);
                if (above.getType().isAir()) {
                    above.setType(org.bukkit.Material.FIRE);
                }
            }
        }
    }
}
