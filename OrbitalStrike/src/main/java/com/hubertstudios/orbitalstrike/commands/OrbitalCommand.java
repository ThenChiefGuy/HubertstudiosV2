package com.hubertstudios.orbitalstrike.commands;

import com.hubertstudios.orbitalstrike.config.BaseStrikeConfig;
import com.hubertstudios.orbitalstrike.config.PluginConfig;
import com.hubertstudios.orbitalstrike.config.StrikeType;
import com.hubertstudios.orbitalstrike.session.CooldownService;
import com.hubertstudios.orbitalstrike.session.RodIssuer;
import com.hubertstudios.orbitalstrike.session.SessionManager;
import com.hubertstudios.orbitalstrike.strikes.StrikeExecutor;
import com.hubertstudios.orbitalstrike.strikes.StrikeRegistry;
import com.hubertstudios.orbitalstrike.util.FoliaUtil;
import com.hubertstudios.orbitalstrike.util.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Handles all /orbital subcommands:
 *   /orbital <dog|wither|nuke|strike>                       - normal aim/fire rod for self
 *   /orbital <dog|wither|nuke|strike> <amount> [player]      - give rods
 *   /orbital bomb <wither|nuke|strike> <player> [amount=1]   - instant, no rod, no dog
 *   /orbital reload                                          - reload config.yml
 */
public final class OrbitalCommand implements CommandExecutor, TabCompleter {

    private final Plugin plugin;
    private final PluginConfig config;
    private final SessionManager sessions;
    private final RodIssuer rodIssuer;
    private final StrikeRegistry strikes;
    private final CooldownService cooldowns;

    public OrbitalCommand(Plugin plugin, PluginConfig config, SessionManager sessions,
                           RodIssuer rodIssuer, StrikeRegistry strikes, CooldownService cooldowns) {
        this.plugin = plugin;
        this.config = config;
        this.sessions = sessions;
        this.rodIssuer = rodIssuer;
        this.strikes = strikes;
        this.cooldowns = cooldowns;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("reload")) {
            return handleReload(sender);
        }

        if (sub.equals("bomb")) {
            return handleBomb(sender, args);
        }

        var typeOpt = StrikeType.fromString(sub);
        if (typeOpt.isEmpty()) {
            sendUsage(sender);
            return true;
        }
        StrikeType type = typeOpt.get();

        if (args.length == 1) {
            return handleSelfCast(sender, type);
        }

        return handleGive(sender, type, args);
    }

    // ---------------------------------------------------------------
    // /orbital <type>   (self-cast: give the caster a rod for that type)
    // ---------------------------------------------------------------
    private boolean handleSelfCast(CommandSender sender, StrikeType type) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }
        BaseStrikeConfig typeConfig = config.forType(type);

        if (!player.hasPermission("orbitalstrike.use") || !player.hasPermission(typeConfig.permission())) {
            player.sendMessage(TextUtil.prefixed(config.prefix(), "&cYou don't have permission to use this."));
            return true;
        }

        if (!player.hasPermission("orbitalstrike.bypass.cooldown")) {
            long remaining = cooldowns.remainingSeconds(player, type);
            if (remaining > 0) {
                player.sendMessage(TextUtil.prefixed(config.prefix(), "&cWait " + remaining + "s before using this again."));
                return true;
            }
        }

        rodIssuer.issue(player, type, typeConfig);
        if (!player.hasPermission("orbitalstrike.bypass.cooldown")) {
            cooldowns.apply(player, type);
        }
        player.sendMessage(TextUtil.prefixed(config.prefix(), "&aTargeting rod issued. Reel to aim, reel again to fire."));
        return true;
    }

    // ---------------------------------------------------------------
    // /orbital <type> <amount> [player]
    // ---------------------------------------------------------------
    private boolean handleGive(CommandSender sender, StrikeType type, String[] args) {
        if (!sender.hasPermission("orbitalstrike.give")) {
            sender.sendMessage("You don't have permission to give targeting rods.");
            return true;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[1]);
        } catch (NumberFormatException ex) {
            sender.sendMessage("Amount must be a whole number.");
            return true;
        }
        if (amount < 1 || amount > 64) {
            sender.sendMessage("Amount must be between 1 and 64.");
            return true;
        }

        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage("Player '" + args[2] + "' is not online.");
                return true;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            sender.sendMessage("Console must specify a player.");
            return true;
        }

        BaseStrikeConfig typeConfig = config.forType(type);
        for (int i = 0; i < amount; i++) {
            rodIssuer.issue(target, type, typeConfig);
        }
        sender.sendMessage("Gave " + amount + " " + type.key() + " targeting rod(s) to " + target.getName() + ".");
        if (!sender.equals(target)) {
            target.sendMessage(TextUtil.prefixed(config.prefix(),
                    "&aYou received " + amount + " " + type.key() + " targeting rod(s)."));
        }
        return true;
    }

    // ---------------------------------------------------------------
    // /orbital bomb <wither|nuke|strike> <player> [amount=1]
    // ---------------------------------------------------------------
    private boolean handleBomb(CommandSender sender, String[] args) {
        if (!sender.hasPermission("orbitalstrike.bomb")) {
            sender.sendMessage("You don't have permission to use /orbital bomb.");
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage("Usage: /orbital bomb <wither|nuke|strike> <player> [amount]");
            return true;
        }

        var typeOpt = StrikeType.fromString(args[1]);
        if (typeOpt.isEmpty()) {
            sender.sendMessage("Unknown strike type '" + args[1] + "'.");
            return true;
        }
        StrikeType type = typeOpt.get();
        BaseStrikeConfig typeConfig = config.forType(type);

        if (type == StrikeType.DOG || !typeConfig.allowBomb()) {
            sender.sendMessage("The 'dog' strike type cannot be used with /orbital bomb.");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            sender.sendMessage("Player '" + args[2] + "' is not online.");
            return true;
        }

        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Integer.parseInt(args[3]);
            } catch (NumberFormatException ex) {
                sender.sendMessage("Amount must be a whole number.");
                return true;
            }
            if (amount < 1 || amount > 64) {
                sender.sendMessage("Amount must be between 1 and 64.");
                return true;
            }
        }

        // All repetitions target the player's location at the moment the
        // command was invoked, per the confirmed design (no live tracking).
        Location frozenTarget = target.getLocation().clone();
        StrikeExecutor executor = strikes.get(type);
        long interval = typeConfig.bombIntervalTicks();

        fireBombSequence(executor, frozenTarget, target, amount, interval, 0);

        sender.sendMessage("Firing " + amount + "x " + type.key() + " bomb strike(s) on " + target.getName() + ".");
        return true;
    }

    private void fireBombSequence(StrikeExecutor executor, Location frozenTarget, Player attributedSource,
                                   int totalAmount, long intervalTicks, int iteration) {
        executor.execute(frozenTarget, attributedSource, true);
        if (iteration + 1 < totalAmount) {
            FoliaUtil.runAtLocationLater(plugin, frozenTarget, intervalTicks,
                    () -> fireBombSequence(executor, frozenTarget, attributedSource, totalAmount, intervalTicks, iteration + 1));
        }
    }

    // ---------------------------------------------------------------
    // /orbital reload
    // ---------------------------------------------------------------
    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("orbitalstrike.admin")) {
            sender.sendMessage("You don't have permission to reload OrbitalStrike.");
            return true;
        }
        config.reload();
        strikes.rebuild();
        sender.sendMessage("OrbitalStrike configuration reloaded.");
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("Usage:");
        sender.sendMessage("  /orbital <dog|wither|nuke|strike>");
        sender.sendMessage("  /orbital <dog|wither|nuke|strike> <amount> [player]");
        sender.sendMessage("  /orbital bomb <wither|nuke|strike> <player> [amount]");
        sender.sendMessage("  /orbital reload");
    }

    // ---------------------------------------------------------------
    // Tab completion
    // ---------------------------------------------------------------
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("dog", "wither", "nuke", "strike", "bomb", "reload"));
            return filterStartsWith(options, args[0]);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("bomb")) {
            if (args.length == 2) {
                return filterStartsWith(List.of("wither", "nuke", "strike"), args[1]);
            }
            if (args.length == 3) {
                return filterStartsWith(onlinePlayerNames(), args[2]);
            }
            if (args.length == 4) {
                return filterStartsWith(List.of("1", "2", "3", "5", "10"), args[3]);
            }
            return List.of();
        }

        var typeOpt = StrikeType.fromString(sub);
        if (typeOpt.isPresent()) {
            if (args.length == 2) {
                return filterStartsWith(List.of("1", "2", "3", "5", "10"), args[1]);
            }
            if (args.length == 3) {
                return filterStartsWith(onlinePlayerNames(), args[2]);
            }
        }

        return List.of();
    }

    private List<String> onlinePlayerNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
    }

    private List<String> filterStartsWith(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(lower)).collect(Collectors.toList());
    }
}
