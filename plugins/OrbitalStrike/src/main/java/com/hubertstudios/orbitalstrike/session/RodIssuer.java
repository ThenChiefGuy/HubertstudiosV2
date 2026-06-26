package com.hubertstudios.orbitalstrike.session;

import com.hubertstudios.orbitalstrike.config.BaseStrikeConfig;
import com.hubertstudios.orbitalstrike.config.PluginConfig;
import com.hubertstudios.orbitalstrike.config.StrikeType;
import com.hubertstudios.orbitalstrike.util.FoliaUtil;
import com.hubertstudios.orbitalstrike.util.TextUtil;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Central place that wires together session creation, rod item creation,
 * giving the rod to a player, and scheduling its expiry. Used by both the
 * normal /orbital <type> command and the /orbital <type> <amount> [player]
 * variant.
 */
public final class RodIssuer {

    private final Plugin plugin;
    private final PluginConfig config;
    private final SessionManager sessions;
    private final RodFactory rodFactory;

    public RodIssuer(Plugin plugin, PluginConfig config, SessionManager sessions, RodFactory rodFactory) {
        this.plugin = plugin;
        this.config = config;
        this.sessions = sessions;
        this.rodFactory = rodFactory;
    }

    /** Issues a single rod of the given type to the player, returning the created session. */
    public RodSession issue(Player player, StrikeType type, BaseStrikeConfig typeConfig) {
        long nowTick = player.getWorld().getFullTime();
        RodSession session = sessions.create(player.getUniqueId(), type, nowTick);

        ItemStack rod = rodFactory.build(typeConfig.rod(), type.key(), session.sessionId());
        player.getInventory().addItem(rod);

        if (config.soundRodGiven() != null) {
            player.playSound(player.getLocation(), config.soundRodGiven(), 1.0f, 1.0f);
        }

        long expiryTicks = config.rodExpirySeconds() * 20L;
        if (expiryTicks > 0) {
            FoliaUtil.runOnEntityLater(plugin, player, expiryTicks, () -> {
                if (session.isExpired() || session.stage() == RodSession.Stage.COMPLETE) {
                    return;
                }
                expireRod(player, session);
            }, () -> { /* player left before expiry; session cleanup happens via PlayerQuitEvent if added later */ });
        }

        return session;
    }

    private void expireRod(Player player, RodSession session) {
        sessions.remove(session.sessionId());
        if (player.isOnline()) {
            for (ItemStack stack : player.getInventory().getContents()) {
                if (stack != null && session.sessionId().equals(rodFactory.readSessionId(stack))) {
                    stack.setAmount(0);
                }
            }
            player.sendMessage(TextUtil.prefixed(config.prefix(), "&7Your targeting rod expired."));
        }
    }
}
