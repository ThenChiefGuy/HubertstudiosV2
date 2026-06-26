package com.HubertStudios.coreguard.license;

import org.bukkit.plugin.java.JavaPlugin;
import com.HubertStudios.coreguard.util.SchedulerUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Distributed runtime gate used by multiple CoreGuard features.
 *
 * This does not make the plugin impossible to crack. Nothing inside a JVM can do that.
 * It does make the license logic less removable than a single startup check: commands,
 * GUI actions, staff tools, reloads, and scheduled checks all depend on a
 * valid runtime token created only after Cloudflare has accepted the license.yml key.
 */
public final class RuntimeLicenseGate {
    private static final long TRUST_WINDOW_MILLIS = 75L * 60L * 1000L;
    private static final AtomicBoolean ARMED = new AtomicBoolean(false);
    private static final AtomicBoolean TRIPPED = new AtomicBoolean(false);
    private static final AtomicInteger CHECKS = new AtomicInteger(0);

    private static volatile String proof = "";
    private static volatile String jarFingerprint = "";
    private static volatile long validUntilMillis = 0L;

    private RuntimeLicenseGate() {
    }

    public static void arm(JavaPlugin plugin, String licenseKey, String jarHash) {
        Objects.requireNonNull(plugin, "plugin");
        if (!LicenseManager.hasUsableLicense(licenseKey) || jarHash == null || jarHash.length() < 32) {
            trip(plugin, "The runtime license gate was armed with invalid data.");
            return;
        }

        String licenseFingerprint = LicenseManager.fingerprintLicense(licenseKey);
        jarFingerprint = sha256Hex(jarHash + ":" + plugin.getName());
        proof = sha256Hex(plugin.getName()
                + ':' + plugin.getDescription().getVersion()
                + ':' + licenseFingerprint
                + ':' + jarFingerprint
                + ':' + RuntimeLicenseGate.class.getName());
        validUntilMillis = System.currentTimeMillis() + TRUST_WINDOW_MILLIS;
        TRIPPED.set(false);
        ARMED.set(true);
    }

    public static void clear() {
        ARMED.set(false);
        TRIPPED.set(false);
        proof = "";
        jarFingerprint = "";
        validUntilMillis = 0L;
        CHECKS.set(0);
    }

    public static boolean allow(JavaPlugin plugin, String checkpoint) {
        if (locallyTrusted(checkpoint)) {
            return true;
        }
        trip(plugin, "License gate rejected checkpoint: " + safeCheckpoint(checkpoint));
        return false;
    }

    public static void require(JavaPlugin plugin, String checkpoint) {
        if (!allow(plugin, checkpoint)) {
            throw new IllegalStateException("CoreGuard license gate is not active: " + safeCheckpoint(checkpoint));
        }
    }

    public static boolean isArmedForTests() {
        return ARMED.get() && !TRIPPED.get();
    }

    private static boolean locallyTrusted(String checkpoint) {
        if (!ARMED.get() || TRIPPED.get()) {
            return false;
        }
        if (System.currentTimeMillis() > validUntilMillis) {
            return false;
        }
        if (proof.length() != 64 || jarFingerprint.length() != 64) {
            return false;
        }
        if (checkpoint == null || checkpoint.isBlank()) {
            return false;
        }
        if ((CHECKS.incrementAndGet() & 15) == 0 && !probeRequiredClasses()) {
            return false;
        }
        return true;
    }

    private static boolean probeRequiredClasses() {
        try {
            // FIX: only probe classes in the license package which are explicitly
            // kept by proguard.conf. PluginCore / CoreGuardPlugin are now also
            // kept, so Class.forName() on their obfuscated names would still work,
            // but referencing them directly is safer and avoids hardcoded strings.
            ClassLoader loader = RuntimeLicenseGate.class.getClassLoader();

            // These are in the kept license package — names survive ProGuard
            Class<?> manager = Class.forName(LicenseManager.class.getName(), false, loader);
            manager.getDeclaredMethod("validate");
            manager.getDeclaredMethod("startPeriodicChecks");
            manager.getDeclaredMethod("cancel");

            // Verify the gate class itself is still intact
            Class.forName(RuntimeLicenseGate.class.getName(), false, loader);
            return true;
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }

    private static void trip(JavaPlugin plugin, String reason) {
        if (!TRIPPED.compareAndSet(false, true)) {
            return;
        }
        ARMED.set(false);
        if (plugin == null) {
            return;
        }
        plugin.getLogger().severe("[License] " + reason);
        plugin.getLogger().severe("[License] CoreGuard is disabling itself because the license could not be verified.");
        try {
            SchedulerUtil.runGlobal(plugin, () -> plugin.getServer().getPluginManager().disablePlugin(plugin));
        } catch (Throwable throwable) {
            plugin.getLogger().severe("[License] Could not schedule plugin disable: " + throwable.getMessage());
        }
    }

    private static String safeCheckpoint(String checkpoint) {
        if (checkpoint == null || checkpoint.isBlank()) {
            return "unknown";
        }
        return checkpoint.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
