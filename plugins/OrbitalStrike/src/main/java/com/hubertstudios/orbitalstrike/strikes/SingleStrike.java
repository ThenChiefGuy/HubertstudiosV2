package com.hubertstudios.orbitalstrike.strikes;

import com.hubertstudios.orbitalstrike.config.SingleStrikeConfig;
import com.hubertstudios.orbitalstrike.util.FoliaUtil;
import com.hubertstudios.orbitalstrike.util.TextUtil;
import org.bukkit.Location;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.Collection;

/**
 * STRIKE type: a single concentrated impact at the target - no carrier
 * entity, no spread pattern. Applies direct damage to living entities in
 * radius, triggers a vanilla-equivalent explosion (block damage/fire
 * governed by config), and optionally renders a purely-visual beam (a
 * column of BlockDisplay entities, removed after duration-ticks) descending
 * from the sky to the target - no particles involved.
 */
public final class SingleStrike implements StrikeExecutor {

    private final Plugin plugin;
    private final SingleStrikeConfig config;

    public SingleStrike(Plugin plugin, SingleStrikeConfig config) {
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
            if (config.beamEnabled()) {
                renderBeam(target.clone());
            }
            impact(target.clone());

            if (iteration + 1 < config.repeatCount()) {
                long interval = isBomb ? config.bombIntervalTicks() : config.repeatIntervalTicks();
                FoliaUtil.runAtLocationLater(plugin, target, interval,
                        () -> runSequence(target, source, isBomb, iteration + 1));
            }
        });
    }

    private void renderBeam(Location target) {
        Location base = target.clone();
        int segments = Math.max(1, (int) (config.beamHeight() / 2.0));
        for (int i = 0; i < segments; i++) {
            Location segLoc = base.clone().add(0, i * 2.0, 0);
            BlockDisplay segment = segLoc.getWorld().spawn(segLoc, BlockDisplay.class, display -> {
                display.setBlock(config.beamBlock().createBlockData());
                display.setPersistent(false);
                display.setGlowing(true);
                Transformation t = new Transformation(
                        new Vector3f(0f, 0f, 0f),
                        new AxisAngle4f(0f, 0f, 1f, 0f),
                        new Vector3f(0.3f, 2.0f, 0.3f),
                        new AxisAngle4f(0f, 0f, 1f, 0f)
                );
                display.setTransformation(t);
            });
            FoliaUtil.runAtLocationLater(plugin, segLoc, config.beamDurationTicks(), () -> {
                if (segment.isValid()) segment.remove();
            });
        }
    }

    private void impact(Location target) {
        Collection<org.bukkit.entity.Entity> nearby = target.getWorld().getNearbyEntities(target, config.radius(), config.radius(), config.radius());
        for (org.bukkit.entity.Entity e : nearby) {
            if (e instanceof LivingEntity living) {
                double distance = living.getLocation().distance(target);
                if (distance <= config.radius()) {
                    living.damage(config.damage());
                }
            }
        }
        target.getWorld().createExplosion(target, (float) config.explosionPower(), config.setFire(), config.breakBlocks());
    }
}
