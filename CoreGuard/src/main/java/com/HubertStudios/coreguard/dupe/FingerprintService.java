package com.HubertStudios.coreguard.dupe;

import com.HubertStudios.coreguard.CoreGuard;
import com.HubertStudios.coreguard.repositories.ItemRepository;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Locale;
import java.util.UUID;

public class FingerprintService {
    private final CoreGuard plugin;
    private final ItemRepository repository;
    private final ItemTypeClassifier classifier = new ItemTypeClassifier();
    private final NamespacedKey itemUuidKey;
    private final NamespacedKey batchUuidKey;
    private final NamespacedKey batchAmountKey;
    private final NamespacedKey staffItemKey;
    private final NamespacedKey freezeStickUsesKey;

    public FingerprintService(CoreGuard plugin, ItemRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
        this.itemUuidKey = new NamespacedKey(plugin, "item_uuid");
        this.batchUuidKey = new NamespacedKey(plugin, "batch_uuid");
        this.batchAmountKey = new NamespacedKey(plugin, "batch_amount");
        this.staffItemKey = new NamespacedKey(plugin, "staff_item");
        this.freezeStickUsesKey = new NamespacedKey(plugin, "freeze_stick_uses");
    }

    public ItemStack ensureFingerprint(ItemStack item, String method, Player holder) {
        if (item == null || item.getType().isAir()) return item;
        if (!plugin.getConfig().getBoolean("anti-dupe.enabled", true)) return item;
        if (holder != null && holder.hasPermission("coreguard.bypass.dupe-check")) return item;
        if (hasStaffMarker(item)) return item;
        if (plugin.getConfig().getBoolean("anti-dupe.unstackable-fingerprints.enabled", true) && classifier.isTrackableUnstackable(item)) {
            return ensureUnstackable(item, method, holder);
        }
        if (plugin.getConfig().getBoolean("anti-dupe.stackable-ledger.enabled", false) && item.getType().getMaxStackSize() > 1) {
            return ensureStackableBatch(item, method, holder);
        }
        return item;
    }

    private ItemStack ensureUnstackable(ItemStack item, String method, Player holder) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String uuid = pdc.get(itemUuidKey, PersistentDataType.STRING);
        if (uuid == null || uuid.isBlank()) {
            if (!shouldWriteMissingFingerprint()) return item;
            uuid = UUID.randomUUID().toString();
            pdc.set(itemUuidKey, PersistentDataType.STRING, uuid);
            item.setItemMeta(meta);
        }
        repository.upsertSeen(uuid, item.getType().name(), method, holderUuid(holder), holderName(holder), location(holder), 1);
        return item;
    }

    private ItemStack ensureStackableBatch(ItemStack item, String method, Player holder) {
        if (!isTrackedStackable(item)) return item;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String uuid = pdc.get(batchUuidKey, PersistentDataType.STRING);
        Integer allowed = pdc.get(batchAmountKey, PersistentDataType.INTEGER);
        if (uuid == null || uuid.isBlank()) {
            if (!shouldWriteMissingFingerprint()) return item;
            uuid = UUID.randomUUID().toString();
            allowed = item.getAmount();
            pdc.set(batchUuidKey, PersistentDataType.STRING, uuid);
            pdc.set(batchAmountKey, PersistentDataType.INTEGER, allowed);
            item.setItemMeta(meta);
        }
        repository.upsertSeen(uuid, item.getType().name(), "BATCH_" + method, holderUuid(holder), holderName(holder), location(holder), allowed == null ? item.getAmount() : allowed);
        return item;
    }

    private boolean isTrackedStackable(ItemStack item) {
        boolean customOnly = plugin.getConfig().getBoolean("anti-dupe.stackable-ledger.require-custom-item-meta", true);
        if (customOnly && !item.hasItemMeta()) return false;
        return plugin.getConfig().getStringList("anti-dupe.stackable-ledger.tracked-materials")
                .stream().map(s -> s.toUpperCase(Locale.ROOT)).anyMatch(s -> s.equals(item.getType().name()));
    }

    public String getItemUuid(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(itemUuidKey, PersistentDataType.STRING);
    }

    public String getBatchUuid(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(batchUuidKey, PersistentDataType.STRING);
    }

    public Integer getBatchAmount(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(batchAmountKey, PersistentDataType.INTEGER);
    }

    public NamespacedKey staffItemKey() { return staffItemKey; }
    public NamespacedKey freezeStickUsesKey() { return freezeStickUsesKey; }

    public boolean isStaffItem(ItemStack item, String type) {
        if (item == null || !item.hasItemMeta()) return false;
        String stored = item.getItemMeta().getPersistentDataContainer().get(staffItemKey, PersistentDataType.STRING);
        return type != null && type.equalsIgnoreCase(stored);
    }

    public boolean hasStaffMarker(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(staffItemKey);
    }

    private boolean shouldWriteMissingFingerprint() {
        return plugin.getConfig().getString("anti-dupe.missing-fingerprint.action", "WRITE_NEW")
                .equalsIgnoreCase("WRITE_NEW");
    }

    private String holderUuid(Player holder) { return holder == null ? null : holder.getUniqueId().toString(); }
    private String holderName(Player holder) { return holder == null ? null : holder.getName(); }
    private String location(Player holder) {
        if (holder == null) return "unknown";
        return holder.getWorld().getName() + ":" + holder.getLocation().getBlockX() + "," + holder.getLocation().getBlockY() + "," + holder.getLocation().getBlockZ();
    }
}
