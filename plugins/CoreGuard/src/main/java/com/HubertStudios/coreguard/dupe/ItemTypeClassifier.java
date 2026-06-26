package com.HubertStudios.coreguard.dupe;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Set;

public class ItemTypeClassifier {
    private static final Set<String> SMART_NAMES = Set.of(
            "SWORD", "AXE", "PICKAXE", "SHOVEL", "HOE", "BOW", "CROSSBOW", "TRIDENT",
            "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS", "ELYTRA", "SHIELD",
            "FISHING_ROD", "SHEARS", "FLINT_AND_STEEL", "BRUSH", "MACE"
    );

    public boolean isTrackableUnstackable(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        Material material = item.getType();
        if (material.getMaxStackSize() != 1) return false;
        String name = material.name().toUpperCase(Locale.ROOT);
        for (String token : SMART_NAMES) {
            if (name.contains(token)) return true;
        }
        return item.hasItemMeta();
    }
}
