package com.HubertStudios.coreguard.license;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Drop-in Bukkit/Paper lifecycle wrapper around {@link License}.
 *
 * <h2>Why this is instance-based, not static</h2>
 * A "drop into every plugin" license library is normally distributed by copying the
 * source file into each plugin's own source tree (or shading the compiled class). If the
 * license-state class keeps that state in {@code static} fields, every plugin on the
 * server that ends up with a copy of this class under the same package name shares ONE
 * set of static fields at the classloader level — arming the gate for one plugin can
 * silently arm (or trip) it for an unrelated plugin too, depending on shading/relocation
 * configuration. There's no good reason to accept that risk.
 *
 * This class holds all of its state as instance fields. Each plugin constructs its own
 * {@code LicenseGate}, owns it for the plugin's lifetime, and there is no possibility of
 * one plugin's armed/tripped state being visible to another plugin's instance, because
 * there is no shared static state at all.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * public class MyPlugin extends JavaPlugin {
 *     private LicenseGate licenseGate;
 *
 *     @Override
 *     public void onEnable() {
 *         licenseGate = new LicenseGate(this, LicenseGate.Config.builder()
 *                 .workerUrl("https://license.yourdomain.com")
 *                 .publicKeyPem(PUBLIC_KEY_PEM) // from scripts/setup-tool.html
 *                 .build());
 *
 *         if (!licenseGate.validateBlocking()) {
 *             return; // licenseGate already disabled the plugin and logged why
 *         }
 *         licenseGate.startPeriodicChecks();
 *
 *         // ... rest of onEnable ...
 *     }
 *
 *     @Override
 *     public void onDisable() {
 *         if (licenseGate != null) licenseGate.shutdown();
 *     }
 *
 *     // Anywhere you want to gate a sensitive feature behind an armed license:
 *     public void someStaffCommand() {
 *         if (!licenseGate.isArmed()) return;
 *         // ...
 *     }
 * }
 * }</pre>
 */
public final class LicenseGate {

    private static final long DEFAULT_TRUST_WINDOW_MILLIS = 75L * 60L * 1000L;
    private static final int DEFAULT_RECHECK_INTERVAL_MINUTES = 60;

    private final JavaPlugin plugin;
    private final License license;
    private final Config config;
    private final Path pluginJarPath;

    private final AtomicBoolean armed = new AtomicBoolean(false);
    private final AtomicBoolean tripped = new AtomicBoolean(false);
    private final AtomicBoolean periodicRunning = new AtomicBoolean(false);
    private final AtomicInteger checks = new AtomicInteger(0);
    private final AtomicReference<ScheduledFuture<?>> periodicTask = new AtomicReference<>(null);
    private final ScheduledExecutorService scheduler;

    private volatile String licenseKey;
    private volatile long validUntilMillis = 0L;
    private volatile String lastFailureReason = "";

    public LicenseGate(JavaPlugin plugin, Config config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "HubertStudios-LicenseGate-" + this.plugin.getName());
            thread.setDaemon(true);
            return thread;
        });
        this.license = new License(
                config.workerUrl,
                plugin.getName(),
                plugin.getDescription().getVersion(),
                config.publicKeyPem,
                config.httpTimeout
        );
        this.pluginJarPath = resolveJarPath(plugin);
        reloadLocalLicenseFile();
    }

    /**
     * Copy/drop helper: loads worker-url, public-key-pem, license, recheck interval,
     * and optional server identifiers from plugins/<PluginName>/license.yml.
     * The plugin must ship a license.yml resource with at least license, worker-url,
     * and public-key-pem keys.
     */
    public static LicenseGate fromLicenseYml(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "license.yml");
        if (!file.exists()) {
            plugin.saveResource("license.yml", false);
        }

        FileConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        Config config = Config.builder()
                .workerUrl(yaml.getString("worker-url", ""))
                .publicKeyPem(yaml.getString("public-key-pem", ""))
                .recheckIntervalMinutes(Math.max(1L, yaml.getLong("recheck-interval-minutes", DEFAULT_RECHECK_INTERVAL_MINUTES)))
                .serverInfoSupplier(() -> new License.ServerInfo(
                        plugin.getServer().getIp(),
                        plugin.getServer().getPort(),
                        plugin.getServer().getName(),
                        null,
                        yaml.getString("server.public-ip", ""),
                        yaml.getString("server.hostname", ""),
                        yaml.getString("server.domain", "")
                ))
                .build();
        return new LicenseGate(plugin, config);
    }

    /** Validates immediately and starts periodic checks when the first validation succeeds. */
    public boolean validateBlockingAndStart() {
        if (!validateBlocking()) return false;
        startPeriodicChecks();
        return true;
    }

    /**
     * Runs one validation synchronously (blocks the calling thread — intended for
     * {@code onEnable}, which Bukkit already expects to block briefly). On failure,
     * disables the plugin and logs the reason. Returns whether validation succeeded so
     * the caller can bail out of {@code onEnable} early.
     */
    public boolean validateBlocking() {
        License.Result result = runValidation();
        if (result.valid()) {
            arm();
            plugin.getLogger().info("[License] Validated successfully.");
            return true;
        }
        lastFailureReason = result.reason();
        clear();
        plugin.getLogger().severe("[License] Validation failed: " + result.reason());
        runOnServerThread(() -> plugin.getServer().getPluginManager().disablePlugin(plugin));
        return false;
    }

    /**
     * Runs one validation asynchronously off the main thread, then applies the result
     * (arm/disable) back on the main thread via the Bukkit scheduler. Use this for
     * periodic re-checks where blocking the main thread on a network call would be
     * unacceptable; {@link #startPeriodicChecks()} already does this for you.
     */
    public CompletableFuture<License.Result> validateAsync(Consumer<License.Result> onMainThreadAfter) {
        return CompletableFuture.supplyAsync(this::runValidation)
                .whenComplete((result, throwable) -> runOnServerThread(() -> {
                    if (throwable != null) {
                        lastFailureReason = "Validation threw: " + throwable.getMessage();
                        clear();
                        plugin.getLogger().severe("[License] " + lastFailureReason);
                        plugin.getServer().getPluginManager().disablePlugin(plugin);
                        return;
                    }
                    if (result.valid()) {
                        arm();
                    } else {
                        lastFailureReason = result.reason();
                        clear();
                        plugin.getLogger().severe("[License] Re-check failed: " + result.reason());
                        plugin.getServer().getPluginManager().disablePlugin(plugin);
                    }
                    if (onMainThreadAfter != null) onMainThreadAfter.accept(result);
                }));
    }

    /** Starts a recurring background re-check on the interval configured in {@link Config}. Safe to call again to restart with new settings. */
    public void startPeriodicChecks() {
        stopPeriodicChecks();
        long periodSeconds = Math.max(1L, config.recheckIntervalMinutes * 60L);
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            if (!periodicRunning.compareAndSet(false, true)) return;
            validateAsync(result -> periodicRunning.set(false));
        }, periodSeconds, periodSeconds, TimeUnit.SECONDS);
        periodicTask.set(task);
    }

    /** Cancels the periodic re-check task, if running. Idempotent. */
    public void stopPeriodicChecks() {
        ScheduledFuture<?> task = periodicTask.getAndSet(null);
        if (task != null) task.cancel(false);
    }

    /** Call from the plugin's onDisable to stop the background task cleanly. */
    public void shutdown() {
        stopPeriodicChecks();
        scheduler.shutdownNow();
        clear();
    }

    /**
     * Returns whether the gate is currently armed (license validated within the trust
     * window) and has not since been tripped. Use this to guard sensitive features —
     * commands, GUIs, scheduled jobs — without re-running a full network validation on
     * every check. This is intentionally cheap (a couple of atomic reads) so it's safe
     * to call from a hot path like a command executor.
     */
    public boolean isArmed() {
        if (!armed.get() || tripped.get()) return false;
        if (System.currentTimeMillis() > validUntilMillis) {
            trip("Trust window expired without a successful re-check.");
            return false;
        }
        checks.incrementAndGet();
        return true;
    }

    /** Like {@link #isArmed()}, but disables the plugin immediately if the gate is not armed. Use for irreversible/high-trust actions. */
    public boolean requireArmed(String checkpointName) {
        if (isArmed()) return true;
        trip("Required checkpoint failed: " + safeName(checkpointName));
        return false;
    }

    public String lastFailureReason() {
        return lastFailureReason;
    }

    /** Re-reads license.yml from disk. Call after the plugin's own /reload-equivalent command, before re-validating. */
    public void reloadLocalLicenseFile() {
        File file = new File(plugin.getDataFolder(), "license.yml");
        if (!file.exists()) {
            plugin.saveResource("license.yml", false);
        }
        FileConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String configured = yaml.getString("license");
        this.licenseKey = configured == null ? "" : configured.trim();
    }

    // ---- internals ----

    private License.Result runValidation() {
        String jarHash = pluginJarPath == null ? null : License.hashJar(pluginJarPath);
        if (jarHash == null) {
            // Running from an IDE/exploded classes directory rather than a real JAR —
            // fall back to a stable, clearly-non-matching placeholder so local dev
            // doesn't crash, but production builds always hash a real JAR file.
            jarHash = License.sha256Hex("dev-classpath:" + plugin.getName());
        }
        License.ServerInfo serverInfo = config.serverInfoSupplier == null ? License.ServerInfo.EMPTY : config.serverInfoSupplier.get();
        return license.validate(licenseKey, jarHash, serverInfo == null ? License.ServerInfo.EMPTY : serverInfo);
    }

    private void arm() {
        tripped.set(false);
        validUntilMillis = System.currentTimeMillis() + config.trustWindowMillis;
        armed.set(true);
        checks.set(0);
    }

    private void clear() {
        armed.set(false);
        tripped.set(false);
        validUntilMillis = 0L;
        checks.set(0);
    }

    private void trip(String reason) {
        if (!tripped.compareAndSet(false, true)) return;
        armed.set(false);
        lastFailureReason = reason;
        plugin.getLogger().severe("[License] " + reason);
        plugin.getLogger().severe("[License] Disabling " + plugin.getName() + " because the license gate tripped.");
        try {
            runOnServerThread(() -> plugin.getServer().getPluginManager().disablePlugin(plugin));
        } catch (Throwable t) {
            // Scheduler may already be shutting down (e.g. tripped during server stop);
            // disabling via the plugin manager directly is still safe to attempt.
            plugin.getServer().getPluginManager().disablePlugin(plugin);
        }
    }

    private void runOnServerThread(Runnable task) {
        try {
            Object globalScheduler = plugin.getServer().getClass().getMethod("getGlobalRegionScheduler").invoke(plugin.getServer());
            java.lang.reflect.Method run = globalScheduler.getClass().getMethod("run", org.bukkit.plugin.Plugin.class, java.util.function.Consumer.class);
            run.invoke(globalScheduler, plugin, (java.util.function.Consumer<Object>) ignored -> task.run());
            return;
        } catch (ReflectiveOperationException ignored) {
            // Not Folia/Paper's global region scheduler; use classic Bukkit.
        } catch (Throwable ignored) {
            // If the Folia scheduler exists but rejects during shutdown, fall back below.
        }

        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (Throwable throwable) {
            task.run();
        }
    }

    private static String safeName(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static Path resolveJarPath(JavaPlugin plugin) {
        try {
            Path path = Path.of(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(path) ? path : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Immutable configuration for a {@link LicenseGate} instance. */
    public static final class Config {
        private final String workerUrl;
        private final String publicKeyPem;
        private final Duration httpTimeout;
        private final long trustWindowMillis;
        private final long recheckIntervalMinutes;
        private final java.util.function.Supplier<License.ServerInfo> serverInfoSupplier;

        private Config(Builder b) {
            this.workerUrl = b.workerUrl;
            this.publicKeyPem = b.publicKeyPem;
            this.httpTimeout = b.httpTimeout;
            this.trustWindowMillis = b.trustWindowMillis;
            this.recheckIntervalMinutes = b.recheckIntervalMinutes;
            this.serverInfoSupplier = b.serverInfoSupplier;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private String workerUrl;
            private String publicKeyPem;
            private Duration httpTimeout = Duration.ofSeconds(10);
            private long trustWindowMillis = DEFAULT_TRUST_WINDOW_MILLIS;
            private long recheckIntervalMinutes = DEFAULT_RECHECK_INTERVAL_MINUTES;
            private java.util.function.Supplier<License.ServerInfo> serverInfoSupplier;

            public Builder workerUrl(String workerUrl) {
                this.workerUrl = workerUrl;
                return this;
            }

            public Builder publicKeyPem(String publicKeyPem) {
                this.publicKeyPem = publicKeyPem;
                return this;
            }

            public Builder httpTimeout(Duration httpTimeout) {
                this.httpTimeout = httpTimeout;
                return this;
            }

            /** How long an armed gate stays trusted between successful re-checks, in milliseconds. Default 75 minutes. */
            public Builder trustWindowMillis(long trustWindowMillis) {
                this.trustWindowMillis = trustWindowMillis;
                return this;
            }

            /** How often {@link LicenseGate#startPeriodicChecks()} re-validates, in minutes. Default 60. */
            public Builder recheckIntervalMinutes(long recheckIntervalMinutes) {
                this.recheckIntervalMinutes = recheckIntervalMinutes;
                return this;
            }

            /** Optional supplier for server identity info (bind IP, port, host, etc.) sent with each validation. */
            public Builder serverInfoSupplier(java.util.function.Supplier<License.ServerInfo> serverInfoSupplier) {
                this.serverInfoSupplier = serverInfoSupplier;
                return this;
            }

            public Config build() {
                if (workerUrl == null || workerUrl.isBlank()) {
                    throw new IllegalStateException("LicenseGate.Config requires workerUrl to be set.");
                }
                if (publicKeyPem == null || publicKeyPem.isBlank()) {
                    throw new IllegalStateException("LicenseGate.Config requires publicKeyPem to be set.");
                }
                return new Config(this);
            }
        }
    }
}
