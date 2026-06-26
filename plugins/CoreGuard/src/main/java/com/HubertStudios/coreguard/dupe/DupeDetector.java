package com.HubertStudios.coreguard.dupe;

import com.HubertStudios.coreguard.CoreGuard;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.time.Duration;
import java.util.*;

public class DupeDetector {
    private final CoreGuard plugin;
    private final FingerprintService fingerprints;

    public DupeDetector(CoreGuard plugin, FingerprintService fingerprints) {
        this.plugin = plugin;
        this.fingerprints = fingerprints;
    }

    public ScanResult scanPlayer(Player target, boolean writeMissingFingerprints) {
        return scanPlayer(target, writeMissingFingerprints, false);
    }

    public ScanResult scanPlayer(Player target, boolean writeMissingFingerprints, boolean applyAction) {
        ScanResult result = new ScanResult();
        if (!plugin.getConfig().getBoolean("anti-dupe.enabled", true)) return result;

        Map<String, List<String>> unstackableLocations = new HashMap<>();
        Map<String, Integer> batchSeenAmounts = new HashMap<>();
        Map<String, Integer> batchAllowedAmounts = new HashMap<>();
        Set<String> mismatchedUuids = new HashSet<>();

        if (plugin.getConfig().getBoolean("player-scan.scan-player-inventory", true)) {
            scanPlayerInventory(target, unstackableLocations, batchSeenAmounts, batchAllowedAmounts, mismatchedUuids, writeMissingFingerprints);
        }
        if (plugin.getConfig().getBoolean("player-scan.scan-ender-chest", true)) {
            scanInventory("echest", target, target.getEnderChest(), unstackableLocations, batchSeenAmounts, batchAllowedAmounts, mismatchedUuids, writeMissingFingerprints);
        }

        Set<String> badItemUuids = new HashSet<>();
        Set<String> badBatchUuids = new HashSet<>();
        for (Map.Entry<String, List<String>> entry : unstackableLocations.entrySet()) {
            String uuid = entry.getKey();
            if (plugin.blacklistManager().contains(uuid)) {
                result.add("BLACKLISTED UUID " + uuid + " at " + String.join(", ", entry.getValue()));
                badItemUuids.add(uuid);
            }
            if (entry.getValue().size() > 1) {
                result.add("DUPLICATE UUID " + uuid + " at " + String.join(", ", entry.getValue()));
                badItemUuids.add(uuid);
            }
            if (mismatchedUuids.contains(uuid)) {
                result.add("MATERIAL MISMATCH UUID " + uuid + " at " + String.join(", ", entry.getValue()) + " (tracked material differs from a previous sighting — possible NBT tampering)");
                badItemUuids.add(uuid);
            }
        }
        for (Map.Entry<String, Integer> entry : batchSeenAmounts.entrySet()) {
            String batch = entry.getKey();
            int seen = entry.getValue();
            int allowed = batchAllowedAmounts.getOrDefault(batch, seen);
            if (plugin.blacklistManager().contains(batch)) {
                result.add("BLACKLISTED BATCH " + batch + " seen=" + seen);
                badBatchUuids.add(batch);
            }
            if (seen > allowed) {
                result.add("STACKABLE BATCH OVER LIMIT " + batch + " seen=" + seen + " allowed=" + allowed);
                badBatchUuids.add(batch);
            }
        }

        if (!result.clean()) {
            broadcastAlert(target, result);
            if (applyAction) applyAction(target, badItemUuids, badBatchUuids, mismatchedUuids);
        }
        return result;
    }

    private void scanPlayerInventory(Player holder,
                                     Map<String, List<String>> unstackables,
                                     Map<String, Integer> batchSeen,
                                     Map<String, Integer> batchAllowed,
                                     Set<String> mismatched,
                                     boolean writeMissing) {
        PlayerInventory inv = holder.getInventory();
        ItemStack[] storage = inv.getStorageContents();
        boolean storageChanged = scanArray("inv", holder, storage, unstackables, batchSeen, batchAllowed, mismatched, writeMissing);
        if (storageChanged) inv.setStorageContents(storage);

        ItemStack[] armor = inv.getArmorContents();
        boolean armorChanged = scanArray("armor", holder, armor, unstackables, batchSeen, batchAllowed, mismatched, writeMissing);
        if (armorChanged) inv.setArmorContents(armor);

        ItemStack offhand = inv.getItemInOffHand();
        if (offhand != null && !offhand.getType().isAir()) {
            ItemStack[] one = new ItemStack[] { offhand };
            boolean offhandChanged = scanArray("offhand", holder, one, unstackables, batchSeen, batchAllowed, mismatched, writeMissing);
            if (offhandChanged) inv.setItemInOffHand(one[0]);
        }
    }

    private void scanInventory(String prefix, Player holder, Inventory inv,
                               Map<String, List<String>> unstackables,
                               Map<String, Integer> batchSeen,
                               Map<String, Integer> batchAllowed,
                               Set<String> mismatched,
                               boolean writeMissing) {
        ItemStack[] contents = inv.getContents();
        boolean modified = scanArray(prefix, holder, contents, unstackables, batchSeen, batchAllowed, mismatched, writeMissing);
        if (modified) inv.setContents(contents);
    }

    private boolean scanArray(String prefix, Player holder, ItemStack[] contents,
                              Map<String, List<String>> unstackables,
                              Map<String, Integer> batchSeen,
                              Map<String, Integer> batchAllowed,
                              Set<String> mismatched,
                              boolean writeMissing) {
        boolean modified = false;
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType().isAir()) continue;
            if (writeMissing) {
                ItemStack before = item.clone();
                ItemStack fingerprinted = fingerprints.ensureFingerprint(item, "SCAN", holder);
                if (!fingerprinted.isSimilar(before) || fingerprinted.getAmount() != before.getAmount()) modified = true;
                contents[i] = fingerprinted;
                item = fingerprinted;
            }
            String uuid = fingerprints.getItemUuid(item);
            if (uuid != null) {
                unstackables.computeIfAbsent(uuid, k -> new ArrayList<>()).add(prefix + ":" + i + "(" + item.getType() + ")");
                // A fingerprint that was first recorded against a different material than
                // it currently carries is a sign the tag was copied or transplanted onto
                // another item rather than this being a normal sighting of the same item.
                String original = plugin.itemRepository().originalMaterial(uuid);
                if (original != null && !original.equals(item.getType().name())) {
                    mismatched.add(uuid);
                }
            }
            String batch = fingerprints.getBatchUuid(item);
            Integer allowed = fingerprints.getBatchAmount(item);
            if (batch != null) {
                batchSeen.merge(batch, item.getAmount(), Integer::sum);
                if (allowed != null) batchAllowed.put(batch, allowed);
            }
        }
        return modified;
    }

    private void broadcastAlert(Player target, ScanResult result) {
        String alertPerm = plugin.getConfig().getString("anti-dupe.duplicate-detection.alert-permission", "coreguard.alerts.dupe");
        String msg = plugin.messages().msg("dupe.alert", Map.of("player", target.getName(), "uuid", String.valueOf(result.count())));
        for (Player staff : Bukkit.getOnlinePlayers()) if (staff.hasPermission(alertPerm)) staff.sendMessage(msg);
    }

    private void applyAction(Player target, Set<String> badItemUuids, Set<String> badBatchUuids, Set<String> mismatchedUuids) {
        String path = badItemUuids.isEmpty() && !badBatchUuids.isEmpty()
                ? "anti-dupe.stackable-ledger.over-limit-action"
                : "anti-dupe.duplicate-detection.action";
        String action = plugin.getConfig().getString(path, "ALERT_AND_REMOVE").toUpperCase(Locale.ROOT);
        if (action.contains("REMOVE")) removeProblematicItems(target, badItemUuids, badBatchUuids, mismatchedUuids);
        if (action.contains("TEMPBAN")) {
            int mins = plugin.getConfig().getInt("anti-dupe.duplicate-detection.tempban-duration-minutes", 1440);
            plugin.punishmentManager().ban(target, Duration.ofMinutes(mins), "Anti-dupe: duplicate or blacklisted items detected", Bukkit.getConsoleSender());
        }
    }

    private void removeProblematicItems(Player target, Set<String> badItemUuids, Set<String> badBatchUuids, Set<String> mismatchedUuids) {
        Set<String> keptDuplicateUuids = new HashSet<>();
        boolean changedStorage = removeFromPlayerInventory(target.getInventory(), badItemUuids, badBatchUuids, mismatchedUuids, keptDuplicateUuids);
        boolean changedEc = removeFrom(target.getEnderChest(), badItemUuids, badBatchUuids, mismatchedUuids, keptDuplicateUuids);
        if (changedStorage || changedEc) {
            target.sendMessage(plugin.messages().msg("dupe.removed"));
            target.updateInventory();
        }
    }

    private boolean removeFromPlayerInventory(PlayerInventory inv, Set<String> badItemUuids, Set<String> badBatchUuids, Set<String> mismatchedUuids, Set<String> keptDuplicateUuids) {
        ItemStack[] storage = inv.getStorageContents();
        boolean changed = removeFromArray(storage, badItemUuids, badBatchUuids, mismatchedUuids, keptDuplicateUuids);
        if (changed) inv.setStorageContents(storage);

        ItemStack[] armor = inv.getArmorContents();
        boolean armorChanged = removeFromArray(armor, badItemUuids, badBatchUuids, mismatchedUuids, keptDuplicateUuids);
        if (armorChanged) inv.setArmorContents(armor);

        ItemStack[] offhand = new ItemStack[] { inv.getItemInOffHand() };
        boolean offhandChanged = removeFromArray(offhand, badItemUuids, badBatchUuids, mismatchedUuids, keptDuplicateUuids);
        if (offhandChanged) inv.setItemInOffHand(offhand[0] == null ? new ItemStack(Material.AIR) : offhand[0]);
        return changed || armorChanged || offhandChanged;
    }

    private boolean removeFrom(Inventory inv, Set<String> badItemUuids, Set<String> badBatchUuids, Set<String> mismatchedUuids, Set<String> keptDuplicateUuids) {
        ItemStack[] contents = inv.getContents();
        boolean changed = removeFromArray(contents, badItemUuids, badBatchUuids, mismatchedUuids, keptDuplicateUuids);
        if (changed) inv.setContents(contents);
        return changed;
    }

    private boolean removeFromArray(ItemStack[] contents, Set<String> badItemUuids, Set<String> badBatchUuids, Set<String> mismatchedUuids, Set<String> keptDuplicateUuids) {
        boolean changed = false;
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType().isAir()) continue;
            String uuid = fingerprints.getItemUuid(item);
            String batch = fingerprints.getBatchUuid(item);
            boolean remove = false;

            if (uuid != null && plugin.blacklistManager().contains(uuid)) {
                remove = true;
            } else if (uuid != null && mismatchedUuids.contains(uuid)) {
                // A material mismatch is a violation on every copy carrying that
                // fingerprint, not a "too many copies" situation — unlike a duplicate,
                // there is no legitimate single instance to keep, so always remove.
                remove = true;
            } else if (uuid != null && badItemUuids.contains(uuid)) {
                remove = !keptDuplicateUuids.add(uuid);
            }

            if (batch != null && (plugin.blacklistManager().contains(batch) || badBatchUuids.contains(batch))) {
                remove = true;
            }

            if (remove) {
                contents[i] = null;
                changed = true;
            }
        }
        return changed;
    }
}
