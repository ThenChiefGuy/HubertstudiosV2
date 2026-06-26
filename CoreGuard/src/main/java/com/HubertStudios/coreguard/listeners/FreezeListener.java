package com.HubertStudios.coreguard.listeners;

import com.HubertStudios.coreguard.CoreGuard;
import com.HubertStudios.coreguard.util.Text;
import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Locale;

public class FreezeListener implements Listener {
    private final CoreGuard plugin;

    public FreezeListener(CoreGuard plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getConfig().getBoolean("freeze.enabled", true)) return;
        Player player = event.getPlayer();
        if (!plugin.freezeManager().isFrozen(player)) return;
        if (player.hasPermission("coreguard.bypass.freeze")) return;
        if (!plugin.getConfig().getBoolean("freeze.prevent-movement", true)) return;
        var from = event.getFrom();
        var to = event.getTo();
        if (to == null) return;
        boolean movedHorizontally = from.getBlockX() != to.getBlockX() || from.getBlockZ() != to.getBlockZ();
        boolean movedDown = from.getBlockY() > to.getBlockY();
        if (movedHorizontally || movedDown) {
            event.setTo(from);
            player.sendActionBar(Text.component(plugin.getConfig().getString("freeze.warning-actionbar", "&7Contact staff")));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!plugin.getConfig().getBoolean("freeze.enabled", true)) return;
        if (plugin.freezeManager().isFrozen(event.getPlayer()) && plugin.getConfig().getBoolean("freeze.prevent-interaction", true)) event.setCancelled(true);
    }

    /**
     * Explicitly blocks launching any projectile (ender pearl, bow, crossbow, trident,
     * firework boost) while frozen. PlayerInteractEvent already blocks most item use,
     * but the actual launch is its own event on modern Paper, so this is defense in
     * depth rather than a duplicate check.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLaunchProjectile(PlayerLaunchProjectileEvent event) {
        if (!plugin.getConfig().getBoolean("freeze.enabled", true)) return;
        if (!plugin.getConfig().getBoolean("freeze.prevent-interaction", true)) return;
        Player player = event.getPlayer();
        if (plugin.freezeManager().isFrozen(player) && !player.hasPermission("coreguard.bypass.freeze")) event.setCancelled(true);
    }

    /** PlayerLaunchProjectileEvent explicitly excludes bow/crossbow arrows; those fire EntityShootBowEvent instead. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent event) {
        if (!plugin.getConfig().getBoolean("freeze.enabled", true)) return;
        if (!plugin.getConfig().getBoolean("freeze.prevent-interaction", true)) return;
        if (event.getEntity() instanceof Player player && plugin.freezeManager().isFrozen(player) && !player.hasPermission("coreguard.bypass.freeze")) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamageDealt(EntityDamageByEntityEvent event) {
        if (!plugin.getConfig().getBoolean("freeze.enabled", true)) return;
        if (event.getDamager() instanceof Player player && plugin.freezeManager().isFrozen(player) && !player.hasPermission("coreguard.bypass.freeze")) event.setCancelled(true);
    }

    /**
     * A frozen player taking damage. Freeze is meant to pin someone in place while
     * staff investigate, not to leave them defenseless against mobs or fall damage —
     * without this, a frozen player can still die while unable to move or fight back.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamageTaken(EntityDamageEvent event) {
        if (!plugin.getConfig().getBoolean("freeze.enabled", true)) return;
        if (!plugin.getConfig().getBoolean("freeze.prevent-damage-taken", true)) return;
        if (event.getEntity() instanceof Player player && plugin.freezeManager().isFrozen(player) && !player.hasPermission("coreguard.bypass.freeze")) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!plugin.getConfig().getBoolean("freeze.enabled", true)) return;
        Player player = event.getPlayer();
        if (plugin.freezeManager().isFrozen(player) && !player.hasPermission("coreguard.bypass.freeze")) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!plugin.getConfig().getBoolean("freeze.enabled", true)) return;
        if (event.getEntity() instanceof Player player && plugin.freezeManager().isFrozen(player) && !player.hasPermission("coreguard.bypass.freeze")) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.getConfig().getBoolean("freeze.enabled", true)) return;
        Player player = event.getPlayer();
        if (!plugin.freezeManager().isFrozen(player)) return;
        if (!plugin.getConfig().getBoolean("freeze.prevent-commands", true)) return;
        String label = event.getMessage().contains(" ") ? event.getMessage().substring(1, event.getMessage().indexOf(' ')).toLowerCase(Locale.ROOT) : event.getMessage().substring(1).toLowerCase(Locale.ROOT);
        if (plugin.getConfig().getStringList("freeze.allowed-commands").stream().map(s -> s.toLowerCase(Locale.ROOT)).anyMatch(label::equals)) return;
        event.setCancelled(true);
        player.sendMessage(plugin.messages().msg("freeze.blocked-command"));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFreezeStick(PlayerInteractAtEntityEvent event) {
        if (!plugin.getConfig().getBoolean("freeze.enabled", true)) return;
        if (!plugin.getConfig().getBoolean("freeze.use-stick", false)) return;
        if (!(event.getRightClicked() instanceof Player target)) return;
        Player staff = event.getPlayer();
        ItemStack item = staff.getInventory().getItemInMainHand();
        if (!plugin.fingerprintService().isStaffItem(item, "FREEZE_STICK")) return;
        event.setCancelled(true);
        if (!staff.hasPermission("coreguard.staff.freeze")) return;
        if (plugin.freezeManager().isFrozen(target)) {
            plugin.freezeManager().unfreeze(target);
            staff.sendMessage(plugin.messages().msg("freeze.unfrozen", java.util.Map.of("player", target.getName())));
        } else {
            plugin.freezeManager().freeze(target, "Freeze stick");
            staff.sendMessage(plugin.messages().msg("freeze.frozen", java.util.Map.of("player", target.getName())));
        }
        consumeUse(staff, item);
    }

    private void consumeUse(Player staff, ItemStack item) {
        if (item == null || !item.hasItemMeta()) return;
        var meta = item.getItemMeta();
        Integer uses = meta.getPersistentDataContainer().get(plugin.fingerprintService().freezeStickUsesKey(), PersistentDataType.INTEGER);
        if (uses == null) return;
        uses--;
        if (uses <= 0 && plugin.getConfig().getBoolean("freeze.delete-stick-when-empty", true)) {
            staff.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        } else {
            meta.getPersistentDataContainer().set(plugin.fingerprintService().freezeStickUsesKey(), PersistentDataType.INTEGER, Math.max(0, uses));
            meta.setLore(java.util.List.of(Text.color("&7Uses: &f" + Math.max(0, uses))));
            item.setItemMeta(meta);
        }
    }
}
