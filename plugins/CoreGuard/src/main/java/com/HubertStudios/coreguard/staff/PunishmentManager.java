package com.HubertStudios.coreguard.staff;

import com.HubertStudios.coreguard.CoreGuard;
import com.HubertStudios.coreguard.repositories.PunishmentRepository;
import com.HubertStudios.coreguard.util.Text;
import com.HubertStudios.coreguard.util.SchedulerUtil;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.net.InetAddress;
import java.time.Duration;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ban/mute/warn enforcement.
 *
 * IMPORTANT on identifiers: on offline-mode/cracked servers (and even on some
 * online-mode setups behind certain proxies), none of UUID, name, or IP is
 * guaranteed to stay constant for a given person — a cracked player's UUID is
 * derived from their chosen name, so changing their name changes their UUID;
 * a name can be changed freely; an IP can change between sessions. Because no
 * single identifier is reliable, a ban here is recorded against every
 * identifier known at ban-time and enforced if ANY of them match on
 * reconnect. This raises the bar for evasion (an evader needs to change every
 * identifier at once) instead of lowering it (which is what enforcing on a
 * single swappable identifier, like name alone, would do).
 *
 * All active bans are persisted to the database (active_bans table) so they
 * survive a server restart or crash — an in-memory-only lockdown disappears
 * the moment the JVM stops, silently un-banning everyone.
 */
public class PunishmentManager {
    private final CoreGuard plugin;
    private final Map<UUID, Long> mutedUntil = new ConcurrentHashMap<>();
    private final Map<UUID, String> muteReasons = new ConcurrentHashMap<>();

    // In-memory mirrors of the persisted active_bans table, used for fast lookups
    // on PlayerLoginEvent without hitting the database on every connection attempt.
    // Rebuilt from the database on plugin enable via loadPersistedBans().
    private final Map<UUID, Long> lockedUuidUntil = new ConcurrentHashMap<>();
    private final Map<String, Long> lockedNameUntil = new ConcurrentHashMap<>();
    private final Map<String, Long> lockedIpUntil = new ConcurrentHashMap<>();
    private final Map<UUID, String> lockdownReasons = new ConcurrentHashMap<>();
    private final Map<String, String> lockdownReasonsByName = new ConcurrentHashMap<>();

    public PunishmentManager(CoreGuard plugin) {
        this.plugin = plugin;
    }

    /** Rebuilds in-memory lockdown state from the database. Call once on plugin enable. */
    public void loadPersistedBans() {
        lockedUuidUntil.clear();
        lockedNameUntil.clear();
        lockedIpUntil.clear();
        lockdownReasons.clear();
        lockdownReasonsByName.clear();
        for (PunishmentRepository.ActiveBanRow row : plugin.punishmentRepository().loadActiveBans()) {
            long until = row.expiresAtMillis() == null ? 0L : row.expiresAtMillis();
            if (row.uuid() != null) {
                try {
                    UUID uuid = UUID.fromString(row.uuid());
                    lockedUuidUntil.put(uuid, until);
                    lockdownReasons.put(uuid, row.reason() == null ? "Banned" : row.reason());
                } catch (IllegalArgumentException ignored) { /* malformed row, skip */ }
            }
            if (row.lowercaseName() != null) {
                lockedNameUntil.put(row.lowercaseName(), until);
                lockdownReasonsByName.put(row.lowercaseName(), row.reason() == null ? "Banned" : row.reason());
            }
            if (row.ip() != null) {
                lockedIpUntil.put(row.ip(), until);
            }
        }
    }

    public void mute(OfflinePlayer target, Duration duration, String reason, CommandSender executor) {
        long expires = (duration == null || duration.isZero()) ? 0L : System.currentTimeMillis() + duration.toMillis();
        mutedUntil.put(target.getUniqueId(), expires);
        muteReasons.put(target.getUniqueId(), reason == null ? "Muted" : reason);
        plugin.punishmentRepository().addPunishment(target.getUniqueId().toString(), safeName(target), expires == 0L ? "MUTE" : "TEMPMUTE", reason, actorUuid(executor), executor.getName(), expires == 0L ? null : expires);
        plugin.playerRepository().increment(target.getUniqueId().toString(), "mute_count");
    }

    public void unmute(OfflinePlayer target) {
        mutedUntil.remove(target.getUniqueId());
        muteReasons.remove(target.getUniqueId());
    }

    public boolean isMuted(Player player) {
        Long until = mutedUntil.get(player.getUniqueId());
        if (until == null) return false;
        if (until != 0L && until < System.currentTimeMillis()) {
            mutedUntil.remove(player.getUniqueId());
            muteReasons.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    public String muteReason(Player player) {
        return muteReasons.getOrDefault(player.getUniqueId(), "Muted");
    }

    public void warn(OfflinePlayer target, String reason, CommandSender executor) {
        plugin.punishmentRepository().addWarning(target.getUniqueId().toString(), safeName(target), reason, actorUuid(executor), executor.getName());
        plugin.playerRepository().increment(target.getUniqueId().toString(), "warn_count");
    }

    /**
     * Bans the target. Always records the UUID and name; additionally records the IP
     * if the target is online and an IP is available. The ban is enforced if any one
     * of these identifiers later matches, so a name change or a UUID change (which on
     * cracked servers are the same event) alone does not let the player back in.
     */
    @SuppressWarnings("deprecation")
    public void ban(OfflinePlayer target, Duration duration, String reason, CommandSender executor) {
        Date expires = (duration == null || duration.isZero()) ? null : new Date(System.currentTimeMillis() + duration.toMillis());
        String targetName = safeName(target);
        Long expiresMillis = expires == null ? null : expires.getTime();

        // Bukkit/Paper ban list integration kept for compatibility with other plugins
        // and external tooling that read the vanilla ban list. Paper 1.21 removed
        // enforcement of BanList.Type.NAME entirely (addBan/pardon on it are no-ops),
        // so the real ban list entry must be by UUID. This is in addition to, not
        // instead of, CoreGuard's own persisted lockdown below, since the vanilla UUID
        // ban list alone would still be bypassable by a renamed cracked alt.
        try {
            Bukkit.getBanList(BanList.Type.UUID).addBan(target.getUniqueId().toString(), reason, expires, executor.getName());
        } catch (Exception ignored) {
            // Some non-vanilla auth setups (e.g. fully offline) may reject this; the
            // persisted lockdown below is the real enforcement path regardless.
        }

        Player online = target.getPlayer();
        String ip = null;
        if (online != null && online.getAddress() != null) {
            ip = online.getAddress().getAddress().getHostAddress();
        }
        boolean ipBanRequested = plugin.getConfig().getBoolean("punishment-system.ban-enforcement.ip-ban-on-player-ban", false);
        if (ipBanRequested && ip != null) {
            banIp(ip, duration, reason, executor);
        }

        activateLockdown(target.getUniqueId(), targetName, ipBanRequested ? ip : null, expiresMillis, reason, executor.getName());

        if (online != null) {
            repeatKick(online, reason);
        }

        if (plugin.getConfig().getBoolean("punishment-system.ban-enforcement.uuid-record-enabled", true)) {
            plugin.punishmentRepository().addPunishment(target.getUniqueId().toString(), targetName, expires == null ? "BAN" : "TEMPBAN", reason, actorUuid(executor), executor.getName(), expiresMillis);
        }
        plugin.playerRepository().increment(target.getUniqueId().toString(), "ban_count");
    }

    /** Records a persisted, multi-identifier lockdown and mirrors it into the in-memory maps used for fast login checks. */
    private void activateLockdown(UUID uuid, String name, String ip, Long expiresMillis, String reason, String executorName) {
        if (!plugin.getConfig().getBoolean("punishment-system.ban-enforcement.immediate-lockdown-enabled", true)) return;
        long until = expiresMillis == null ? 0L : expiresMillis;
        String lowerName = name == null ? null : name.toLowerCase(Locale.ROOT);
        String safeReason = reason == null ? "Banned" : reason;

        plugin.punishmentRepository().addActiveBan(uuid == null ? null : uuid.toString(), name, ip, safeReason, executorName, expiresMillis);

        if (uuid != null) {
            lockedUuidUntil.put(uuid, until);
            lockdownReasons.put(uuid, safeReason);
        }
        if (lowerName != null) {
            lockedNameUntil.put(lowerName, until);
            lockdownReasonsByName.put(lowerName, safeReason);
        }
        if (ip != null) {
            lockedIpUntil.put(ip, until);
        }
    }

    @SuppressWarnings("deprecation")
    public void unban(String name) {
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        try {
            Bukkit.getBanList(BanList.Type.UUID).pardon(offline.getUniqueId().toString());
        } catch (Exception ignored) { /* best effort */ }
        String lowerName = name.toLowerCase(Locale.ROOT);
        lockedNameUntil.remove(lowerName);
        lockdownReasonsByName.remove(lowerName);
        lockedUuidUntil.remove(offline.getUniqueId());
        lockdownReasons.remove(offline.getUniqueId());
        plugin.punishmentRepository().deactivateBan(offline.getUniqueId().toString(), lowerName);
    }

    @SuppressWarnings("deprecation")
    public void banIp(String ip, Duration duration, String reason, CommandSender executor) {
        Date expires = (duration == null || duration.isZero()) ? null : new Date(System.currentTimeMillis() + duration.toMillis());
        Bukkit.getBanList(BanList.Type.IP).addBan(ip, reason, expires, executor.getName());
        long until = expires == null ? 0L : expires.getTime();
        lockedIpUntil.put(ip, until);
    }

    @SuppressWarnings("deprecation")
    public void unbanIp(String ip) {
        Bukkit.getBanList(BanList.Type.IP).pardon(ip);
        lockedIpUntil.remove(ip);
    }

    /** Locks down and kicks an already-online player without going through the full ban() flow (used for IP-ban follow-through). */
    public void lockAndKick(Player player, Duration duration, String reason) {
        Date expires = (duration == null || duration.isZero()) ? null : new Date(System.currentTimeMillis() + duration.toMillis());
        Long expiresMillis = expires == null ? null : expires.getTime();
        String ip = player.getAddress() == null ? null : player.getAddress().getAddress().getHostAddress();
        activateLockdown(player.getUniqueId(), player.getName(), ip, expiresMillis, reason, "CONSOLE");
        repeatKick(player, reason);
    }

    /** Checks lockdown for an online player by UUID, name, and current IP. Any match enforces. */
    public boolean isInImmediateLockdown(Player player) {
        if (!plugin.getConfig().getBoolean("punishment-system.ban-enforcement.immediate-lockdown-enabled", true)) return false;
        String ip = player.getAddress() == null ? null : player.getAddress().getAddress().getHostAddress();
        return checkLockdown(player.getUniqueId(), player.getName(), ip);
    }

    /**
     * Checks lockdown ahead of a full login (works for offline players too, since it
     * takes raw identifiers rather than a connected Player). Intended for use from
     * AsyncPlayerPreLoginEvent / PlayerLoginEvent so a banned player is rejected before
     * they fully join, regardless of which single identifier they changed.
     */
    public boolean isBanned(UUID uuid, String name, InetAddress address) {
        if (!plugin.getConfig().getBoolean("punishment-system.ban-enforcement.immediate-lockdown-enabled", true)) return false;
        String ip = address == null ? null : address.getHostAddress();
        return checkLockdown(uuid, name, ip);
    }

    private boolean checkLockdown(UUID uuid, String name, String ip) {
        long now = System.currentTimeMillis();
        boolean banned = false;

        if (uuid != null) {
            Long uuidUntil = lockedUuidUntil.get(uuid);
            if (uuidUntil != null) {
                if (uuidUntil == 0L || uuidUntil > now) banned = true;
                else { lockedUuidUntil.remove(uuid); lockdownReasons.remove(uuid); }
            }
        }
        if (name != null) {
            String lowerName = name.toLowerCase(Locale.ROOT);
            Long nameUntil = lockedNameUntil.get(lowerName);
            if (nameUntil != null) {
                if (nameUntil == 0L || nameUntil > now) banned = true;
                else { lockedNameUntil.remove(lowerName); lockdownReasonsByName.remove(lowerName); }
            }
        }
        if (ip != null) {
            Long ipUntil = lockedIpUntil.get(ip);
            if (ipUntil != null) {
                if (ipUntil == 0L || ipUntil > now) banned = true;
                else lockedIpUntil.remove(ip);
            }
        }
        return banned;
    }

    public String lockdownReason(Player player) {
        String byUuid = lockdownReasons.get(player.getUniqueId());
        if (byUuid != null) return byUuid;
        String byName = lockdownReasonsByName.get(player.getName().toLowerCase(Locale.ROOT));
        return byName != null ? byName : "Banned";
    }

    private void repeatKick(Player player, String reason) {
        int attempts = Math.max(0, plugin.getConfig().getInt("punishment-system.ban-enforcement.repeat-kick-attempts", 5));
        int delay = Math.max(1, plugin.getConfig().getInt("punishment-system.ban-enforcement.repeat-kick-every-ticks", 20));
        UUID targetUuid = player.getUniqueId();
        String message = Text.color(reason == null || reason.isBlank() ? "Banned" : reason);
        player.kickPlayer(message);
        for (int i = 1; i <= attempts; i++) {
            int attempt = i;
            SchedulerUtil.runGlobalLater(plugin, (long) attempt * delay, () -> {
                Player online = Bukkit.getPlayer(targetUuid);
                if (online != null && online.isOnline()) {
                    SchedulerUtil.runEntity(plugin, online, () -> {
                        if (online.isOnline() && isInImmediateLockdown(online)) online.kickPlayer(message);
                    });
                }
            });
        }
    }

    private String safeName(OfflinePlayer player) {
        return player.getName() == null || player.getName().isBlank() ? player.getUniqueId().toString() : player.getName();
    }

    private String actorUuid(CommandSender sender) {
        return sender instanceof Player p ? p.getUniqueId().toString() : "CONSOLE";
    }
}
