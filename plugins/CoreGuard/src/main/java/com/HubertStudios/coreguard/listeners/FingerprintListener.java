package com.HubertStudios.coreguard.listeners;

import com.HubertStudios.coreguard.CoreGuard;
import org.bukkit.Bukkit;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

public class FingerprintListener implements Listener {
    private final CoreGuard plugin;

    public FingerprintListener(CoreGuard plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!plugin.getConfig().getBoolean("anti-dupe.fingerprint-triggers.craft", true)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack result = event.getCurrentItem();
        if (result != null && !isStaffItem(result)) event.setCurrentItem(plugin.fingerprintService().ensureFingerprint(result, "CRAFT", player));
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!plugin.getConfig().getBoolean("anti-dupe.fingerprint-triggers.pickup", true)) return;
        if (!(event.getEntity() instanceof Player player)) return;
        Item item = event.getItem();
        if (!isStaffItem(item.getItemStack())) item.setItemStack(plugin.fingerprintService().ensureFingerprint(item.getItemStack(), "PICKUP", player));
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(ItemSpawnEvent event) {
        if (!plugin.getConfig().getBoolean("anti-dupe.fingerprint-triggers.item-spawn", true)) return;
        if (!isStaffItem(event.getEntity().getItemStack())) event.getEntity().setItemStack(plugin.fingerprintService().ensureFingerprint(event.getEntity().getItemStack(), "SPAWN", null));
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!plugin.getConfig().getBoolean("anti-dupe.fingerprint-triggers.drop", true)) return;
        if (!isStaffItem(event.getItemDrop().getItemStack())) event.getItemDrop().setItemStack(plugin.fingerprintService().ensureFingerprint(event.getItemDrop().getItemStack(), "DROP", event.getPlayer()));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!plugin.getConfig().getBoolean("anti-dupe.fingerprint-triggers.inventory-click", true)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack current = event.getCurrentItem();
        if (current != null && !isStaffItem(current)) event.setCurrentItem(plugin.fingerprintService().ensureFingerprint(current, "INVENTORY_CLICK", player));
        ItemStack cursor = event.getCursor();
        if (cursor != null && !cursor.getType().isAir() && !isStaffItem(cursor)) event.setCursor(plugin.fingerprintService().ensureFingerprint(cursor, "INVENTORY_CURSOR", player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player joining = event.getPlayer();
        plugin.staffModeManager().restoreFromDiskOnJoin(joining);
        if (plugin.vanishManager().isVanished(joining)) event.setJoinMessage(null);
        plugin.playerRepository().upsertJoin(joining);
        plugin.vanishManager().handleJoin(joining);
        if (!plugin.getConfig().getBoolean("anti-dupe.fingerprint-triggers.join-scan", true)) return;
        SchedulerUtil.runEntityLater(plugin, joining, 40L, () -> {
            Player player = event.getPlayer();
            if (player.isOnline()) plugin.dupeDetector().scanPlayer(player, true, true);
        });
    }

    private boolean isStaffItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(plugin.fingerprintService().staffItemKey());
    }
}
