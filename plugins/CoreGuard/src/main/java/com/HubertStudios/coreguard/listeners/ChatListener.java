package com.HubertStudios.coreguard.listeners;

import com.HubertStudios.coreguard.CoreGuard;
import com.HubertStudios.coreguard.util.Text;
import com.HubertStudios.coreguard.util.SchedulerUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Locale;

@SuppressWarnings("deprecation")
public class ChatListener implements Listener {
    private final CoreGuard plugin;

    public ChatListener(CoreGuard plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (plugin.freezeManager().isFrozen(player) && plugin.getConfig().getBoolean("freeze.prevent-chat", true)) {
            event.setCancelled(true);
            String msg = plugin.getConfig().getString("freeze.warning-actionbar", "&7Contact staff");
            SchedulerUtil.runEntity(plugin, player, () -> player.sendMessage(Text.color(msg)));
            return;
        }
        if (plugin.punishmentManager().isMuted(player)) {
            event.setCancelled(true);
            String reason = plugin.punishmentManager().muteReason(player);
            SchedulerUtil.runEntity(plugin, player, () -> player.sendMessage(Text.color("&cYou are muted. Reason: &f" + reason)));
            return;
        }
        String senderName = player.getName();
        String message = event.getMessage();
        SchedulerUtil.runGlobal(plugin, () -> {
            Player liveSender = Bukkit.getPlayer(player.getUniqueId());
            if (liveSender == null) return;
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (plugin.spyManager().watches(viewer.getUniqueId(), liveSender.getUniqueId())) {
                    SchedulerUtil.runEntity(plugin, viewer, () ->
                            viewer.sendMessage(Text.color("&8[&dSpy&8] &f" + senderName + "&8: &7" + message)));
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String raw = event.getMessage();
        String label = raw.contains(" ") ? raw.substring(1, raw.indexOf(' ')).toLowerCase(Locale.ROOT) : raw.substring(1).toLowerCase(Locale.ROOT);
        if (plugin.punishmentManager().isMuted(player) && plugin.getConfig().getStringList("punishment-system.muted-command-block-list").stream().map(s -> s.toLowerCase(Locale.ROOT)).anyMatch(label::equals)) {
            event.setCancelled(true);
            player.sendMessage(Text.color("&cYou are muted. Reason: &f" + plugin.punishmentManager().muteReason(player)));
            return;
        }
        boolean anySpy = Bukkit.getOnlinePlayers().stream().anyMatch(v -> plugin.spyManager().isSpying(v.getUniqueId()));
        if (!anySpy) return;
        String senderName = player.getName();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (plugin.spyManager().watches(viewer.getUniqueId(), player.getUniqueId())) {
                SchedulerUtil.runEntity(plugin, viewer, () -> viewer.sendMessage(Text.color("&8[&dCommandSpy&8] &f" + senderName + "&8: &7" + raw)));
            }
        }
    }
}
