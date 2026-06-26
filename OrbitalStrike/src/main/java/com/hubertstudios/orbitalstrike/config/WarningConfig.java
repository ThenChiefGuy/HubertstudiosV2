package com.hubertstudios.orbitalstrike.config;

import org.bukkit.configuration.ConfigurationSection;

/** Per-strike-type pre-fire warning broadcast settings. */
public final class WarningConfig {

    private final boolean enabled;
    private final String message;
    private final long leadTimeTicks;
    private final long repeatIntervalTicks;

    public WarningConfig(ConfigurationSection section) {
        if (section == null) {
            this.enabled = false;
            this.message = "";
            this.leadTimeTicks = 0;
            this.repeatIntervalTicks = 0;
            return;
        }
        this.enabled = section.getBoolean("enabled", false);
        this.message = section.getString("message", "");
        this.leadTimeTicks = section.getLong("lead-time-ticks", 0);
        this.repeatIntervalTicks = section.getLong("repeat-interval-ticks", 0);
    }

    public boolean enabled() {
        return enabled;
    }

    public String message() {
        return message;
    }

    public long leadTimeTicks() {
        return leadTimeTicks;
    }

    public long repeatIntervalTicks() {
        return repeatIntervalTicks;
    }
}
