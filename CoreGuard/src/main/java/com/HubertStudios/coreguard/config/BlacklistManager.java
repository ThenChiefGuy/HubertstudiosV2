package com.HubertStudios.coreguard.config;

import com.HubertStudios.coreguard.CoreGuard;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class BlacklistManager {
    private final CoreGuard plugin;
    private final Set<String> blacklisted = new LinkedHashSet<>();
    private File file;
    private FileConfiguration config;

    public BlacklistManager(CoreGuard plugin) {
        this.plugin = plugin;
    }

    public void load() {
        file = new File(plugin.getDataFolder(), "blacklist.yml");
        config = YamlConfiguration.loadConfiguration(file);
        blacklisted.clear();
        blacklisted.addAll(config.getStringList("blacklisted-uuids"));
    }

    public boolean add(String uuid) {
        boolean changed = blacklisted.add(uuid);
        save();
        return changed;
    }

    public boolean remove(String uuid) {
        boolean changed = blacklisted.remove(uuid);
        save();
        return changed;
    }

    public boolean contains(String uuid) {
        return blacklisted.contains(uuid);
    }

    public Set<String> all() {
        return Set.copyOf(blacklisted);
    }

    private void save() {
        config.set("blacklisted-uuids", List.copyOf(blacklisted));
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save blacklist.yml: " + e.getMessage());
        }
    }
}
