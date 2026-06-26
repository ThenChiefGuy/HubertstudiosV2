package com.hubertstudios.orbitalstrike.session;

import com.hubertstudios.orbitalstrike.config.RodConfig;
import com.hubertstudios.orbitalstrike.util.TextUtil;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds the single-use targeting rod ItemStack for a given strike type and
 * session. The session id is stamped into the item's PersistentDataContainer
 * so the FishingRodListener can recover which RodSession a given cast/reel
 * event belongs to, without relying on inventory slot position (which the
 * player could change) or item identity comparisons.
 *
 * "Glow" is implemented via a hidden Enchantment (LURE at level 0 looks ugly,
 * so we use a vanity unbreaking + ItemFlag.HIDE_ENCHANTS trick is wrong - we
 * instead rely on Material having a glint via the standard ENCHANTMENT_GLINT
 * component path) - to keep this simple and version-safe, we apply Unbreaking
 * 1 and hide it, which reliably grants the enchanted glint on Paper 1.21+
 * without affecting rod durability behavior in a meaningful way for a
 * single-use, never-actually-fished item.
 */
public final class RodFactory {

    public static final String SESSION_KEY = "orbital_session_id";
    public static final String STRIKE_TYPE_KEY = "orbital_strike_type";

    private final Plugin plugin;

    public RodFactory(Plugin plugin) {
        this.plugin = plugin;
    }

    public ItemStack build(RodConfig rodConfig, String strikeTypeKey, UUID sessionId) {
        ItemStack item = new ItemStack(rodConfig.material());
        ItemMeta meta = item.getItemMeta();

        meta.displayName(TextUtil.colorize(rodConfig.customName()));

        if (rodConfig.loreEnabled() && rodConfig.lore() != null && !rodConfig.lore().isEmpty()) {
            List<net.kyori.adventure.text.Component> loreComponents = new ArrayList<>();
            for (String line : rodConfig.lore()) {
                loreComponents.add(TextUtil.colorize(line));
            }
            meta.lore(loreComponents);
        } else {
            meta.lore(List.of());
        }

        if (rodConfig.glow()) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);

        NamespacedKey sessionKey = new NamespacedKey(plugin, SESSION_KEY);
        meta.getPersistentDataContainer().set(sessionKey, PersistentDataType.STRING, sessionId.toString());

        NamespacedKey typeKey = new NamespacedKey(plugin, STRIKE_TYPE_KEY);
        meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, strikeTypeKey);

        item.setItemMeta(meta);
        return item;
    }

    /** Reads the session id stamped on a rod item, or null if this item isn't one of ours. */
    public UUID readSessionId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        NamespacedKey key = new NamespacedKey(plugin, SESSION_KEY);
        String raw = meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public boolean isOrbitalRod(ItemStack item) {
        return readSessionId(item) != null;
    }
}
