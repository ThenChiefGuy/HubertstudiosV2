package com.HubertStudios.coreguard.staff;

import com.HubertStudios.coreguard.CoreGuard;
import com.HubertStudios.coreguard.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FreezeManager {
    private final CoreGuard plugin;
    private final Map<UUID, String> frozen = new ConcurrentHashMap<>();

    public FreezeManager(CoreGuard plugin) {
        this.plugin = plugin;
    }

    public void freeze(Player target, String reason) {
        frozen.put(target.getUniqueId(), reason == null ? "No reason" : reason);
        String title = plugin.getConfig().getString("freeze.warning-title", "&cYou are frozen");
        String subtitle = plugin.getConfig().getString("freeze.warning-actionbar", "&7Contact staff");
        target.sendTitle(Text.color(title), Text.color(subtitle), 10, 80, 20);
        target.sendActionBar(Text.component(subtitle));
    }

    public void unfreeze(Player target) {
        frozen.remove(target.getUniqueId());
        target.resetTitle();
    }

    public boolean isFrozen(Player target) {
        return frozen.containsKey(target.getUniqueId());
    }

    public String reason(Player target) {
        return frozen.getOrDefault(target.getUniqueId(), "No reason");
    }

    public ItemStack createStick() {
        String materialName = plugin.getConfig().getString("freeze.stick-material", "BLAZE_ROD");
        Material material = Material.matchMaterial(materialName == null ? "BLAZE_ROD" : materialName);
        if (material == null || material.isAir()) {
            plugin.getLogger().warning("Invalid freeze.stick-material '" + materialName + "'; using BLAZE_ROD.");
            material = Material.BLAZE_ROD;
        }
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        int uses = Math.max(1, plugin.getConfig().getInt("freeze.stick-uses", 25));
        meta.setDisplayName(Text.color(plugin.getConfig().getString("freeze.stick-display-name", "&cFreeze Stick")));
        meta.setLore(List.of(Text.color("&7Uses: &f" + uses)));
        meta.getPersistentDataContainer().set(plugin.fingerprintService().staffItemKey(), PersistentDataType.STRING, "FREEZE_STICK");
        meta.getPersistentDataContainer().set(plugin.fingerprintService().freezeStickUsesKey(), PersistentDataType.INTEGER, uses);
        item.setItemMeta(meta);
        return item;
    }
}
