package com.hubertstudios.orbitalstrike;

import com.hubertstudios.orbitalstrike.commands.OrbitalCommand;
import com.hubertstudios.orbitalstrike.config.PluginConfig;
import com.hubertstudios.orbitalstrike.listeners.ExplosionListener;
import com.hubertstudios.orbitalstrike.listeners.FishingRodListener;
import com.hubertstudios.orbitalstrike.session.CooldownService;
import com.hubertstudios.orbitalstrike.session.RodFactory;
import com.hubertstudios.orbitalstrike.session.RodIssuer;
import com.hubertstudios.orbitalstrike.session.SessionManager;
import com.hubertstudios.orbitalstrike.strikes.StrikeRegistry;
import com.hubertstudios.orbitalstrike.targeting.TargetingService;
import com.hubertstudios.license.License;
import com.hubertstudios.license.LicenseGate;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class OrbitalStrikePlugin extends JavaPlugin {

    // HubertStudios backend — do not change.
    private static final String WORKER_URL = "https://api.gg69nah.workers.dev";
    // Paste the public key from backend/scripts/setup-tool.html before distributing.
    private static final String PUBLIC_KEY_PEM =
            "-----BEGIN PUBLIC KEY-----\n" +
            "PASTE_PUBLIC_KEY_HERE\n" +
            "-----END PUBLIC KEY-----";

    private PluginConfig pluginConfig;
    private SessionManager sessionManager;
    private RodFactory rodFactory;
    private TargetingService targetingService;
    private StrikeRegistry strikeRegistry;
    private RodIssuer rodIssuer;
    private CooldownService cooldownService;
    private LicenseGate licenseGate;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResourceIfMissing("license.yml");

        if (!startLicenseGate()) {
            return;
        }

        this.pluginConfig = new PluginConfig(this);
        this.sessionManager = new SessionManager();
        this.rodFactory = new RodFactory(this);
        this.targetingService = new TargetingService(pluginConfig);
        this.strikeRegistry = new StrikeRegistry(this, pluginConfig);
        this.rodIssuer = new RodIssuer(this, pluginConfig, sessionManager, rodFactory);
        this.cooldownService = new CooldownService(pluginConfig, sessionManager);

        getServer().getPluginManager().registerEvents(
                new FishingRodListener(this, pluginConfig, sessionManager, rodFactory, targetingService, strikeRegistry),
                this);
        getServer().getPluginManager().registerEvents(new ExplosionListener(this, pluginConfig), this);

        OrbitalCommand orbitalCommand = new OrbitalCommand(this, pluginConfig, sessionManager, rodIssuer, strikeRegistry, cooldownService);
        var command = getCommand("orbital");
        if (command != null) {
            command.setExecutor(orbitalCommand);
            command.setTabCompleter(orbitalCommand);
        } else {
            getLogger().severe("Failed to register /orbital command - check paper-plugin.yml.");
        }

        getLogger().info("OrbitalStrike enabled.");
    }

    @Override
    public void onDisable() {
        if (licenseGate != null) {
            licenseGate.shutdown();
        }
        getLogger().info("OrbitalStrike disabled.");
    }

    private boolean startLicenseGate() {
        try {
            LicenseGate.Config gateConfig = LicenseGate.Config.builder()
                    .workerUrl(WORKER_URL)
                    .publicKeyPem(PUBLIC_KEY_PEM)
                    .recheckIntervalMinutes(60L)
                    .serverInfoSupplier(() -> new License.ServerInfo(
                            getServer().getIp(),
                            getServer().getPort(),
                            getServer().getName(),
                            null, "", "", ""
                    ))
                    .build();

            licenseGate = new LicenseGate(this, gateConfig);
            if (!licenseGate.validateBlocking()) {
                return false;
            }
            licenseGate.startPeriodicChecks();
            return true;
        } catch (Exception exception) {
            getLogger().severe("OrbitalStrike license error: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }
    }

    private void saveResourceIfMissing(String resourcePath) {
        File output = new File(getDataFolder(), resourcePath);
        if (!output.exists()) {
            File parent = output.getParentFile();
            if (parent != null) parent.mkdirs();
            saveResource(resourcePath, false);
        }
    }
}
