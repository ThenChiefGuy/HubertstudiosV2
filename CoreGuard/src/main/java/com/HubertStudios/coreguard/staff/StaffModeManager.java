package com.HubertStudios.coreguard.staff;

import com.HubertStudios.coreguard.CoreGuard;
import com.HubertStudios.coreguard.util.ItemSerializer;
import com.HubertStudios.coreguard.util.Text;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Staff mode swaps a staff member's real inventory out for a god-mode admin loadout.
 * The swapped-out inventory is the player's actual items, so it is kept both in memory
 * (for fast access during normal play) and mirrored to disk immediately on enable. If
 * the server crashes or is killed while someone is in staff mode, the in-memory map is
 * lost, but the on-disk snapshot lets the items be recovered on the next startup instead
 * of being silently destroyed.
 */
public class StaffModeManager {
    private final CoreGuard plugin;
    private final Map<UUID, ItemStack[]> savedStorage = new ConcurrentHashMap<>();
    private final Map<UUID, ItemStack[]> savedArmor = new ConcurrentHashMap<>();
    private final Map<UUID, ItemStack> savedOffhand = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> savedFlight = new ConcurrentHashMap<>();

    public StaffModeManager(CoreGuard plugin) {
        this.plugin = plugin;
    }

    /**
     * On plugin enable, restores anyone whose staff-mode snapshot is still on disk but
     * who isn't tracked in memory (i.e. the server stopped uncleanly while they were in
     * staff mode). If they're online already (rare, but possible on a /reload of some
     * other plugin that re-triggers PlayerJoinEvent oddly) their inventory is restored
     * immediately; otherwise the snapshot is left on disk and applied the next time they
     * join, via restoreFromDiskOnJoin.
     */
    public void recoverOrphanedSnapshots() {
        File folder = snapshotFolder();
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return;
        for (File file : files) {
            String fileName = file.getName();
            String uuidPart = fileName.substring(0, fileName.length() - ".yml".length());
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidPart);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            plugin.getLogger().warning("Found an orphaned staff-mode snapshot for " + uuid + " (server likely stopped uncleanly while they were in staff mode). It will be restored automatically when they next join.");
        }
    }

    /** Applies a pending on-disk staff-mode snapshot for a player who just joined, if one exists. Call from PlayerJoinEvent. */
    public void restoreFromDiskOnJoin(Player player) {
        if (isEnabled(player)) return; // already tracked in memory, nothing to recover
        File file = snapshotFile(player.getUniqueId());
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        PlayerInventory inv = player.getInventory();
        ItemStack[] storage = ItemSerializer.loadArray(yaml, "storage", inv.getStorageContents().length);
        ItemStack[] armor = ItemSerializer.loadArray(yaml, "armor", 4);
        ItemStack offhand = yaml.getItemStack("offhand");
        boolean flight = yaml.getBoolean("flight", false);

        inv.setStorageContents(storage);
        inv.setArmorContents(armor);
        inv.setItemInOffHand(offhand == null ? new ItemStack(Material.AIR) : offhand);
        player.setAllowFlight(flight);
        player.setInvulnerable(false);
        player.updateInventory();
        if (!file.delete()) plugin.getLogger().warning("Could not delete recovered staff-mode snapshot: " + file.getPath());
        player.sendMessage(Text.color("&aYour real inventory was restored from a saved staff-mode snapshot (the server stopped while you were in staff mode)."));
    }

    public boolean toggle(Player player) {
        if (isEnabled(player)) {
            disable(player);
            return false;
        }
        enable(player);
        return true;
    }

    public void enable(Player player) {
        if (isEnabled(player)) return;
        UUID uuid = player.getUniqueId();
        PlayerInventory inv = player.getInventory();
        ItemStack[] storage = cloneArray(inv.getStorageContents());
        ItemStack[] armor = cloneArray(inv.getArmorContents());
        ItemStack offhand = cloneItem(inv.getItemInOffHand());
        boolean flight = player.getAllowFlight();

        savedStorage.put(uuid, storage);
        savedArmor.put(uuid, armor);
        savedOffhand.put(uuid, offhand);
        savedFlight.put(uuid, flight);
        writeSnapshotToDisk(uuid, storage, armor, offhand, flight);

        inv.clear();
        inv.setArmorContents(new ItemStack[4]);
        inv.setItemInOffHand(new ItemStack(Material.AIR));
        player.setAllowFlight(plugin.getConfig().getBoolean("staff-mode.flight-enabled", true));
        player.setInvulnerable(plugin.getConfig().getBoolean("staff-mode.god-mode-enabled", true));
        giveHotbar(player);

        if (plugin.getConfig().getBoolean("staff-mode.enable-vanish-when-staff-mode-starts", true)) {
            plugin.vanishManager().enable(player);
        }
        player.updateInventory();
    }

    public void disable(Player player) {
        restore(player, true);
    }

    public void cleanupOnQuit(Player player) {
        restore(player, false);
    }

    private void restore(Player player, boolean updateInventory) {
        if (!isEnabled(player)) return;
        UUID uuid = player.getUniqueId();
        PlayerInventory inv = player.getInventory();

        ItemStack[] storage = savedStorage.remove(uuid);
        ItemStack[] armor = savedArmor.remove(uuid);
        ItemStack offhand = savedOffhand.remove(uuid);
        Boolean flight = savedFlight.remove(uuid);

        if (storage != null) inv.setStorageContents(cloneArray(storage));
        if (armor != null) inv.setArmorContents(cloneArray(armor));
        inv.setItemInOffHand(offhand == null ? new ItemStack(Material.AIR) : cloneItem(offhand));
        if (flight != null) player.setAllowFlight(flight);
        player.setInvulnerable(false);
        if (updateInventory) player.updateInventory();
        deleteSnapshotFromDisk(uuid);
    }

    public boolean isEnabled(Player player) {
        return savedStorage.containsKey(player.getUniqueId());
    }

    private File snapshotFolder() {
        File folder = new File(plugin.getDataFolder(), "data/staffmode-snapshots");
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().warning("Could not create staff-mode snapshot directory: " + folder.getPath());
        }
        return folder;
    }

    private File snapshotFile(UUID uuid) {
        return new File(snapshotFolder(), uuid + ".yml");
    }

    private void writeSnapshotToDisk(UUID uuid, ItemStack[] storage, ItemStack[] armor, ItemStack offhand, boolean flight) {
        YamlConfiguration yaml = new YamlConfiguration();
        ItemSerializer.saveArray(yaml, "storage", storage);
        ItemSerializer.saveArray(yaml, "armor", armor);
        yaml.set("offhand", offhand == null ? new ItemStack(Material.AIR) : offhand);
        yaml.set("flight", flight);
        yaml.set("saved-at", System.currentTimeMillis());
        try {
            yaml.save(snapshotFile(uuid));
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save staff-mode snapshot for " + uuid + ": " + e.getMessage());
        }
    }

    private void deleteSnapshotFromDisk(UUID uuid) {
        File file = snapshotFile(uuid);
        if (file.exists() && !file.delete()) {
            plugin.getLogger().warning("Could not delete staff-mode snapshot: " + file.getPath());
        }
    }

    private void giveHotbar(Player player) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("staff-mode.hotbar-loadout");
        if (section == null) {
            setStaffItem(player, 0, "INVENTORY_GUI_ITEM");
            setStaffItem(player, 1, "VANISH_TOGGLE");
            setStaffItem(player, 2, "FREEZE_STICK");
            setStaffItem(player, 3, "RANDOM_TELEPORT");
            setStaffItem(player, 8, "STAFFMODE_DISABLE");
            return;
        }
        for (String key : section.getKeys(false)) {
            if (!key.startsWith("slot-")) continue;
            try {
                int slot = Integer.parseInt(key.substring(5));
                if (slot >= 0 && slot <= 8) setStaffItem(player, slot, section.getString(key, ""));
            } catch (NumberFormatException ignored) {}
        }
    }

    private void setStaffItem(Player player, int slot, String id) {
        ItemStack item = itemFromId(id);
        if (item != null) player.getInventory().setItem(slot, item);
    }

    private ItemStack itemFromId(String id) {
        if (id == null) return null;
        return switch (id.toUpperCase()) {
            case "INVENTORY_GUI_ITEM" -> staffItem(Material.CHEST, "&bOpen Inventory", "INVENTORY_GUI_ITEM", "&7Right-click a player to open their inventory");
            case "VANISH_TOGGLE" -> staffItem(Material.ENDER_EYE, "&dToggle Vanish", "VANISH_TOGGLE", "&7Click to toggle vanish mode");
            case "FREEZE_STICK" -> plugin.freezeManager().createStick();
            case "RANDOM_TELEPORT" -> staffItem(Material.COMPASS, "&eRandom Teleport", "RANDOM_TELEPORT", "&7Click to teleport to a random player");
            case "STAFFMODE_DISABLE" -> staffItem(Material.BARRIER, "&cDisable Staff Mode", "STAFFMODE_DISABLE", "&7Click to exit staff mode");
            default -> null;
        };
    }

    private ItemStack staffItem(Material material, String name, String type, String lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(Text.color(name));
        meta.setLore(List.of(Text.color(lore)));
        meta.getPersistentDataContainer().set(plugin.fingerprintService().staffItemKey(), PersistentDataType.STRING, type);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack[] cloneArray(ItemStack[] input) {
        ItemStack[] out = new ItemStack[input.length];
        for (int i = 0; i < input.length; i++) out[i] = cloneItem(input[i]);
        return out;
    }

    private ItemStack cloneItem(ItemStack item) {
        return item == null ? null : item.clone();
    }
}
