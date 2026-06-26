package com.HubertStudios.coreguard.util;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

public final class ItemSerializer {
    private ItemSerializer() {}

    public static void saveArray(ConfigurationSection section, String key, ItemStack[] items) {
        section.set(key, items);
    }

    public static ItemStack[] loadArray(ConfigurationSection section, String key, int size) {
        java.util.List<?> list = section.getList(key);
        ItemStack[] result = new ItemStack[size];
        if (list == null) return result;
        for (int i = 0; i < Math.min(size, list.size()); i++) {
            Object o = list.get(i);
            if (o instanceof ItemStack item) result[i] = item;
        }
        return result;
    }
}
