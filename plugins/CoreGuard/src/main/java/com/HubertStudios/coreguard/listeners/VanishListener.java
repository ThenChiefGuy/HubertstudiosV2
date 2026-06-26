package com.HubertStudios.coreguard.listeners;

import com.HubertStudios.coreguard.CoreGuard;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;

public class VanishListener implements Listener {
    private final CoreGuard plugin;

    public VanishListener(CoreGuard plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (plugin.getConfig().getBoolean("vanish.prevent-item-pickup", true) && plugin.vanishManager().isVanished(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMobTarget(EntityTargetEvent event) {
        if (!(event.getTarget() instanceof Player player)) return;
        if (plugin.getConfig().getBoolean("vanish.prevent-mob-targeting", true) && plugin.vanishManager().isVanished(player)) {
            event.setCancelled(true);
        }
    }
}
