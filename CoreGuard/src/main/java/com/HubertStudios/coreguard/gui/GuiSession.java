package com.HubertStudios.coreguard.gui;

import org.bukkit.inventory.Inventory;

import java.util.Map;
import java.util.UUID;

public record GuiSession(UUID viewer, UUID target, GuiType type, Inventory inventory, Map<Integer, String> slotMap) {}
