package com.HubertStudios.coreguard.listeners;

import com.HubertStudios.coreguard.CoreGuard;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.List;
import java.util.Random;

public class StaffModeListener implements Listener {
    private final CoreGuard plugin;
    private final Random random = new Random();

    public StaffModeListener(CoreGuard plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!plugin.staffModeManager().isEnabled(player)) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        var item = player.getInventory().getItemInMainHand();
        if (plugin.fingerprintService().isStaffItem(item, "VANISH_TOGGLE")) {
            event.setCancelled(true);
            if (!player.hasPermission("coreguard.staff.vanish")) { denyRevoked(player); return; }
            boolean vanished = plugin.vanishManager().toggle(player);
            player.sendMessage(plugin.messages().msg(vanished ? "vanish.enabled" : "vanish.disabled", java.util.Map.of("player", player.getName())));
        } else if (plugin.fingerprintService().isStaffItem(item, "STAFFMODE_DISABLE")) {
            event.setCancelled(true);
            plugin.staffModeManager().disable(player);
            player.sendMessage(plugin.messages().msg("staff.mode-disabled"));
        } else if (plugin.fingerprintService().isStaffItem(item, "RANDOM_TELEPORT")) {
            event.setCancelled(true);
            if (!player.hasPermission("coreguard.staff.staffmode")) { denyRevoked(player); return; }
            List<Player> candidates = Bukkit.getOnlinePlayers().stream().filter(p -> !p.equals(player)).filter(p -> !plugin.vanishManager().isVanished(p) || player.hasPermission("coreguard.bypass.vanish-detect")).toList();
            if (!candidates.isEmpty()) player.teleport(candidates.get(random.nextInt(candidates.size())));
            else player.sendMessage(plugin.messages().msg("player-not-found"));
        }
    }

    @EventHandler
    public void onEntityInteract(PlayerInteractAtEntityEvent event) {
        Player staff = event.getPlayer();
        if (!plugin.staffModeManager().isEnabled(staff)) return;
        if (!(event.getRightClicked() instanceof Player target)) return;
        var item = staff.getInventory().getItemInMainHand();
        if (plugin.fingerprintService().isStaffItem(item, "INVENTORY_GUI_ITEM")) {
            event.setCancelled(true);
            // Staff mode can stay enabled across a permission downgrade (e.g. a group
            // change while the staff member is connected); re-check here rather than
            // relying solely on the staffmode flag, since the flag alone does not prove
            // the player still has inventory-edit rights.
            if (!staff.hasPermission("coreguard.staff.inventory")) { denyRevoked(staff); return; }
            plugin.guiSessionManager().openInventory(staff, target);
        }
    }

    private void denyRevoked(Player player) {
        plugin.messages().send(player, "no-permission");
    }
}
