package com.HubertStudios.coreguard.listeners;

import com.HubertStudios.coreguard.CoreGuard;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public class InventoryGuiListener implements Listener {
    private final CoreGuard plugin;

    public InventoryGuiListener(CoreGuard plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        plugin.guiSessionManager().session(player).ifPresent(session -> {
            if (!event.getView().getTopInventory().equals(session.inventory())) return;
            ClickType click = event.getClick();
            if (click.isShiftClick() || click == ClickType.NUMBER_KEY || click == ClickType.DOUBLE_CLICK || click == ClickType.SWAP_OFFHAND) {
                event.setCancelled(true);
                return;
            }
            if (event.getRawSlot() < event.getView().getTopInventory().getSize()) {
                event.setCancelled(true);
                plugin.guiSessionManager().applyClick(player, event.getRawSlot());
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        plugin.guiSessionManager().session(player).ifPresent(session -> {
            if (!event.getView().getTopInventory().equals(session.inventory())) return;
            for (int raw : event.getRawSlots()) {
                if (raw < event.getView().getTopInventory().getSize()) {
                    event.setCancelled(true);
                    return;
                }
            }
        });
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) plugin.guiSessionManager().close(player);
    }
}
