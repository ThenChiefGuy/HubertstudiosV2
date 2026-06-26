package com.HubertStudios.coreguard.staff;

import com.HubertStudios.coreguard.CoreGuard;
import com.HubertStudios.coreguard.util.ItemSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

public class InventoryBackupManager {
    private final CoreGuard plugin;

    public InventoryBackupManager(CoreGuard plugin) {
        this.plugin = plugin;
    }

    public String backup(Player player, String reason) {
        String safeReason = reason == null ? "unknown" : reason.replaceAll("[^A-Za-z0-9_\\-]", "_");
        String id = System.currentTimeMillis() + "-" + safeReason;
        File folder = new File(plugin.getDataFolder(), "data/backups/" + player.getUniqueId());
        if (!folder.exists() && !folder.mkdirs()) plugin.getLogger().warning("Could not create backup directory: " + folder.getPath());
        File file = new File(folder, id + ".yml");

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("player", player.getName());
        yaml.set("uuid", player.getUniqueId().toString());
        yaml.set("reason", reason);
        yaml.set("created-at", System.currentTimeMillis());
        ItemSerializer.saveArray(yaml, "storage", player.getInventory().getStorageContents());
        ItemSerializer.saveArray(yaml, "armor", player.getInventory().getArmorContents());
        yaml.set("offhand", cloneOrAir(player.getInventory().getItemInOffHand()));

        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save inventory backup: " + e.getMessage());
        }
        cleanup(player);
        return id;
    }

    public boolean restore(Player player, String idOrNull) {
        File folder = new File(plugin.getDataFolder(), "data/backups/" + player.getUniqueId());
        File file = idOrNull == null ? latest(folder) : new File(folder, idOrNull.endsWith(".yml") ? idOrNull : idOrNull + ".yml");
        if (file == null || !file.exists()) return false;

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        if (yaml.contains("storage")) {
            player.getInventory().setStorageContents(ItemSerializer.loadArray(yaml, "storage", player.getInventory().getStorageContents().length));
        } else {
            // Backward compatibility with early CoreGuard backups.
            player.getInventory().setContents(ItemSerializer.loadArray(yaml, "contents", player.getInventory().getSize()));
        }
        player.getInventory().setArmorContents(ItemSerializer.loadArray(yaml, "armor", 4));
        ItemStack offhand = yaml.getItemStack("offhand");
        player.getInventory().setItemInOffHand(offhand == null ? new ItemStack(Material.AIR) : offhand);
        player.updateInventory();
        return true;
    }

    private File latest(File folder) {
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) return null;
        return Arrays.stream(files).max(Comparator.comparing(File::getName).thenComparingLong(File::lastModified)).orElse(null);
    }

    private void cleanup(Player player) {
        int max = plugin.getConfig().getInt("inventory-backups.max-backups-per-player", 10);
        if (max <= 0) return;
        File folder = new File(plugin.getDataFolder(), "data/backups/" + player.getUniqueId());
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length <= max) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (int i = 0; i < files.length - max; i++) {
            if (!files[i].delete()) plugin.getLogger().warning("Could not delete old backup: " + files[i].getName());
        }
    }

    private ItemStack cloneOrAir(ItemStack item) {
        return item == null ? new ItemStack(Material.AIR) : item.clone();
    }
}
