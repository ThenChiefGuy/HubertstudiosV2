package com.HubertStudios.coreguard.listeners;

import com.HubertStudios.coreguard.CoreGuard;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerConnectionListener implements Listener {
    private final CoreGuard plugin;

    public PlayerConnectionListener(CoreGuard plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        var player = event.getPlayer();
        if (plugin.vanishManager().isVanished(player)) event.setQuitMessage(null);
        plugin.staffModeManager().cleanupOnQuit(player);
        if (plugin.getConfig().getBoolean("inventory-backups.auto-backup-on-disconnect", true)) {
            plugin.inventoryBackupManager().backup(player, "disconnect");
        }
        plugin.vanishManager().handleQuit(player);
        plugin.guiSessionManager().closeSessionsFor(player.getUniqueId());
        plugin.spyManager().clearAll(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (plugin.getConfig().getBoolean("inventory-backups.auto-backup-on-death", true)) {
            plugin.inventoryBackupManager().backup(event.getEntity(), "death");
        }
    }
}
