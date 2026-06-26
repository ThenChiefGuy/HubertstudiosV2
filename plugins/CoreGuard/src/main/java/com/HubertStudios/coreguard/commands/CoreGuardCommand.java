package com.HubertStudios.coreguard.commands;

import com.HubertStudios.coreguard.CoreGuard;
import com.HubertStudios.coreguard.models.ItemRecord;
import com.HubertStudios.coreguard.models.PlayerRecord;
import com.HubertStudios.coreguard.util.Text;
import com.HubertStudios.coreguard.util.TimeParser;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class CoreGuardCommand implements CommandExecutor, TabCompleter {
    private final CoreGuard plugin;
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public CoreGuardCommand(CoreGuard plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("coreguard.use")) return deny(sender);
        if (plugin.licenseGate() == null || !plugin.licenseGate().isArmed()) return true;
        if (args.length == 0) {
            sender.sendMessage(plugin.messages().msg("command-header", Map.of("version", plugin.getDescription().getVersion())));
            sender.sendMessage(Text.color("&7Use &f/cg help &7for commands."));
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            return switch (sub) {
                case "reload" -> reload(sender);
                case "help" -> help(sender, args);
                case "iteminfo" -> itemInfo(sender);
                case "scan" -> scan(sender, args);
                case "blacklist" -> blacklist(sender, args);
                case "dupelog" -> dupeLog(sender, args);
                case "vanish" -> vanish(sender, args);
                case "spy" -> spy(sender, args);
                case "freeze" -> freeze(sender, args);
                case "unfreeze" -> unfreeze(sender, args);
                case "freezestick" -> freezeStick(sender);
                case "inventory", "inv" -> inventory(sender, args);
                case "echest", "enderchest" -> echest(sender, args);
                case "ban" -> ban(sender, args);
                case "unban" -> unban(sender, args);
                case "banip" -> banIp(sender, args);
                case "unbanip" -> unbanIp(sender, args);
                case "mute" -> mute(sender, args);
                case "unmute" -> unmute(sender, args);
                case "warn" -> warn(sender, args);
                case "warnings" -> warnings(sender, args);
                case "clearwarnings" -> clearWarnings(sender, args);
                case "kick" -> kick(sender, args);
                case "staffmode" -> staffMode(sender);
                case "sc" -> staffChat(sender, args);
                case "lookup" -> lookup(sender, args);
                case "invbackup" -> invBackup(sender, args);
                case "invrestore" -> invRestore(sender, args);
                case "history" -> history(sender, args);
                default -> { sender.sendMessage(Text.color("&cUnknown subcommand. Use /cg help.")); yield true; }
            };
        } catch (Exception e) {
            sender.sendMessage(Text.color("&cCommand error: &f" + e.getMessage()));
            if (plugin.getConfig().getBoolean("plugin.debug-enabled", false)) e.printStackTrace();
            return true;
        }
    }

    private boolean reload(CommandSender sender) {
        if (!perm(sender, "coreguard.admin.reload")) return deny(sender);
        plugin.reloadCoreGuard();
        plugin.messages().send(sender, "reload-success");
        return true;
    }

    private boolean help(CommandSender sender, String[] args) {
        int page = args.length >= 2 ? parseInt(args[1], 1) : 1;
        List<String> lines = new ArrayList<>();
        addHelp(sender, lines, "coreguard.use", "/cg", "Status");
        addHelp(sender, lines, "coreguard.admin.reload", "/cg reload", "Reload configs");
        addHelp(sender, lines, "coreguard.staff.iteminfo", "/cg iteminfo", "Held item fingerprint info");
        addHelp(sender, lines, "coreguard.staff.scan", "/cg scan <player>", "Scan inventory/e-chest");
        addHelp(sender, lines, "coreguard.admin.blacklist", "/cg blacklist add|remove|list", "Manage illegal UUIDs");
        addHelp(sender, lines, "coreguard.staff.vanish", "/cg vanish [player]", "Toggle vanish");
        addHelp(sender, lines, "coreguard.staff.freeze", "/cg freeze <player> [reason]", "Freeze player");
        addHelp(sender, lines, "coreguard.staff.inventory", "/cg inventory <player>", "Live inventory GUI");
        addHelp(sender, lines, "coreguard.staff.echest", "/cg echest <player>", "Live ender chest GUI");
        addHelp(sender, lines, "coreguard.staff.ban", "/cg ban <player> [time] <reason>", "Ban/tempban");
        addHelp(sender, lines, "coreguard.staff.mute", "/cg mute <player> [time] <reason>", "Mute/tempmute");
        addHelp(sender, lines, "coreguard.staff.warn", "/cg warn <player> <reason>", "Warn player");
        addHelp(sender, lines, "coreguard.staff.staffmode", "/cg staffmode", "Toggle staff mode");
        addHelp(sender, lines, "coreguard.staff.lookup", "/cg lookup <player>", "Player data + alts by IP");
        addHelp(sender, lines, "coreguard.staff.invbackup", "/cg invbackup <player>", "Manual inventory backup");
        addHelp(sender, lines, "coreguard.staff.invrestore", "/cg invrestore <player> [id]", "Restore inventory backup");
        addHelp(sender, lines, "coreguard.staff.history", "/cg history <player>", "Punishment history");
        int per = 8;
        int pages = Math.max(1, (int) Math.ceil(lines.size() / (double) per));
        page = Math.min(Math.max(1, page), pages);
        sender.sendMessage(Text.color("&8&m-----&r &dCoreGuard Help &7(" + page + "/" + pages + ") &8&m-----"));
        for (int i = (page - 1) * per; i < Math.min(lines.size(), page * per); i++) sender.sendMessage(lines.get(i));
        return true;
    }

    private boolean itemInfo(CommandSender sender) {
        if (!perm(sender, "coreguard.staff.iteminfo")) return deny(sender);
        if (!(sender instanceof Player player)) return onlyPlayer(sender);
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) { plugin.messages().send(sender, "dupe.item-no-item"); return true; }
        item = plugin.fingerprintService().ensureFingerprint(item, "ITEMINFO", player);
        player.getInventory().setItemInMainHand(item);
        String uuid = plugin.fingerprintService().getItemUuid(item);
        if (uuid == null) uuid = plugin.fingerprintService().getBatchUuid(item);
        if (uuid == null) { plugin.messages().send(sender, "dupe.item-no-fingerprint"); return true; }
        Optional<ItemRecord> record = plugin.itemRepository().find(uuid);
        Map<String, String> ph = new HashMap<>();
        ph.put("uuid", uuid);
        ph.put("material", item.getType().name());
        ph.put("created_at", record.map(r -> FORMAT.format(Instant.ofEpochMilli(r.createdAt()))).orElse("unknown"));
        ph.put("method", record.map(ItemRecord::creationMethod).orElse("unknown"));
        ph.put("holder", record.map(ItemRecord::lastHolderName).orElse("unknown"));
        plugin.messages().list("dupe.item-info", ph).forEach(sender::sendMessage);
        plugin.itemRepository().history(uuid, 5).forEach(line -> sender.sendMessage(Text.color("&8- &7" + line)));
        return true;
    }

    private boolean scan(CommandSender sender, String[] args) {
        if (!perm(sender, "coreguard.staff.scan")) return deny(sender);
        if (args.length < 2) return usage(sender, "/cg scan <player>");
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) return notFound(sender);
        plugin.messages().send(sender, "dupe.scan-start", Map.of("player", target.getName()));
        var result = plugin.dupeDetector().scanPlayer(target, true);
        if (result.clean()) plugin.messages().send(sender, "dupe.scan-clean");
        else {
            plugin.messages().send(sender, "dupe.scan-found", Map.of("count", String.valueOf(result.count())));
            result.problems().forEach(p -> sender.sendMessage(Text.color("&8- &c" + p)));
        }
        return true;
    }

    private boolean blacklist(CommandSender sender, String[] args) {
        if (!perm(sender, "coreguard.admin.blacklist")) return deny(sender);
        if (args.length < 2) return usage(sender, "/cg blacklist add|remove|list [uuid]");
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                if (args.length < 3) return usage(sender, "/cg blacklist add <uuid>");
                plugin.blacklistManager().add(args[2]);
                plugin.itemRepository().setBlacklisted(args[2], true);
                plugin.messages().send(sender, "blacklist.added", Map.of("uuid", args[2]));
            }
            case "remove" -> {
                if (args.length < 3) return usage(sender, "/cg blacklist remove <uuid>");
                plugin.blacklistManager().remove(args[2]);
                plugin.itemRepository().setBlacklisted(args[2], false);
                plugin.messages().send(sender, "blacklist.removed", Map.of("uuid", args[2]));
            }
            case "list" -> {
                if (plugin.blacklistManager().all().isEmpty()) plugin.messages().send(sender, "blacklist.empty");
                else plugin.blacklistManager().all().forEach(id -> sender.sendMessage(Text.color("&8- &f" + id)));
            }
            default -> usage(sender, "/cg blacklist add|remove|list [uuid]");
        }
        return true;
    }

    private boolean dupeLog(CommandSender sender, String[] args) {
        if (!perm(sender, "coreguard.admin.dupelog")) return deny(sender);
        sender.sendMessage(Text.color("&eDupe log file support is configured; detailed SQL filtering can be extended in ItemRepository."));
        return true;
    }

    private boolean vanish(CommandSender sender, String[] args) {
        if (!perm(sender, "coreguard.staff.vanish")) return deny(sender);
        Player target;
        if (args.length >= 2) {
            if (!perm(sender, "coreguard.staff.vanish.others")) return deny(sender);
            target = Bukkit.getPlayerExact(args[1]);
        } else {
            if (!(sender instanceof Player p)) return onlyPlayer(sender);
            target = p;
        }
        if (target == null) return notFound(sender);
        boolean enabled = plugin.vanishManager().toggle(target);
        plugin.messages().send(sender, enabled ? "vanish.enabled" : "vanish.disabled", Map.of("player", target.getName()));
        return true;
    }

    private boolean spy(CommandSender sender, String[] args) {
        if (!(sender instanceof Player spy)) return onlyPlayer(sender);
        if (!perm(sender, "coreguard.staff.spy")) return deny(sender);
        if (args.length < 2) return usage(sender, "/cg spy <player>");
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) return notFound(sender);
        plugin.spyManager().toggle(spy.getUniqueId(), target.getUniqueId());
        sender.sendMessage(Text.color("&aSpy toggled for &f" + target.getName()));
        return true;
    }

    private boolean freeze(CommandSender sender, String[] args) {
        if (!perm(sender, "coreguard.staff.freeze")) return deny(sender);
        if (!plugin.getConfig().getBoolean("freeze.enabled", true)) { sender.sendMessage(Text.color("&cFreeze is disabled in config.yml.")); return true; }
        if (args.length < 2) return usage(sender, "/cg freeze <player> [reason]");
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) return notFound(sender);
        String reason = args.length >= 3 ? join(args, 2) : "No reason";
        plugin.freezeManager().freeze(target, reason);
        plugin.messages().send(sender, "freeze.frozen", Map.of("player", target.getName()));
        return true;
    }

    private boolean unfreeze(CommandSender sender, String[] args) {
        if (!perm(sender, "coreguard.staff.freeze")) return deny(sender);
        if (!plugin.getConfig().getBoolean("freeze.enabled", true)) { sender.sendMessage(Text.color("&cFreeze is disabled in config.yml.")); return true; }
        if (args.length < 2) return usage(sender, "/cg unfreeze <player>");
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) return notFound(sender);
        plugin.freezeManager().unfreeze(target);
        plugin.messages().send(sender, "freeze.unfrozen", Map.of("player", target.getName()));
        return true;
    }

    private boolean freezeStick(CommandSender sender) {
        if (!perm(sender, "coreguard.staff.freezestick")) return deny(sender);
        if (!(sender instanceof Player player)) return onlyPlayer(sender);
        if (!plugin.getConfig().getBoolean("freeze.use-stick", false)) {
            sender.sendMessage(Text.color("&cFreeze stick is disabled in config.yml."));
            return true;
        }
        player.getInventory().addItem(plugin.freezeManager().createStick());
        plugin.messages().send(sender, "freeze.stick-given");
        return true;
    }

    private boolean inventory(CommandSender sender, String[] args) {
        if (!perm(sender, "coreguard.staff.inventory")) return deny(sender);
        if (!(sender instanceof Player viewer)) return onlyPlayer(sender);
        if (args.length < 2) return usage(sender, "/cg inventory <player>");
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) return notFound(sender);
        if (!plugin.guiSessionManager().openInventory(viewer, target)) sender.sendMessage(Text.color("&cAnother staff member is already editing this inventory."));
        return true;
    }

    private boolean echest(CommandSender sender, String[] args) {
        if (!perm(sender, "coreguard.staff.echest")) return deny(sender);
        if (!(sender instanceof Player viewer)) return onlyPlayer(sender);
        if (args.length < 2) return usage(sender, "/cg echest <player>");
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) return notFound(sender);
        if (!plugin.guiSessionManager().openEchest(viewer, target)) sender.sendMessage(Text.color("&cAnother staff member is already editing this ender chest."));
        return true;
    }

    private boolean ban(CommandSender sender, String[] args) {
        if (!perm(sender, "coreguard.staff.ban")) return deny(sender);
        if (args.length < 3) return usage(sender, "/cg ban <player> [time] <reason>");
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        warnIfNeverJoined(sender, target, args[1]);
        int reasonStart = 2;
        Duration duration = Duration.ZERO;
        if (TimeParser.looksLikeDuration(args[2])) { duration = TimeParser.parse(args[2]); reasonStart = 3; }
        String reason = reasonStart < args.length ? join(args, reasonStart) : "Banned";
        plugin.punishmentManager().ban(target, duration, reason, sender);
        plugin.messages().send(sender, "punishments.banned", Map.of("player", name(target), "reason", reason));
        return true;
    }

    private boolean unban(CommandSender sender, String[] args) {
        if (!perm(sender, "coreguard.staff.ban")) return deny(sender);
        if (args.length < 2) return usage(sender, "/cg unban <player>");
        plugin.punishmentManager().unban(args[1]);
        plugin.messages().send(sender, "punishments.unbanned", Map.of("player", args[1]));
        return true;
    }

    private boolean banIp(CommandSender sender, String[] args) {
        if (!perm(sender, "coreguard.admin.banip")) return deny(sender);
        if (args.length < 3) return usage(sender, "/cg banip <player/ip> [time] <reason>");
        String ip = args[1];
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target != null && target.getAddress() != null) ip = target.getAddress().getAddress().getHostAddress();
        int reasonStart = 2;
        Duration duration = Duration.ZERO;
        if (TimeParser.looksLikeDuration(args[2])) { duration = TimeParser.parse(args[2]); reasonStart = 3; }
        String reason = reasonStart < args.length ? join(args, reasonStart) : "IP banned";
        plugin.punishmentManager().banIp(ip, duration, reason, sender);
        if (target != null) plugin.punishmentManager().lockAndKick(target, duration, reason);
        sender.sendMessage(Text.color("&aIP banned: &f" + ip));
        return true;
    }

    private boolean unbanIp(CommandSender sender, String[] args) {
        if (!perm(sender, "coreguard.admin.banip")) return deny(sender);
        if (args.length < 2) return usage(sender, "/cg unbanip <ip>");
        plugin.punishmentManager().unbanIp(args[1]);
        sender.sendMessage(Text.color("&aIP unbanned: &f" + args[1]));
        return true;
    }

    private boolean mute(CommandSender sender, String[] args) {
        if (!perm(sender, "coreguard.staff.mute")) return deny(sender);
        if (args.length < 3) return usage(sender, "/cg mute <player> [time] <reason>");
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        warnIfNeverJoined(sender, target, args[1]);
        int reasonStart = 2;
        Duration duration = Duration.ZERO;
        if (TimeParser.looksLikeDuration(args[2])) { duration = TimeParser.parse(args[2]); reasonStart = 3; }
        String reason = reasonStart < args.length ? join(args, reasonStart) : "Muted";
        plugin.punishmentManager().mute(target, duration, reason, sender);
        plugin.messages().send(sender, "punishments.muted", Map.of("player", name(target), "reason", reason));
        return true;
    }

    private boolean unmute(CommandSender sender, String[] args) {
        if (!perm(sender, "coreguard.staff.mute")) return deny(sender);
        if (args.length < 2) return usage(sender, "/cg unmute <player>");
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        plugin.punishmentManager().unmute(target);
        plugin.messages().send(sender, "punishments.unmuted", Map.of("player", name(target)));
        return true;
    }

    private boolean warn(CommandSender sender, String[] args) {
        if (!perm(sender, "coreguard.staff.warn")) return deny(sender);
        if (args.length < 3) return usage(sender, "/cg warn <player> <reason>");
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        String reason = join(args, 2);
        plugin.punishmentManager().warn(target, reason, sender);
        plugin.messages().send(sender, "punishments.warned", Map.of("player", name(target), "reason", reason));
        return true;
    }

    private boolean warnings(CommandSender sender, String[] args) {
        if (!perm(sender, "coreguard.staff.warn")) return deny(sender);
        if (args.length < 2) return usage(sender, "/cg warnings <player>");
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        sender.sendMessage(Text.color("&eWarnings for &f" + name(target)));
        plugin.punishmentRepository().warnings(target.getUniqueId().toString()).forEach(line -> sender.sendMessage(Text.color("&8- &7" + line)));
        return true;
    }

    private boolean clearWarnings(CommandSender sender, String[] args) {
        if (!perm(sender, "coreguard.staff.warn.clear")) return deny(sender);
        if (args.length < 2) return usage(sender, "/cg clearwarnings <player>");
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        plugin.punishmentRepository().clearWarnings(target.getUniqueId().toString());
        sender.sendMessage(Text.color("&aWarnings cleared for &f" + name(target)));
        return true;
    }

    private boolean kick(CommandSender sender, String[] args) {
        if (!perm(sender, "coreguard.staff.kick")) return deny(sender);
        if (args.length < 2) return usage(sender, "/cg kick <player> [reason]");
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) return notFound(sender);
        String reason = args.length >= 3 ? join(args, 2) : "Kicked";
        target.kickPlayer(Text.color(reason));
        plugin.playerRepository().increment(target.getUniqueId().toString(), "kick_count");
        plugin.messages().send(sender, "punishments.kicked", Map.of("player", target.getName(), "reason", reason));
        return true;
    }

    private boolean staffMode(CommandSender sender) {
        if (!perm(sender, "coreguard.staff.staffmode")) return deny(sender);
        if (!(sender instanceof Player player)) return onlyPlayer(sender);
        boolean enabled = plugin.staffModeManager().toggle(player);
        plugin.messages().send(sender, enabled ? "staff.mode-enabled" : "staff.mode-disabled");
        return true;
    }

    private boolean staffChat(CommandSender sender, String[] args) {
        if (!perm(sender, "coreguard.staff.chat")) return deny(sender);
        if (args.length < 2) return usage(sender, "/cg sc <message>");
        String message = join(args, 1);
        String formatted = plugin.messages().msg("staff.chat-format", Map.of("player", sender.getName(), "message", message));
        Bukkit.getOnlinePlayers().stream().filter(p -> p.hasPermission("coreguard.staff.chat")).forEach(p -> p.sendMessage(formatted));
        if (!(sender instanceof Player)) sender.sendMessage(formatted);
        return true;
    }

    private boolean lookup(CommandSender sender, String[] args) {
        if (!perm(sender, "coreguard.staff.lookup")) return deny(sender);
        if (args.length < 2) return usage(sender, "/cg lookup <player>");
        OfflinePlayer off = Bukkit.getOfflinePlayer(args[1]);
        Optional<PlayerRecord> record = plugin.playerRepository().find(off.getUniqueId().toString());
        if (record.isEmpty()) { sender.sendMessage(Text.color("&cNo stored data for this player.")); return true; }
        PlayerRecord r = record.get();
        sender.sendMessage(Text.color("&8&m-----&r &dLookup: &f" + r.name() + " &8&m-----"));
        sender.sendMessage(Text.color("&7UUID: &f" + r.uuid()));
        sender.sendMessage(Text.color("&7IP: &f" + r.ip()));
        sender.sendMessage(Text.color("&7First join: &f" + FORMAT.format(Instant.ofEpochMilli(r.firstJoin()))));
        sender.sendMessage(Text.color("&7Last join: &f" + FORMAT.format(Instant.ofEpochMilli(r.lastJoin()))));
        sender.sendMessage(Text.color("&7Counts: &cban " + r.banCount() + " &6mute " + r.muteCount() + " &ewarn " + r.warnCount() + " &ckick " + r.kickCount()));
        if (plugin.getConfig().getBoolean("lookup.show-alt-accounts-by-ip", true)) {
            sender.sendMessage(Text.color("&7Linked by IP:"));
            plugin.playerRepository().findByIp(r.ip()).forEach(p -> sender.sendMessage(Text.color("&8- &f" + p.name() + " &7" + p.uuid())));
        }
        return true;
    }

    private boolean invBackup(CommandSender sender, String[] args) {
        if (!perm(sender, "coreguard.staff.invbackup")) return deny(sender);
        if (args.length < 2) return usage(sender, "/cg invbackup <player>");
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) return notFound(sender);
        String id = plugin.inventoryBackupManager().backup(target, "manual-command");
        plugin.messages().send(sender, "staff.backup-created", Map.of("id", id));
        return true;
    }

    private boolean invRestore(CommandSender sender, String[] args) {
        if (!perm(sender, "coreguard.staff.invrestore")) return deny(sender);
        if (args.length < 2) return usage(sender, "/cg invrestore <player> [id]");
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) return notFound(sender);
        boolean ok = plugin.inventoryBackupManager().restore(target, args.length >= 3 ? args[2] : null);
        plugin.messages().send(sender, ok ? "staff.backup-restored" : "staff.backup-missing");
        return true;
    }

    private boolean history(CommandSender sender, String[] args) {
        if (!perm(sender, "coreguard.staff.history")) return deny(sender);
        if (args.length < 2) return usage(sender, "/cg history <player>");
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        sender.sendMessage(Text.color("&ePunishment history for &f" + name(target)));
        plugin.punishmentRepository().history(target.getUniqueId().toString()).forEach(line -> sender.sendMessage(Text.color("&8- &7" + line)));
        return true;
    }

    private boolean perm(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        if (permission.startsWith("coreguard.admin.")) return sender.hasPermission("coreguard.admin.*");
        if (permission.startsWith("coreguard.staff.")) return sender.hasPermission("coreguard.staff.*") || sender.hasPermission("coreguard.admin.*");
        return false;
    }

    /**
     * Bukkit.getOfflinePlayer(name) always returns a non-null OfflinePlayer, even for a
     * name that has never connected to this server — it just derives a UUID from the
     * name (offline-mode style) and returns an "offline player" wrapper around it. That
     * means a typo'd name in /cg ban|mute|warn never errors out; it silently punishes a
     * UUID nobody actually owns. This doesn't block the action (staff may legitimately
     * want to pre-emptively ban a name before it ever joins), but it does surface a
     * clear warning so a typo gets noticed immediately instead of "the ban didn't work"
     * being reported as a bug later.
     */
    private void warnIfNeverJoined(CommandSender sender, OfflinePlayer target, String typedName) {
        if (target.hasPlayedBefore() || target.isOnline()) return;
        sender.sendMessage(Text.color("&e[CoreGuard] Warning: &f" + typedName + " &ehas never joined this server. Double-check the spelling — this will still apply to that exact name/UUID."));
    }

    private boolean deny(CommandSender sender) { plugin.messages().send(sender, "no-permission"); return true; }
    private boolean onlyPlayer(CommandSender sender) { plugin.messages().send(sender, "only-player"); return true; }
    private boolean notFound(CommandSender sender) { plugin.messages().send(sender, "player-not-found"); return true; }
    private boolean usage(CommandSender sender, String usage) { plugin.messages().send(sender, "invalid-usage", Map.of("usage", usage)); return true; }
    private String join(String[] args, int start) { return String.join(" ", Arrays.copyOfRange(args, start, args.length)); }
    private int parseInt(String s, int fallback) { try { return Integer.parseInt(s); } catch (Exception e) { return fallback; } }
    private String name(OfflinePlayer player) { return player.getName() == null ? player.getUniqueId().toString() : player.getName(); }
    private void addHelp(CommandSender sender, List<String> lines, String permission, String usage, String desc) { if (perm(sender, permission)) lines.add(Text.color("&d" + usage + " &8- &7" + desc)); }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("reload","help","iteminfo","scan","blacklist","dupelog","vanish","spy","freeze","unfreeze","freezestick","inventory","echest","ban","unban","banip","unbanip","mute","unmute","warn","warnings","clearwarnings","kick","staffmode","sc","lookup","invbackup","invrestore","history"), args[0]);
        }
        if (args.length == 2 && List.of("scan","vanish","spy","freeze","unfreeze","inventory","echest","ban","mute","warn","warnings","clearwarnings","kick","lookup","invbackup","invrestore","history").contains(args[0].toLowerCase(Locale.ROOT))) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("blacklist")) return filter(List.of("add","remove","list"), args[1]);
        return List.of();
    }

    private List<String> filter(List<String> input, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return input.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(p)).toList();
    }
}
