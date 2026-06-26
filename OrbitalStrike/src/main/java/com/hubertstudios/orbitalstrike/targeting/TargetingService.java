package com.hubertstudios.orbitalstrike.targeting;

import com.hubertstudios.orbitalstrike.config.PluginConfig;
import org.bukkit.Location;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * Performs the raycast used to resolve where a player is aiming, and renders
 * an optional visual marker at the resolved point. The marker is a
 * BlockDisplay entity (a real, lightweight client-rendered entity with no
 * per-tick particle cost) rather than particles, per the performance
 * requirement of using real game objects instead of particle spam.
 *
 * One raycast call per invocation; this class does NOT run its own tick
 * loop - callers (the FishingRodListener / live-aim updater) decide when to
 * re-resolve, keeping this class free of any scheduling concerns.
 */
public final class TargetingService {

    private final PluginConfig config;

    public TargetingService(PluginConfig config) {
        this.config = config;
    }

    /**
     * Resolves the block location the player is currently looking at, up to
     * the configured max distance. Returns null if nothing was hit and
     * fallback-on-miss is "cancel"; otherwise returns a point at max range
     * along the player's look vector when fallback-on-miss is "max-range".
     */
    public Location resolveTarget(Player player) {
        double maxDistance = config.maxTargetDistance();
        RayTraceResult result = player.getWorld().rayTraceBlocks(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                maxDistance,
                org.bukkit.FluidCollisionMode.NEVER,
                true
        );
        if (result != null && result.getHitPosition() != null) {
            return result.getHitPosition().toLocation(player.getWorld());
        }
        if (config.fallbackMaxRange()) {
            return player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(maxDistance));
        }
        return null;
    }

    /** Spawns (or returns null if marker-type is "none") a BlockDisplay marker at the given point. */
    public BlockDisplay spawnMarker(Location at) {
        if ("none".equals(config.markerType())) {
            return null;
        }
        Location blockLoc = at.toBlockLocation();
        return at.getWorld().spawn(blockLoc, BlockDisplay.class, display -> {
            display.setBlock(config.markerBlock().createBlockData());
            display.setPersistent(false);
            display.setGlowing(true);
            Transformation t = new Transformation(
                    new Vector3f(0f, 0f, 0f),
                    new AxisAngle4f(0f, 0f, 1f, 0f),
                    new Vector3f(1.02f, 1.02f, 1.02f),
                    new AxisAngle4f(0f, 0f, 1f, 0f)
            );
            display.setTransformation(t);
        });
    }

    public void moveMarker(BlockDisplay marker, Location to) {
        if (marker == null || !marker.isValid()) return;
        marker.teleportAsync(to.toBlockLocation());
    }

    public void removeMarker(BlockDisplay marker) {
        if (marker != null && marker.isValid()) {
            marker.remove();
        }
    }
}
