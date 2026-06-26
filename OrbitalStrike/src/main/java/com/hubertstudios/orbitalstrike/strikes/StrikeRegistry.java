package com.hubertstudios.orbitalstrike.strikes;

import com.hubertstudios.orbitalstrike.config.PluginConfig;
import com.hubertstudios.orbitalstrike.config.StrikeType;
import org.bukkit.plugin.Plugin;

import java.util.EnumMap;
import java.util.Map;

/**
 * Holds the active StrikeExecutor for each StrikeType. Call {@link #rebuild()}
 * after a config reload so executors pick up new settings (executors hold a
 * direct reference to their typed config object, which is itself replaced
 * wholesale on reload - rebuilding here just re-wires the executors to the
 * new config instances).
 */
public final class StrikeRegistry {

    private final Plugin plugin;
    private final PluginConfig config;
    private final Map<StrikeType, StrikeExecutor> executors = new EnumMap<>(StrikeType.class);

    public StrikeRegistry(Plugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
        rebuild();
    }

    public void rebuild() {
        executors.put(StrikeType.DOG, new DogStrike(plugin, config.dog()));
        executors.put(StrikeType.WITHER, new WitherStrike(plugin, config.wither()));
        executors.put(StrikeType.NUKE, new NukeStrike(plugin, config.nuke()));
        executors.put(StrikeType.STRIKE, new SingleStrike(plugin, config.strike()));
    }

    public StrikeExecutor get(StrikeType type) {
        return executors.get(type);
    }
}
