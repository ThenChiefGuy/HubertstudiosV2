package com.hubertstudios.orbitalstrike.strikes;

import com.hubertstudios.orbitalstrike.config.NukeConfig;
import com.hubertstudios.orbitalstrike.util.FoliaUtil;
import com.hubertstudios.orbitalstrike.util.GeometryUtil;
import com.hubertstudios.orbitalstrike.util.TextUtil;
import org.bukkit.Location;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.List;

/**
 * NUKE strike: a single carrier (rendered as a BlockDisplay, not a real
 * falling-block entity, so it costs nothing physics-wise) descends toward
 * the target. Once within {@code splitAltitude} blocks of the target, it is
 * replaced by {@code unitCount} primed TNT entities arranged in the
 * configured pattern (ring/grid/random/line), each with its yield boosted
 * by {@code yieldMultiplier} via the explosion API's power parameter.
 */
public final class NukeStrike implements StrikeExecutor {

    private final Plugin plugin;
    private final NukeConfig config;

    public NukeStrike(Plugin plugin, NukeConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    @Override
    public void execute(Location target, Player source, boolean isBomb) {
        runSequence(target, source, isBomb, 0);
    }

    private void runSequence(Location target, Player source, boolean isBomb, int iteration) {
        long delay = isBomb ? 1 : config.fireDelayTicks();

        FoliaUtil.runAtLocationLater(plugin, target, delay, () -> {
            if (!isBomb && config.warning().enabled() && source.isOnline()) {
                source.sendMessage(TextUtil.colorize(config.warning().message()));
            }
            dropCarrier(target.clone(), source);

            if (iteration + 1 < config.repeatCount()) {
                long interval = isBomb ? config.bombIntervalTicks() : config.repeatIntervalTicks();
                FoliaUtil.runAtLocationLater(plugin, target, interval,
                        () -> runSequence(target, source, isBomb, iteration + 1));
            }
        });
    }

    private void dropCarrier(Location target, Player source) {
        Location spawnLoc = target.clone().add(0, config.carrierSpawnHeightOffset(), 0);

        BlockDisplay carrier = spawnLoc.getWorld().spawn(spawnLoc, BlockDisplay.class, display -> {
            display.setBlock(config.carrierDisplayBlock().createBlockData());
            display.setPersistent(false);
            display.setGlowing(true);
        });

        FoliaUtil.CancellableTask fallTask = FoliaUtil.runAtLocationTimer(plugin, target, 1, 1, handle -> {
            if (!carrier.isValid()) {
                handle.cancel();
                return;
            }
            Location current = carrier.getLocation();
            double remaining = current.getY() - target.getY();
            if (remaining <= config.carrierSplitAltitude()) {
                handle.cancel();
                Location splitPoint = current.clone();
                carrier.remove();
                splitIntoTnt(splitPoint, target);
                return;
            }
            current.add(0, -config.carrierFallSpeed(), 0);
            carrier.teleportAsync(current);
        });
    }

    private void splitIntoTnt(Location splitPoint, Location target) {
        List<Vector> offsets = switch (config.pattern()) {
            case RING -> GeometryUtil.ring(config.unitCount(), config.patternRadius());
            case GRID -> GeometryUtil.grid(config.unitCount(), config.patternSpacing());
            case RANDOM -> GeometryUtil.random(config.unitCount(), config.patternRadius());
            case LINE -> GeometryUtil.line(config.unitCount(), config.patternSpacing());
        };

        for (Vector offset : offsets) {
            Location tntSpawnLoc = splitPoint.clone().add(offset.getX(), 0, offset.getZ());
            Location groundTarget = target.clone().add(offset.getX(), 0, offset.getZ());

            tntSpawnLoc.getWorld().spawn(tntSpawnLoc, TNTPrimed.class, tnt -> {
                tnt.setFuseTicks((int) config.fuseTicks());
                tnt.setYield((float) (4.0 * config.yieldMultiplier()));
                tnt.setSource(null);
                tnt.setMetadata(com.hubertstudios.orbitalstrike.listeners.ExplosionListener.METADATA_KEY,
                        new org.bukkit.metadata.FixedMetadataValue(plugin, true));
                Vector toGround = groundTarget.toVector().subtract(tntSpawnLoc.toVector());
                toGround.setY(Math.min(toGround.getY(), -0.1));
                tnt.setVelocity(toGround.normalize().multiply(0.3));
            });
        }
    }
}
