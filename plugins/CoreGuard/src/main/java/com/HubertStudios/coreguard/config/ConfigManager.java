package com.HubertStudios.coreguard.config;

import com.HubertStudios.coreguard.CoreGuard;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

public class ConfigManager {
    private final CoreGuard plugin;
    private FileConfiguration inventoryGui;
    private FileConfiguration echestGui;

    public ConfigManager(CoreGuard plugin) {
        this.plugin = plugin;
    }

    public void load() {
        saveMissing("messages.yml");
        saveMissing("blacklist.yml");
        saveMissing("guis/inventory.yml");
        saveMissing("guis/echest.yml");
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        inventoryGui = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "guis/inventory.yml"));
        echestGui = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "guis/echest.yml"));
    }

    public void reload() {
        load();
    }

    private void saveMissing(String path) {
        File file = new File(plugin.getDataFolder(), path);
        if (!file.exists()) {
            file.getParentFile().mkdirs();
            plugin.saveResource(path, false);
        }
    }

    public FileConfiguration main() {
        return plugin.getConfig();
    }

    public FileConfiguration inventoryGui() {
        return inventoryGui;
    }

    public FileConfiguration echestGui() {
        return echestGui;
    }
}
