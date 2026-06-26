package com.hubertstudios.orbitalstrike.strikes;

import com.hubertstudios.orbitalstrike.config.DogConfig;
import com.hubertstudios.orbitalstrike.util.FoliaUtil;
import com.hubertstudios.orbitalstrike.util.GeometryUtil;
import com.hubertstudios.orbitalstrike.util.TextUtil;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wolf;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;

/**
 * DOG strike: ignores the raycast target entirely and spawns wolves around
 * the source player, per the confirmed design (no raycast needed for this
 * type - "the wolves should spawn around the player").
 *
 * Wolves spawn tamed (owner = source), NOT sitting, and with standard
 * vanilla neutral AI (only become hostile if attacked) - no forced anger.
 */
public final class DogStrike implements StrikeExecutor {

    private final Plugin plugin;
    private final DogConfig config;

    public DogStrike(Plugin plugin, DogConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    @Override
    public void execute(Location target, Player source, boolean isBomb) {
        // dog always centers on the source player's current location, per design.
        runSequence(source, 0);
    }

    private void runSequence(Player source, int iteration) {
        if (!source.isOnline()) return;

        Location center = source.getLocation();
        FoliaUtil.runAtLocationLater(plugin, center, config.fireDelayTicks(), () -> {
            if (!source.isOnline()) return;
            spawnWolfBatch(source);

            if (iteration + 1 < config.repeatCount()) {
                FoliaUtil.runAtLocationLater(plugin, source.getLocation(), config.repeatIntervalTicks(),
                        () -> runSequence(source, iteration + 1));
            }
        });
    }

    private void spawnWolfBatch(Player source) {
        Location origin = source.getLocation();
        if (config.warning().enabled()) {
            source.sendMessage(TextUtil.colorize(config.warning().message()));
        }

        for (int i = 0; i < config.wolfCount(); i++) {
            Vector offset = GeometryUtil.randomInAnnulus(config.minRadius(), config.maxRadius());
            Location spawnLoc = origin.clone().add(offset.getX(), 0, offset.getZ());
            spawnLoc.setY(findSafeY(spawnLoc));

            origin.getWorld().spawn(spawnLoc, Wolf.class, wolf -> {
                if (config.adult()) {
                    wolf.setAdult();
                } else {
                    wolf.setBaby();
                }
                wolf.setVariant(config.variant());
                wolf.setTamed(config.tamed());
                if (config.tamed()) {
                    wolf.setOwner(source);
                }
                wolf.setSitting(config.sitting());
                if (config.angryOnSpawn()) {
                    wolf.setAngry(true);
                }
                if (config.lifetimeSeconds() > 0) {
                    wolf.setRemoveWhenFarAway(false);
                }

                if (config.equipmentEnabled() && config.bodyArmor() != null) {
                    EntityEquipment eq = wolf.getEquipment();
                    if (eq != null) {
                        eq.setItem(org.bukkit.inventory.EquipmentSlot.BODY, new ItemStack(config.bodyArmor()));
                    }
                }

                for (PotionEffect effect : config.effects()) {
                    wolf.addPotionEffect(effect);
                }

                if (config.lifetimeSeconds() > 0) {
                    FoliaUtil.runOnEntityLater(plugin, wolf, config.lifetimeSeconds() * 20L,
                            () -> {
                                if (wolf.isValid()) wolf.remove();
                            },
                            () -> { /* entity retired before timer fired; nothing to clean up */ });
                }
            });
        }
    }

    /** Walks up/down from the candidate Y to find solid ground, avoiding spawning wolves mid-air or inside blocks. */
    private double findSafeY(Location candidate) {
        Location probe = candidate.clone();
        int baseY = probe.getBlockY();
        for (int dy = 0; dy <= 5; dy++) {
            int y = baseY - dy;
            probe.setY(y);
            if (probe.getBlock().getType().isSolid() && probe.clone().add(0, 1, 0).getBlock().getType().isAir()) {
                return y + 1;
            }
        }
        return candidate.getY();
    }
}
