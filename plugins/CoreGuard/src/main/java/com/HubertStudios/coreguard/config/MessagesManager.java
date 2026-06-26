package com.HubertStudios.coreguard.config;

import com.HubertStudios.coreguard.CoreGuard;
import com.HubertStudios.coreguard.util.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MessagesManager {
    private final CoreGuard plugin;
    private FileConfiguration config;

    public MessagesManager(CoreGuard plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), plugin.getConfig().getString("plugin.language-file", "messages.yml"));
        config = YamlConfiguration.loadConfiguration(file);
    }

    public String raw(String key) {
        String prefix = config.getString("prefix", "&8[&dCoreGuard&8] ");
        String value = config.getString(key, key);
        return value.replace("%prefix%", prefix);
    }

    public String msg(String key) {
        return Text.color(raw(key));
    }

    public String msg(String key, Map<String, String> placeholders) {
        String value = raw(key);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            value = value.replace("%" + entry.getKey() + "%", entry.getValue());
        }
        return Text.color(value);
    }

    public List<String> list(String key, Map<String, String> placeholders) {
        List<String> input = config.getStringList(key);
        List<String> out = new ArrayList<>();
        for (String line : input) {
            String value = line.replace("%prefix%", config.getString("prefix", ""));
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                value = value.replace("%" + entry.getKey() + "%", entry.getValue());
            }
            out.add(Text.color(value));
        }
        return out;
    }

    public void send(CommandSender sender, String key) {
        sender.sendMessage(msg(key));
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        sender.sendMessage(msg(key, placeholders));
    }
}
