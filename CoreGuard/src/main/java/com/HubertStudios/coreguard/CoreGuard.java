package com.HubertStudios.coreguard;

import com.HubertStudios.coreguard.commands.CoreGuardCommand;
import com.HubertStudios.coreguard.config.BlacklistManager;
import com.HubertStudios.coreguard.config.ConfigManager;
import com.HubertStudios.coreguard.config.MessagesManager;
import com.HubertStudios.coreguard.database.DatabaseManager;
import com.HubertStudios.coreguard.dupe.DupeDetector;
import com.HubertStudios.coreguard.dupe.FingerprintService;
import com.HubertStudios.coreguard.gui.GuiSessionManager;
import com.HubertStudios.coreguard.license.LicenseManager;
import com.HubertStudios.coreguard.listeners.*;
import com.HubertStudios.coreguard.repositories.*;
import com.HubertStudios.coreguard.staff.*;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class CoreGuard extends JavaPlugin {
    private LicenseManager licenseManager;
    private ConfigManager configManager;
    private MessagesManager messages;
    private BlacklistManager blacklistManager;
    private DatabaseManager databaseManager;
    private ItemRepository itemRepository;
    private PlayerRepository playerRepository;
    private PunishmentRepository punishmentRepository;
    private AuditRepository auditRepository;
    private FingerprintService fingerprintService;
    private DupeDetector dupeDetector;
    private GuiSessionManager guiSessionManager;
    private VanishManager vanishManager;
    private FreezeManager freezeManager;
    private StaffModeManager staffModeManager;
    private SpyManager spyManager;
    private PunishmentManager punishmentManager;
    private InventoryBackupManager inventoryBackupManager;

    @Override
    public void onEnable() {
        try {
            saveResourceIfMissing("license.yml");
            configManager = new ConfigManager(this);
            configManager.load();
            messages = new MessagesManager(this);
            messages.load();
            blacklistManager = new BlacklistManager(this);
            blacklistManager.load();

            printBanner();

            licenseManager = new LicenseManager(this, getFile());
            if (!licenseManager.validate()) {
                getLogger().severe("This CoreGuard build could not be verified as an official licensed build.");
                Bukkit.getPluginManager().disablePlugin(this);
                return;
            }
            licenseManager.startPeriodicChecks();

            databaseManager = new DatabaseManager(this);
            databaseManager.open();
            itemRepository = new ItemRepository(databaseManager);
            playerRepository = new PlayerRepository(databaseManager);
            punishmentRepository = new PunishmentRepository(databaseManager);
            auditRepository = new AuditRepository(databaseManager);

            fingerprintService = new FingerprintService(this, itemRepository);
            dupeDetector = new DupeDetector(this, fingerprintService);
            vanishManager = new VanishManager(this);
            freezeManager = new FreezeManager(this);
            staffModeManager = new StaffModeManager(this);
            staffModeManager.recoverOrphanedSnapshots();
            spyManager = new SpyManager();
            punishmentManager = new PunishmentManager(this);
            punishmentManager.loadPersistedBans();
            inventoryBackupManager = new InventoryBackupManager(this);
            guiSessionManager = new GuiSessionManager(this);

            registerCommands();
            registerListeners();

            getLogger().info("CoreGuard enabled. Paper 1.21.x / Java 21 target.");
        } catch (Exception e) {
            getLogger().severe("CoreGuard failed to enable: " + e.getMessage());
            e.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (licenseManager != null) licenseManager.shutdown();
        if (staffModeManager != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (staffModeManager.isEnabled(player)) staffModeManager.cleanupOnQuit(player);
            }
        }
        if (guiSessionManager != null) {
            for (Player player : Bukkit.getOnlinePlayers()) guiSessionManager.closeSessionsFor(player.getUniqueId());
        }
        if (databaseManager != null) databaseManager.close();
        getLogger().info("CoreGuard disabled.");
    }

    public synchronized void reloadCoreGuard() {
        reloadConfig();
        configManager.reload();
        messages.load();
        blacklistManager.load();
        if (licenseManager != null) licenseManager.reload();
        if (punishmentManager != null) punishmentManager.loadPersistedBans();
    }

    private void printBanner() {
        String v = getDescription().getVersion();
        getLogger().info("  ");
        getLogger().info("  ╔═══════════════════════════════════════════════════════╗");
        getLogger().info("  ║                                                       ║");
        getLogger().info("  ║    ██╗  ██╗██╗   ██╗██████╗ ███████╗██████╗ ████████╗ ║");
        getLogger().info("  ║    ██║  ██║██║   ██║██╔══██╗██╔════╝██╔══██╗╚══██╔══╝ ║");
        getLogger().info("  ║    ███████║██║   ██║██████╔╝█████╗  ██████╔╝   ██║    ║");
        getLogger().info("  ║    ██╔══██║██║   ██║██╔══██╗██╔══╝  ██╔══██╗   ██║    ║");
        getLogger().info("  ║    ██║  ██║╚██████╔╝██████╔╝███████╗██║  ██║   ██║    ║");
        getLogger().info("  ║    ╚═╝  ╚═╝ ╚═════╝ ╚═════╝ ╚══════╝╚═╝  ╚═╝   ╚═╝    ║");
        getLogger().info("  ║                    S  T  U  D  I  O  S                ║");
        getLogger().info("  ║                                                       ║");
        getLogger().info("  ║   Plugin   »  CoreGuard   v" + padRight(v, 25) + " ║");
        getLogger().info("  ║   Author   »  HubertStudios                           ║");
        getLogger().info("  ║   Status   »  Starting up...                          ║");
        getLogger().info("  ║                                                       ║");
        getLogger().info("  ╚═══════════════════════════════════════════════════════╝");
        getLogger().info("  ");
    }

    private String padRight(String s, int n) {
        return String.format("%-" + n + "s", s);
    }

    private void saveResourceIfMissing(String resourcePath) {
        java.io.File output = new java.io.File(getDataFolder(), resourcePath);
        if (!output.exists()) {
            java.io.File parent = output.getParentFile();
            if (parent != null) parent.mkdirs();
            saveResource(resourcePath, false);
        }
    }

    private void registerCommands() {
        CoreGuardCommand executor = new CoreGuardCommand(this);
        PluginCommand command = getCommand("coreguard");
        if (command != null) {
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
    }

    private void registerListeners() {
        Bukkit.getPluginManager().registerEvents(new FingerprintListener(this), this);
        Bukkit.getPluginManager().registerEvents(new InventoryGuiListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
        Bukkit.getPluginManager().registerEvents(new FreezeListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ChatListener(this), this);
        Bukkit.getPluginManager().registerEvents(new StaffModeListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PunishmentEnforcementListener(this), this);
        Bukkit.getPluginManager().registerEvents(new VanishListener(this), this);
    }

    public ConfigManager configManager() { return configManager; }
    public MessagesManager messages() { return messages; }
    public BlacklistManager blacklistManager() { return blacklistManager; }
    public ItemRepository itemRepository() { return itemRepository; }
    public PlayerRepository playerRepository() { return playerRepository; }
    public PunishmentRepository punishmentRepository() { return punishmentRepository; }
    public AuditRepository auditRepository() { return auditRepository; }
    public FingerprintService fingerprintService() { return fingerprintService; }
    public DupeDetector dupeDetector() { return dupeDetector; }
    public GuiSessionManager guiSessionManager() { return guiSessionManager; }
    public VanishManager vanishManager() { return vanishManager; }
    public FreezeManager freezeManager() { return freezeManager; }
    public StaffModeManager staffModeManager() { return staffModeManager; }
    public SpyManager spyManager() { return spyManager; }
    public PunishmentManager punishmentManager() { return punishmentManager; }
    public InventoryBackupManager inventoryBackupManager() { return inventoryBackupManager; }
}
