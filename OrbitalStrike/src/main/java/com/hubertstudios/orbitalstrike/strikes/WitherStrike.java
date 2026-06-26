package com.hubertstudios.orbitalstrike.strikes;

import com.hubertstudios.orbitalstrike.config.WitherConfig;
import com.hubertstudios.orbitalstrike.util.FoliaUtil;
import com.hubertstudios.orbitalstrike.util.GeometryUtil;
import com.hubertstudios.orbitalstrike.util.TextUtil;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkull;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * WITHER strike: a single carrier WitherSkull is fired toward the target.
 * Once it is within {@code splitDistance} of the target (checked on a
 * lightweight per-tick follow task, not a particle loop), it is removed and
 * replaced by a dome of skulls positioned on an even grid projected onto a
 * half-sphere - giving full, gapless, non-random coverage of the target
 * circle, matching the corrected real-machine behavior. The dome holds
 * briefly, then every skull in it launches simultaneously toward its own
 * point on the ground (with a small per-skull velocity jitter only - the
 * grid positions themselves stay deterministic/even, only the resulting
 * impact gets slight natural-looking scatter from velocity, not position).
 *
 * Each skull independently rolls blue (charged) vs black (normal) by the
 * configured percentage.
 */
public final class WitherStrike implements StrikeExecutor {

    private final Plugin plugin;
    private final WitherConfig config;

    public WitherStrike(Plugin plugin, WitherConfig config) {
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
            launchCarrier(target.clone(), source);

            if (iteration + 1 < config.repeatCount()) {
                long interval = isBomb ? config.bombIntervalTicks() : config.repeatIntervalTicks();
                FoliaUtil.runAtLocationLater(plugin, target, interval,
                        () -> runSequence(target, source, isBomb, iteration + 1));
            }
        });
    }

    private void launchCarrier(Location target, Player source) {
        Location spawnLoc = target.clone().add(0, config.carrierSpawnHeightOffset(), 0);
        Vector toTarget = target.toVector().subtract(spawnLoc.toVector());
        double totalDistance = toTarget.length();
        Vector direction = toTarget.clone().normalize();

        WitherSkull carrier = spawnLoc.getWorld().spawn(spawnLoc, WitherSkull.class, skull -> {
            skull.setCharged(false);
            skull.setVelocity(direction.clone().multiply(config.carrierFlySpeed()));
            skull.setShooter(null);
        });

        // Follow the carrier each tick to detect when it has closed within
        // split-distance of the target, then split it. This uses an
        // entity-bound repeating task (Folia-safe) rather than a global loop.
        FoliaUtil.runOnEntityTimer(plugin, carrier, 1, 1, handle -> {
            if (!carrier.isValid()) {
                handle.cancel();
                return;
            }
            double remaining = carrier.getLocation().distance(target);
            if (remaining <= config.carrierSplitDistance() || carrier.isOnGround()) {
                handle.cancel();
                Location splitPoint = carrier.getLocation().clone();
                carrier.remove();
                formDomeAndLaunch(splitPoint, target, source);
            }
        });

        // Safety timeout: if something prevents the proximity check from ever
        // triggering (e.g. the skull is stopped by a block far from target),
        // force the split after a generous timeout proportional to distance.
        long timeoutTicks = Math.max(20, (long) (totalDistance / Math.max(0.1, config.carrierFlySpeed())) + 40);
        FoliaUtil.runAtLocationLater(plugin, target, timeoutTicks, () -> {
            if (carrier.isValid()) {
                Location splitPoint = carrier.getLocation().clone();
                carrier.remove();
                formDomeAndLaunch(splitPoint, target, source);
            }
        });
    }

    private void formDomeAndLaunch(Location splitPoint, Location target, Player source) {
        List<Vector> flatGrid = GeometryUtil.evenDiscGrid(config.domeGridRows(), config.domeGridColumns(), config.domeRadius());
        List<Vector> domePoints = GeometryUtil.projectToDome(flatGrid, config.domeRadius(), config.domeHeight());

        Location domeCenter = target.clone().add(0, config.domeHeight(), 0);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        for (Vector offset : domePoints) {
            Location skullSpawnLoc = domeCenter.clone().add(offset.getX(), offset.getY(), offset.getZ());
            boolean blue = rnd.nextInt(100) < config.bluePercentage();

            WitherSkull skull = skullSpawnLoc.getWorld().spawn(skullSpawnLoc, WitherSkull.class, ws -> {
                ws.setCharged(blue);
                ws.setShooter(null);
                ws.setVelocity(new Vector(0, 0, 0));
                if (config.fireOnImpact()) {
                    ws.setFireTicks(1);
                }
            });

            // Hold in place, then launch toward a slightly-jittered ground impact point.
            FoliaUtil.runOnEntityLater(plugin, skull, config.domeHoldTicks(), () -> {
                if (!skull.isValid()) return;
                Vector scatter = GeometryUtil.randomInDisc(config.domeRadius() * 0.15);
                Location impactPoint = target.clone().add(scatter.getX(), 0, scatter.getZ());
                Vector toImpact = impactPoint.toVector().subtract(skull.getLocation().toVector()).normalize();
                skull.setVelocity(toImpact.multiply(config.domeLaunchSpeed()));
            }, () -> { /* skull removed before hold elapsed; nothing to do */ });
        }
    }
}
