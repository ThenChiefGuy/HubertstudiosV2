package com.HubertStudios.coreguard.listeners;

import com.HubertStudios.coreguard.CoreGuard;
import com.HubertStudios.coreguard.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleEnterEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PunishmentEnforcementListener implements Listener {
    private final CoreGuard plugin;
    private final Map<UUID, Long> lastMessage = new ConcurrentHashMap<>();

    public PunishmentEnforcementListener(CoreGuard plugin) {
        this.plugin = plugin;
    }

    /**
     * Earliest possible rejection point: runs before the connection is accepted, before
     * PlayerLoginEvent, and before the player ever appears in Bukkit.getOnlinePlayers().
     * This is the layer that actually matters against a client that suppresses or
     * ignores disconnect/kick packets — the server simply never finishes accepting the
     * connection, so there is no "online" session for such a client to keep alive.
     * Checks UUID, name, and IP together so changing only one of them does not bypass it.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        if (!plugin.punishmentManager().isBanned(event.getUniqueId(), event.getName(), event.getAddress())) return;
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                Text.color("&cYou are banned."));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(PlayerLoginEvent event) {
        if (!plugin.punishmentManager().isInImmediateLockdown(event.getPlayer())) return;
        event.disallow(PlayerLoginEvent.Result.KICK_BANNED, Text.color(plugin.punishmentManager().lockdownReason(event.getPlayer())));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (locked(event.getPlayer(), false)) event.setTo(event.getFrom());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (locked(event.getPlayer(), false)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (locked(event.getPlayer(), false)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (locked(event.getPlayer(), false)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && locked(player, false)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && locked(player, false)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (locked(event.getPlayer(), false)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (locked(event.getPlayer(), false)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player && locked(player, false)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && locked(player, false)) event.setCancelled(true);
    }

    /** A locked player dealing damage. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamageDealt(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && locked(player, false)) event.setCancelled(true);
    }

    /**
     * A locked player taking damage. Without this, a player who is connection-frozen by
     * a no-kick client patch (sees no disconnect, never leaves the world) can still be
     * killed or can still die to fall damage / mobs / environment while in lockdown,
     * which is both unfair (they cannot legitimately fight back since onDamageDealt is
     * cancelled) and pointless from an enforcement standpoint.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamageTaken(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && locked(player, false)) event.setCancelled(true);
    }

    /** Stops mobs from continuing to path toward / aggro a locked player. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMobTarget(EntityTargetEvent event) {
        if (event.getTarget() instanceof Player player && plugin.punishmentManager().isInImmediateLockdown(player)) event.setCancelled(true);
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (locked(event.getPlayer(), true)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (locked(event.getPlayer(), true)) event.setCancelled(true);
    }

    private boolean locked(Player player, boolean rateLimit) {
        if (!plugin.punishmentManager().isInImmediateLockdown(player)) return false;
        if (!rateLimit) {
            player.sendMessage(Text.color("&cYou are banned: &f" + plugin.punishmentManager().lockdownReason(player)));
        } else {
            long now = System.currentTimeMillis();
            Long last = lastMessage.get(player.getUniqueId());
            if (last == null || now - last > 1000L) {
                lastMessage.put(player.getUniqueId(), now);
                player.sendMessage(Text.color("&cYou are banned: &f" + plugin.punishmentManager().lockdownReason(player)));
            }
        }
        return true;
    }
}
