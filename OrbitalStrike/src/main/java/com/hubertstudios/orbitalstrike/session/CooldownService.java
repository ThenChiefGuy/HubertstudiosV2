package com.hubertstudios.orbitalstrike.session;

import com.hubertstudios.orbitalstrike.config.PluginConfig;
import com.hubertstudios.orbitalstrike.config.StrikeType;
import org.bukkit.entity.Player;

/**
 * Checks and applies cooldowns. Bypass permission is checked by the caller
 * (command layer) before consulting this service, keeping permission logic
 * out of the cooldown bookkeeping itself.
 */
public final class CooldownService {

    private final PluginConfig config;
    private final SessionManager sessions;

    public CooldownService(PluginConfig config, SessionManager sessions) {
        this.config = config;
        this.sessions = sessions;
    }

    /** Returns remaining seconds on cooldown, or 0 if the player may act now. */
    public long remainingSeconds(Player player, StrikeType type) {
        long nowTick = player.getWorld().getFullTime();

        long globalUntil = sessions.getGlobalCooldownUntil(player.getUniqueId());
        if (nowTick < globalUntil) {
            return (globalUntil - nowTick) / 20L + 1;
        }

        if (config.perTypeCooldownEnabled()) {
            long typeUntil = sessions.getTypeCooldownUntil(player.getUniqueId(), type);
            if (nowTick < typeUntil) {
                return (typeUntil - nowTick) / 20L + 1;
            }
        }
        return 0;
    }

    public void apply(Player player, StrikeType type) {
        long nowTick = player.getWorld().getFullTime();
        sessions.setGlobalCooldown(player.getUniqueId(), nowTick + config.globalCooldownSeconds() * 20L);
        if (config.perTypeCooldownEnabled()) {
            sessions.setTypeCooldown(player.getUniqueId(), type, nowTick + config.globalCooldownSeconds() * 20L);
        }
    }
}
