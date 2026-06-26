package com.HubertStudios.coreguard.gui;

import com.HubertStudios.coreguard.CoreGuard;
import com.HubertStudios.coreguard.util.Text;
import com.HubertStudios.coreguard.util.SchedulerUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class GuiSessionManager {
    private final CoreGuard plugin;
    private final Map<UUID, GuiSession> sessions = new HashMap<>();
    private final Map<UUID, UUID> activeInventoryEditors = new HashMap<>();
    private final Map<UUID, UUID> activeEchestEditors = new HashMap<>();

    public GuiSessionManager(CoreGuard plugin) {
        this.plugin = plugin;
    }

    public boolean openInventory(Player viewer, Player target) {
        if (!canOpen(viewer, target, GuiType.INVENTORY)) return false;
        FileConfiguration gui = plugin.configManager().inventoryGui();
        int rows = Math.max(6, gui.getInt("rows", 6));
        String title = Text.color(gui.getString("title", "&8Inventory: %player%").replace("%player%", target.getName()));
        Inventory inv = Bukkit.createInventory(null, rows * 9, title);
        Map<Integer, String> map = new HashMap<>();
        fill(inv, gui);
        map.put(0, "HELMET");
        map.put(1, "CHESTPLATE");
        map.put(2, "LEGGINGS");
        map.put(3, "BOOTS");
        map.put(4, "OFFHAND");
        for (int raw = 9; raw <= 35; raw++) map.put(raw, "INV:" + raw);
        for (int raw = 36; raw <= 44; raw++) map.put(raw, "INV:" + (raw - 36));
        map.put(gui.getInt("buttons.refresh.slot", 6), "REFRESH");
        map.put(gui.getInt("buttons.backup.slot", 7), "BACKUP");
        map.put(gui.getInt("buttons.close.slot", 8), "CLOSE");
        sessions.put(viewer.getUniqueId(), new GuiSession(viewer.getUniqueId(), target.getUniqueId(), GuiType.INVENTORY, inv, map));
        activeInventoryEditors.put(target.getUniqueId(), viewer.getUniqueId());
        render(viewer);
        viewer.openInventory(inv);
        playOpen(viewer, gui.getString("live-edit.sound-on-open", "BLOCK_CHEST_OPEN"));
        return true;
    }

    public boolean openEchest(Player viewer, Player target) {
        if (!canOpen(viewer, target, GuiType.ENDER_CHEST)) return false;
        FileConfiguration gui = plugin.configManager().echestGui();
        int rows = Math.max(4, gui.getInt("rows", 4));
        String title = Text.color(gui.getString("title", "&8Ender Chest: %player%").replace("%player%", target.getName()));
        Inventory inv = Bukkit.createInventory(null, rows * 9, title);
        Map<Integer, String> map = new HashMap<>();
        fill(inv, gui);
        for (int raw = 0; raw < 27; raw++) map.put(raw, "ECHEST:" + raw);
        map.put(gui.getInt("buttons.refresh.slot", 27), "REFRESH");
        map.put(gui.getInt("buttons.close.slot", 35), "CLOSE");
        sessions.put(viewer.getUniqueId(), new GuiSession(viewer.getUniqueId(), target.getUniqueId(), GuiType.ENDER_CHEST, inv, map));
        activeEchestEditors.put(target.getUniqueId(), viewer.getUniqueId());
        render(viewer);
        viewer.openInventory(inv);
        playOpen(viewer, gui.getString("live-edit.sound-on-open", "BLOCK_CHEST_OPEN"));
        return true;
    }

    public Optional<GuiSession> session(Player viewer) {
        return Optional.ofNullable(sessions.get(viewer.getUniqueId()));
    }

    public void close(Player viewer) {
        GuiSession session = sessions.remove(viewer.getUniqueId());
        if (session == null) return;
        activeInventoryEditors.remove(session.target(), viewer.getUniqueId());
        activeEchestEditors.remove(session.target(), viewer.getUniqueId());
    }

    public void closeSessionsFor(UUID uuid) {
        GuiSession asViewer = sessions.remove(uuid);
        if (asViewer != null) {
            activeInventoryEditors.remove(asViewer.target(), uuid);
            activeEchestEditors.remove(asViewer.target(), uuid);
        }
        java.util.List<UUID> viewersToClose = sessions.entrySet().stream()
                .filter(entry -> entry.getValue().target().equals(uuid))
                .map(Map.Entry::getKey)
                .toList();
        for (UUID viewerId : viewersToClose) {
            sessions.remove(viewerId);
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null) {
                viewer.closeInventory();
                viewer.sendMessage(plugin.messages().msg("player-not-found"));
            }
        }
        activeInventoryEditors.entrySet().removeIf(e -> e.getKey().equals(uuid) || e.getValue().equals(uuid));
        activeEchestEditors.entrySet().removeIf(e -> e.getKey().equals(uuid) || e.getValue().equals(uuid));
    }

    public void render(Player viewer) {
        GuiSession session = sessions.get(viewer.getUniqueId());
        if (session == null) return;
        Player target = Bukkit.getPlayer(session.target());
        if (target == null) return;
        if (session.type() == GuiType.INVENTORY) renderInventory(session.inventory(), target);
        else renderEchest(session.inventory(), target);
    }

    public void applyClick(Player viewer, int rawSlot) {
        GuiSession session = sessions.get(viewer.getUniqueId());
        if (session == null) return;
        Player target = Bukkit.getPlayer(session.target());
        if (target == null) return;
        String action = session.slotMap().get(rawSlot);
        if (action == null) return;
        if (action.equals("CLOSE")) {
            viewer.closeInventory();
            return;
        }
        if (action.equals("REFRESH")) {
            render(viewer);
            return;
        }
        if (action.equals("BACKUP")) {
            String id = plugin.inventoryBackupManager().backup(target, "manual-gui");
            viewer.sendMessage(plugin.messages().msg("staff.backup-created", Map.of("id", id)));
            SchedulerUtil.runEntityLater(plugin, viewer, 1L, () -> render(viewer));
            return;
        }
        ItemStack cursor = viewer.getItemOnCursor();
        cursor = cursor == null || cursor.getType().isAir() ? null : cursor.clone();
        if (plugin.fingerprintService().hasStaffMarker(cursor)) {
            viewer.sendMessage(Text.color("&cYou cannot place CoreGuard staff items into a player's inventory."));
            return;
        }
        // Anything the staff member places into a player's inventory through this GUI
        // must be fingerprinted on the way in, the same as a normal pickup/click would
        // be. Without this, the live GUI is a laundering path: an item with no
        // fingerprint yet (or a duplicate of an already-tracked unstackable) could be
        // dropped into a player's inventory and would never have been seen by the
        // normal dupe-detection triggers, so a later scan would treat it as legitimate.
        if (cursor != null) {
            String existingUuid = plugin.fingerprintService().getItemUuid(cursor);
            if (existingUuid != null && plugin.blacklistManager().contains(existingUuid)) {
                viewer.sendMessage(Text.color("&cThat item is blacklisted and cannot be placed into a player's inventory."));
                return;
            }
            cursor = plugin.fingerprintService().ensureFingerprint(cursor, "LIVE_GUI_PLACE", viewer);
        }
        // Re-read the slot right before writing (rather than trusting the last render)
        // so a concurrent change to the target's own inventory between click and write
        // is reflected — the write always acts on the live item, never a stale snapshot.
        ItemStack old = getTargetItem(target, action);
        setTargetItem(target, action, cursor);
        ItemStack returnedToCursor = old == null ? new ItemStack(Material.AIR) : old.clone();
        // The item coming OUT of a player's inventory onto the staff cursor should also
        // be fingerprinted if it somehow lacks one (e.g. older item predating CoreGuard,
        // or a missing-fingerprint edge case) so it's trackable from this point forward.
        if (returnedToCursor.getType() != Material.AIR) {
            returnedToCursor = plugin.fingerprintService().ensureFingerprint(returnedToCursor, "LIVE_GUI_TAKE", viewer);
        }
        viewer.setItemOnCursor(returnedToCursor);
        FileConfiguration logGui = session.type() == GuiType.INVENTORY ? plugin.configManager().inventoryGui() : plugin.configManager().echestGui();
        if (logGui.getBoolean("live-edit.log-actions", true)) {
            plugin.auditRepository().log(viewer, "LIVE_GUI_EDIT", target.getUniqueId().toString(), target.getName(), session.type() + " slot " + action);
        }
        SchedulerUtil.runEntityLater(plugin, viewer, 1L, () -> render(viewer));
    }

    private boolean canOpen(Player viewer, Player target, GuiType type) {
        if (viewer.equals(target)) return true;
        FileConfiguration gui = type == GuiType.INVENTORY ? plugin.configManager().inventoryGui() : plugin.configManager().echestGui();
        if (gui.getBoolean("live-edit.allow-multiple-viewers", false)) return true;
        Map<UUID, UUID> map = type == GuiType.INVENTORY ? activeInventoryEditors : activeEchestEditors;
        UUID current = map.get(target.getUniqueId());
        return current == null || current.equals(viewer.getUniqueId());
    }

    private void renderInventory(Inventory gui, Player target) {
        PlayerInventory inv = target.getInventory();
        gui.setItem(0, inv.getHelmet());
        gui.setItem(1, inv.getChestplate());
        gui.setItem(2, inv.getLeggings());
        gui.setItem(3, inv.getBoots());
        gui.setItem(4, inv.getItemInOffHand());
        for (int raw = 9; raw <= 35; raw++) gui.setItem(raw, inv.getItem(raw));
        for (int raw = 36; raw <= 44; raw++) gui.setItem(raw, inv.getItem(raw - 36));
        applyButtons(gui, plugin.configManager().inventoryGui());
    }

    private void renderEchest(Inventory gui, Player target) {
        for (int raw = 0; raw < 27; raw++) gui.setItem(raw, target.getEnderChest().getItem(raw));
        applyButtons(gui, plugin.configManager().echestGui());
    }

    private ItemStack getTargetItem(Player target, String action) {
        PlayerInventory inv = target.getInventory();
        return switch (action) {
            case "HELMET" -> inv.getHelmet();
            case "CHESTPLATE" -> inv.getChestplate();
            case "LEGGINGS" -> inv.getLeggings();
            case "BOOTS" -> inv.getBoots();
            case "OFFHAND" -> inv.getItemInOffHand();
            default -> {
                if (action.startsWith("INV:")) yield inv.getItem(Integer.parseInt(action.substring(4)));
                if (action.startsWith("ECHEST:")) yield target.getEnderChest().getItem(Integer.parseInt(action.substring(7)));
                yield null;
            }
        };
    }

    private void setTargetItem(Player target, String action, ItemStack item) {
        PlayerInventory inv = target.getInventory();
        switch (action) {
            case "HELMET" -> inv.setHelmet(item);
            case "CHESTPLATE" -> inv.setChestplate(item);
            case "LEGGINGS" -> inv.setLeggings(item);
            case "BOOTS" -> inv.setBoots(item);
            case "OFFHAND" -> inv.setItemInOffHand(item);
            default -> {
                if (action.startsWith("INV:")) inv.setItem(Integer.parseInt(action.substring(4)), item);
                else if (action.startsWith("ECHEST:")) target.getEnderChest().setItem(Integer.parseInt(action.substring(7)), item);
            }
        }
        target.updateInventory();
    }

    private void fill(Inventory inv, FileConfiguration gui) {
        if (!gui.getBoolean("filler.enabled", true)) return;
        Material mat = Material.matchMaterial(gui.getString("filler.material", "BLACK_STAINED_GLASS_PANE"));
        if (mat == null || mat.isAir()) return;
        ItemStack filler = named(mat, gui.getString("filler.name", " "));
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
    }

    private void applyButtons(Inventory inv, FileConfiguration gui) {
        setButton(inv, gui, "refresh");
        setButton(inv, gui, "backup");
        setButton(inv, gui, "close");
    }

    private void setButton(Inventory inv, FileConfiguration gui, String key) {
        if (!gui.contains("buttons." + key + ".slot")) return;
        int slot = gui.getInt("buttons." + key + ".slot");
        if (slot < 0 || slot >= inv.getSize()) return;
        Material mat = Material.matchMaterial(gui.getString("buttons." + key + ".material", "STONE"));
        if (mat == null || mat.isAir()) return;
        inv.setItem(slot, named(mat, gui.getString("buttons." + key + ".name", key)));
    }

    private ItemStack named(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(Text.color(name));
        item.setItemMeta(meta);
        return item;
    }

    private void playOpen(Player viewer, String soundName) {
        try {
            viewer.playSound(viewer.getLocation(), Sound.valueOf(soundName), 0.8f, 1.0f);
        } catch (Exception ignored) {}
    }
}
