package com.HubertStudios.coreguard.staff;

import com.HubertStudios.coreguard.CoreGuard;
import com.HubertStudios.coreguard.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class VanishManager {
    private final CoreGuard plugin;
    private final Set<UUID> vanished = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public VanishManager(CoreGuard plugin) {
        this.plugin = plugin;
    }

    public boolean toggle(Player target) {
        if (vanished.contains(target.getUniqueId())) {
            disable(target);
            return false;
        }
        enable(target);
        return true;
    }

    public void enable(Player target) {
        vanished.add(target.getUniqueId());
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.equals(target) && !viewer.hasPermission("coreguard.bypass.vanish-detect")) {
                viewer.hidePlayer(plugin, target);
            }
        }
        if (plugin.getConfig().getBoolean("vanish.fake-join-quit-messages", true)) {
            String quitMsg = plugin.getConfig().getString("vanish.fake-quit-message", "&e" + target.getName() + " left the game");
            sendFakeMessage(target, quitMsg);
        }
    }

    public void disable(Player target) {
        vanished.remove(target.getUniqueId());
        for (Player viewer : Bukkit.getOnlinePlayers()) viewer.showPlayer(plugin, target);
        if (plugin.getConfig().getBoolean("vanish.fake-join-quit-messages", true)) {
            String joinMsg = plugin.getConfig().getString("vanish.fake-join-message", "&e" + target.getName() + " joined the game");
            sendFakeMessage(target, joinMsg);
        }
    }

    private void sendFakeMessage(Player target, String message) {
        if (message == null || message.isBlank()) return;
        String formatted = Text.color(message.replace("%player%", target.getName()));
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(target)) continue;
            if (!viewer.hasPermission("coreguard.bypass.vanish-detect")) viewer.sendMessage(formatted);
        }
    }

    public boolean isVanished(Player player) {
        return vanished.contains(player.getUniqueId());
    }

    public void applyVisibilityFor(Player viewer) {
        boolean canSee = viewer.hasPermission("coreguard.bypass.vanish-detect");
        for (UUID uuid : vanished) {
            Player vanishedPlayer = Bukkit.getPlayer(uuid);
            if (vanishedPlayer == null || vanishedPlayer.equals(viewer)) continue;
            if (!canSee) viewer.hidePlayer(plugin, vanishedPlayer);
        }
    }


    public void handleJoin(Player player) {
        applyVisibilityFor(player);
        if (!isVanished(player)) return;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(player)) continue;
            if (!viewer.hasPermission("coreguard.bypass.vanish-detect")) {
                viewer.hidePlayer(plugin, player);
            }
        }
    }

    public void handleQuit(Player player) {
        if (!plugin.getConfig().getBoolean("vanish.persist-on-relog", true)) vanished.remove(player.getUniqueId());
    }
}
