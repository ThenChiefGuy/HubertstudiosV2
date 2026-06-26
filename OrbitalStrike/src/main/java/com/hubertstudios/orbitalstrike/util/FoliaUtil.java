package com.hubertstudios.orbitalstrike.util;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.function.Consumer;

/**
 * Thin wrapper around Paper/Folia's region-aware schedulers.
 *
 * Folia splits the world into independently-ticked regions; a plain
 * BukkitScheduler delayed task is not guaranteed to run on the correct
 * region's thread (and on Folia, the legacy scheduler API throws). Paper's
 * RegionScheduler / EntityScheduler abstractions work correctly on BOTH
 * Folia and regular Paper, so routing everything through this class is what
 * makes the plugin Folia-safe without any reflection or version-sniffing.
 */
public final class FoliaUtil {

    private FoliaUtil() {
    }

    /** Runs a task after {@code delayTicks}, scheduled on the region that owns the location. */
    public static void runAtLocationLater(Plugin plugin, Location location, long delayTicks, Runnable task) {
        long delay = Math.max(1, delayTicks);
        plugin.getServer().getRegionScheduler().runDelayed(plugin, location, scheduledTask -> task.run(), delay);
    }

    /** Runs a task now, scheduled on the region that owns the location. */
    public static void runAtLocation(Plugin plugin, Location location, Runnable task) {
        plugin.getServer().getRegionScheduler().run(plugin, location, scheduledTask -> task.run());
    }

    /**
     * Runs a repeating task tied to a location's region, invoking {@code task} every
     * {@code periodTicks} starting after {@code delayTicks}. The consumer receives a
     * cancel callback to stop the repetition (used for things like raycast marker
     * refresh loops bound to an aiming session).
     */
    public static CancellableTask runAtLocationTimer(Plugin plugin, Location location, long delayTicks,
                                                       long periodTicks, Consumer<CancellableTask> task) {
        CancellableTask holder = new CancellableTask();
        holder.scheduledTask = plugin.getServer().getRegionScheduler().runAtFixedRate(
                plugin, location, st -> {
                    if (holder.cancelled) {
                        st.cancel();
                        return;
                    }
                    task.accept(holder);
                }, Math.max(1, delayTicks), Math.max(1, periodTicks));
        return holder;
    }

    /** Runs a task tied to a specific entity (follows the entity across regions on Folia). */
    public static void runOnEntityLater(Plugin plugin, Entity entity, long delayTicks, Runnable task, Runnable retired) {
        entity.getScheduler().runDelayed(plugin, scheduledTask -> task.run(), retired, Math.max(1, delayTicks));
    }

    /** Repeating task tied to an entity; stops automatically if the entity is removed. */
    public static CancellableTask runOnEntityTimer(Plugin plugin, Entity entity, long delayTicks,
                                                     long periodTicks, Consumer<CancellableTask> task) {
        CancellableTask holder = new CancellableTask();
        holder.scheduledTask = entity.getScheduler().runAtFixedRate(
                plugin, st -> {
                    if (holder.cancelled) {
                        st.cancel();
                        return;
                    }
                    task.accept(holder);
                }, null, Math.max(1, delayTicks), Math.max(1, periodTicks));
        return holder;
    }

    /** Runs a task on the global region scheduler (server-wide, not tied to any location). */
    public static void runGlobalLater(Plugin plugin, long delayTicks, Runnable task) {
        plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, scheduledTask -> task.run(), Math.max(1, delayTicks));
    }

    /** Handle allowing a repeating region/entity task to be cancelled from outside. */
    public static final class CancellableTask {
        private volatile boolean cancelled = false;
        private Object scheduledTask;

        public void cancel() {
            this.cancelled = true;
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }
}
