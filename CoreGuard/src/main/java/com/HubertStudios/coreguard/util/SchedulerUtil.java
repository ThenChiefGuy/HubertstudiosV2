package com.HubertStudios.coreguard.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * Cross-runtime scheduler bridge for Bukkit/Paper/Folia.
 *
 * Uses Folia's global/entity/region schedulers when they exist, and falls back
 * to the classic Bukkit scheduler on regular Bukkit/Paper servers. Reflection
 * keeps this class source-compatible with Bukkit-style APIs while still using
 * Folia correctly at runtime.
 */
public final class SchedulerUtil {
    private SchedulerUtil() {
    }

    public static void runGlobal(Plugin plugin, Runnable task) {
        if (tryGlobal(plugin, task, 0L)) return;
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (Throwable ignored) {
            task.run();
        }
    }

    public static void runGlobalLater(Plugin plugin, long delayTicks, Runnable task) {
        if (tryGlobal(plugin, task, Math.max(1L, delayTicks))) return;
        try {
            Bukkit.getScheduler().runTaskLater(plugin, task, Math.max(1L, delayTicks));
        } catch (Throwable ignored) {
            task.run();
        }
    }

    public static void runEntity(Plugin plugin, Entity entity, Runnable task) {
        if (entity != null && tryEntity(plugin, entity, task, 0L)) return;
        runGlobal(plugin, task);
    }

    public static void runEntityLater(Plugin plugin, Entity entity, long delayTicks, Runnable task) {
        if (entity != null && tryEntity(plugin, entity, task, Math.max(1L, delayTicks))) return;
        runGlobalLater(plugin, delayTicks, task);
    }

    public static void runAtLocation(Plugin plugin, Location location, Runnable task) {
        if (location != null && tryRegion(plugin, location, task, 0L)) return;
        runGlobal(plugin, task);
    }

    public static void runAtLocationLater(Plugin plugin, Location location, long delayTicks, Runnable task) {
        if (location != null && tryRegion(plugin, location, task, Math.max(1L, delayTicks))) return;
        runGlobalLater(plugin, delayTicks, task);
    }

    private static boolean tryGlobal(Plugin plugin, Runnable task, long delayTicks) {
        try {
            Object scheduler = Bukkit.getServer().getClass().getMethod("getGlobalRegionScheduler").invoke(Bukkit.getServer());
            if (delayTicks > 0) {
                Method runDelayed = scheduler.getClass().getMethod("runDelayed", Plugin.class, Consumer.class, long.class);
                runDelayed.invoke(scheduler, plugin, (Consumer<Object>) ignored -> task.run(), delayTicks);
            } else {
                Method run = scheduler.getClass().getMethod("run", Plugin.class, Consumer.class);
                run.invoke(scheduler, plugin, (Consumer<Object>) ignored -> task.run());
            }
            return true;
        } catch (ReflectiveOperationException ignored) {
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean tryEntity(Plugin plugin, Entity entity, Runnable task, long delayTicks) {
        try {
            Object scheduler = entity.getClass().getMethod("getScheduler").invoke(entity);
            if (delayTicks > 0) {
                Method runDelayed = scheduler.getClass().getMethod("runDelayed", Plugin.class, Consumer.class, Runnable.class, long.class);
                runDelayed.invoke(scheduler, plugin, (Consumer<Object>) ignored -> task.run(), null, delayTicks);
            } else {
                Method run = scheduler.getClass().getMethod("run", Plugin.class, Consumer.class, Runnable.class);
                run.invoke(scheduler, plugin, (Consumer<Object>) ignored -> task.run(), null);
            }
            return true;
        } catch (ReflectiveOperationException ignored) {
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean tryRegion(Plugin plugin, Location location, Runnable task, long delayTicks) {
        try {
            Object scheduler = Bukkit.getServer().getClass().getMethod("getRegionScheduler").invoke(Bukkit.getServer());
            if (delayTicks > 0) {
                Method runDelayed = scheduler.getClass().getMethod("runDelayed", Plugin.class, Location.class, Consumer.class, long.class);
                runDelayed.invoke(scheduler, plugin, location, (Consumer<Object>) ignored -> task.run(), delayTicks);
            } else {
                Method run = scheduler.getClass().getMethod("run", Plugin.class, Location.class, Consumer.class);
                run.invoke(scheduler, plugin, location, (Consumer<Object>) ignored -> task.run());
            }
            return true;
        } catch (ReflectiveOperationException ignored) {
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
