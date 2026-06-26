package com.HubertStudios.coreguard.license;

import com.HubertStudios.coreguard.util.SchedulerUtil;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CoreGuard authenticity + license verifier.
 *
 * Validates two things on every check:
 *   1. The license key in license.yml exists and is active in Cloudflare KV.
 *   2. The SHA-256 hash of this plugin JAR is registered as an official build.
 *
 * Cloudflare signs every response with an EC private key. The plugin verifies
 * the response using the matching public key baked in here. No secret leaves
 * Cloudflare. The public key is safe to embed in the JAR.
 *
 * Worker URL, license key, and public key are loaded from plugins/CoreGuard/license.yml.
 */
public final class LicenseManager {

    private static final int    DEFAULT_RECHECK_INTERVAL_MINUTES = 60;
    private static final Duration HTTP_TIMEOUT            = Duration.ofSeconds(10);
    private static final long   MAX_RESPONSE_AGE_SECONDS = 30L;
    private static final long   CLOCK_DRIFT_SECONDS      = 5L;
    private static final int    P256_COMPONENT_LENGTH    = 32;

    private final JavaPlugin plugin;
    private final HttpClient httpClient;
    private final Path pluginJarPath;
    private final AtomicBoolean periodicRunning = new AtomicBoolean(false);

    /**
     * FIX for race condition: use AtomicReference so cancel() can atomically
     * swap the task reference to null and cancel it, preventing two concurrent
     * cancel() calls from both seeing a non-null task.
     */
    private final AtomicReference<ScheduledFuture<?>> periodicTask = new AtomicReference<>(null);
    private final ScheduledExecutorService scheduler;

    private volatile String    licenseKey;
    private volatile String    workerUrl;
    private volatile int       recheckIntervalMinutes = DEFAULT_RECHECK_INTERVAL_MINUTES;
    private volatile PublicKey publicKey;

    public LicenseManager(JavaPlugin plugin) {
        this(plugin, null);
    }

    public LicenseManager(JavaPlugin plugin, File pluginJarFile) {
        this.plugin = plugin;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "CoreGuard-LicenseCheck");
            thread.setDaemon(true);
            return thread;
        });
        this.pluginJarPath = pluginJarFile == null ? null : pluginJarFile.toPath().toAbsolutePath().normalize();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        reloadLocalLicenseFile();
    }

    public boolean validate() {
        LicenseResult result = validateNow();
        if (result.valid()) {
            plugin.getLogger().info("CoreGuard license and build hash validated successfully.");
        } else {
            RuntimeLicenseGate.clear();
            plugin.getLogger().severe("CoreGuard validation failed: " + result.reason());
        }
        return result.valid();
    }

    public void reload() {
        cancel();
        reloadLocalLicenseFile();

        if (!validate()) {
            SchedulerUtil.runGlobal(plugin, () -> plugin.getServer().getPluginManager().disablePlugin(plugin));
            return;
        }
        startPeriodicChecks();
    }

    public void startPeriodicChecks() {
        cancel();
        long periodMinutes = Math.max(1L, recheckIntervalMinutes);

        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            if (!periodicRunning.compareAndSet(false, true)) return;

            CompletableFuture.supplyAsync(this::validateNow)
                    .whenComplete((result, throwable) -> {
                        periodicRunning.set(false);

                        if (throwable != null) {
                            RuntimeLicenseGate.clear();
                            disableFromScheduler("Validation re-check threw: " + throwable.getMessage());
                            return;
                        }
                        if (result == null || !result.valid()) {
                            RuntimeLicenseGate.clear();
                            String reason = result == null ? "Unknown error." : result.reason();
                            disableFromScheduler("Validation re-check failed: " + reason);
                        }
                    });
        }, periodMinutes, periodMinutes, TimeUnit.MINUTES);

        periodicTask.set(task);
    }

    /**
     * FIX: atomically swaps the task reference to null before cancelling.
     * Prevents two concurrent cancel() calls from both cancelling the same task.
     */
    public void cancel() {
        ScheduledFuture<?> task = periodicTask.getAndSet(null);
        if (task != null) task.cancel(false);
    }

    public void shutdown() {
        cancel();
        scheduler.shutdownNow();
    }

    // ── Core validation ───────────────────────────────────────────────────

    private LicenseResult validateNow() {
        if (!hasUsableLicense(licenseKey)) {
            return LicenseResult.invalid("Put your license key in plugins/CoreGuard/license.yml as: license: \"xxx\"");
        }
        if (isPlaceholder(workerUrl) || !workerUrl.startsWith("http")) {
            return LicenseResult.invalid("Set worker-url in plugins/CoreGuard/license.yml.");
        }
        if (publicKey == null) {
            return LicenseResult.invalid("Set public-key-pem in plugins/CoreGuard/license.yml.");
        }

        String jarHash = hashOwnJar();
        if (!isSha256Hex(jarHash)) {
            return LicenseResult.invalid("Could not calculate a valid SHA-256 hash for the CoreGuard JAR.");
        }

        String licenseFingerprint = fingerprintLicense(licenseKey);
        String requestBody = "{"
                + "\"plugin\":\""             + escapeJson(plugin.getName()) + "\","
                + "\"version\":\""            + escapeJson(plugin.getDescription().getVersion()) + "\","
                + "\"license\":\""            + escapeJson(licenseKey) + "\","
                + "\"licenseFingerprint\":\"" + licenseFingerprint + "\","
                + "\"hash\":\""               + jarHash + "\","
                + "\"server\":{"
                + "\"bindIp\":\""             + escapeJson(plugin.getServer().getIp()) + "\","
                + "\"port\":"                    + plugin.getServer().getPort() + ","
                + "\"serverName\":\""         + escapeJson(plugin.getServer().getName()) + "\""
                + "}"
                + "}";

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(normalizeWorkerUrl(workerUrl) + "api/validate"))
                    .timeout(HTTP_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                return LicenseResult.invalid("Cloudflare returned HTTP " + response.statusCode());
            }

            LicenseResult result = verifyCloudflareResponse(response.body(), licenseFingerprint, jarHash);
            if (result.valid()) {
                RuntimeLicenseGate.arm(plugin, licenseKey, jarHash);
            } else if (result.reason() != null
                    && result.reason().toLowerCase(Locale.ROOT).contains("unknown jar hash")) {
                logUnknownJarHash(jarHash);
            }
            return result;
        } catch (IOException exception) {
            return LicenseResult.invalid("Could not reach Cloudflare: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return LicenseResult.invalid("Validation check was interrupted.");
        } catch (Exception exception) {
            return LicenseResult.invalid(exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    private LicenseResult verifyCloudflareResponse(String json, String licenseFingerprint, String localJarHash) {
        Boolean valid               = parseJsonBoolean(json, "valid");
        String  pluginName          = parseJsonString(json, "plugin");
        String  reason              = parseJsonString(json, "reason");
        String  timestamp           = parseJsonString(json, "timestamp");
        String  signature           = parseJsonString(json, "signature");
        String  responseHash        = parseJsonString(json, "hash");
        String  responseFingerprint = parseJsonString(json, "licenseFingerprint");
        String  serverFingerprint   = parseJsonString(json, "serverFingerprint");
        Boolean blocked             = parseJsonBoolean(json, "blocked");
        String  code                = parseJsonNumberAsString(json, "code");

        if (valid == null || pluginName == null || timestamp == null || signature == null
                || responseHash == null || responseFingerprint == null) {
            return LicenseResult.invalid("Malformed signed response from Cloudflare.");
        }
        if (!plugin.getName().equals(pluginName)) {
            return LicenseResult.invalid("Cloudflare response was for a different plugin.");
        }
        // BUG A FIX: when the hash kill-switch is OFF the worker returns
        // hash="hash-check-disabled". Don't reject that — the worker intentionally
        // skipped hash validation. Only reject if the response carries a real hash
        // that doesn't match ours (i.e. tampered response).
        boolean hashCheckWasEnabled = isSha256Hex(responseHash);
        if (hashCheckWasEnabled && !localJarHash.equalsIgnoreCase(responseHash)) {
            return LicenseResult.invalid("Cloudflare response hash did not match this JAR.");
        }
        if (!licenseFingerprint.equalsIgnoreCase(responseFingerprint)) {
            return LicenseResult.invalid("Cloudflare response license fingerprint did not match license.yml.");
        }

        // BUG B FIX: use responseHash — the value the worker actually put in
        // dataToSign. Using localJarHash instead caused a permanent mismatch
        // whenever they differed (e.g. kill-switch returning "hash-check-disabled").
        String signedData = modernSignatureFieldsPresent(serverFingerprint, blocked, code)
                ? signaturePayloadModern(valid, pluginName, licenseFingerprint, responseHash, serverFingerprint, blocked, code, timestamp)
                : signaturePayload(valid, pluginName, licenseFingerprint, responseHash, timestamp);
        if (!verifySignature(signedData, signature)) {
            return LicenseResult.invalid("Cloudflare signature check failed.");
        }

        try {
            long age = Math.abs(System.currentTimeMillis() / 1000L - Long.parseLong(timestamp));
            if (age > MAX_RESPONSE_AGE_SECONDS + CLOCK_DRIFT_SECONDS) {
                return LicenseResult.invalid("Cloudflare response is too old (" + age + "s). Check the server clock.");
            }
        } catch (NumberFormatException exception) {
            return LicenseResult.invalid("Cloudflare response had an invalid timestamp.");
        }

        if (!valid) {
            return LicenseResult.invalid(reason == null || reason.isBlank() ? "Rejected by Cloudflare." : reason);
        }
        return LicenseResult.valid(reason == null || reason.isBlank() ? "OK" : reason);
    }

    // ── Crypto ────────────────────────────────────────────────────────────

    private boolean verifySignature(String data, String signatureBase64) {
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(data.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(p1363ToDer(decodeBase64(signatureBase64)));
        } catch (Exception exception) {
            plugin.getLogger().warning("Could not verify Cloudflare signature: " + exception.getMessage());
            return false;
        }
    }

    private PublicKey loadPublicKey(String configuredPublicKey) {
        if (isPlaceholder(configuredPublicKey)) return null;
        try {
            String cleaned = configuredPublicKey
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] decoded = decodeBase64(cleaned);
            return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(decoded));
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to load Cloudflare public key: " + exception.getMessage());
            return null;
        }
    }

    /** Computes SHA-256 of the original plugin JAR. Modified JARs have different hashes and are rejected. */
    private String hashOwnJar() {
        try {
            Path jarPath = pluginJarPath;

            if (jarPath == null || !Files.isRegularFile(jarPath)) {
                jarPath = Path.of(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            }

            if (Files.isDirectory(jarPath)) {
                // Running from IDE/test — use a stable placeholder so tests do not fail on hash.
                return sha256Hex(jarPath.toAbsolutePath().normalize().toString());
            }

            byte[] jarBytes = Files.readAllBytes(jarPath);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jarBytes));
        } catch (Exception exception) {
            plugin.getLogger().warning("Could not hash plugin JAR: " + exception.getMessage());
            return null;
        }
    }

    private void logUnknownJarHash(String jarHash) {
        plugin.getLogger().severe("Cloudflare rejected this build hash as unknown.");
        plugin.getLogger().severe("Local CoreGuard JAR SHA-256 sent to Cloudflare: " + jarHash);
        plugin.getLogger().severe("Register this exact lowercase KV key: CoreGuard:" + jarHash);
    }

    private void disableFromScheduler(String message) {
        SchedulerUtil.runGlobal(plugin, () -> {
            plugin.getLogger().severe(message);
            plugin.getLogger().severe("Disabling CoreGuard because license/build could not be verified.");
            plugin.getServer().getPluginManager().disablePlugin(plugin);
        });
    }

    // ── License file ─────────────────────────────────────────────────────

    /**
     * Reads all license settings from plugins/CoreGuard/license.yml.
     * This makes CoreGuard use the same copy/drop configuration model as the
     * universal HubertStudios license client instead of hardcoded Worker values.
     */
    private void reloadLocalLicenseFile() {
        File file = new File(plugin.getDataFolder(), "license.yml");
        if (!file.exists()) plugin.saveResource("license.yml", false);

        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        String configured = config.getString("license");
        this.licenseKey = configured == null ? "" : configured.trim();
        this.workerUrl = config.getString("worker-url", "").trim();
        this.recheckIntervalMinutes = Math.max(1, config.getInt("recheck-interval-minutes", DEFAULT_RECHECK_INTERVAL_MINUTES));
        this.publicKey = loadPublicKey(config.getString("public-key-pem", ""));
    }

    // ── Package-private helpers (used by tests) ───────────────────────────

    static boolean hasUsableLicense(String value) {
        if (value == null) return false;
        String normalized = value.trim();
        return !normalized.isEmpty()
                && !normalized.equalsIgnoreCase("license")
                && !normalized.equalsIgnoreCase("put-license-here")
                && !normalized.equalsIgnoreCase("xxx")
                && !normalized.equalsIgnoreCase("your-license-key")
                && !normalized.equalsIgnoreCase("customer-license-key");
    }

    static String fingerprintLicense(String license) {
        return sha256Hex(license == null ? "" : license.trim());
    }

    static String signaturePayload(boolean valid, String pluginName,
                                   String licenseFingerprint, String jarHash, String timestamp) {
        return valid + ":" + pluginName + ":" + licenseFingerprint + ":" + jarHash + ":" + timestamp;
    }

    static boolean modernSignatureFieldsPresent(String serverFingerprint, Boolean blocked, String code) {
        return serverFingerprint != null && blocked != null && code != null;
    }

    static String signaturePayloadModern(boolean valid, String pluginName, String licenseFingerprint, String jarHash,
                                         String serverFingerprint, boolean blocked, String code, String timestamp) {
        return valid + ":" + pluginName + ":" + licenseFingerprint + ":" + jarHash + ":"
                + serverFingerprint + ":" + blocked + ":" + code + ":" + timestamp;
    }

    static boolean isSha256Hex(String value) {
        return value != null && value.matches("^[0-9a-fA-F]{64}$");
    }

    // ── P1363 → DER conversion ────────────────────────────────────────────

    private static byte[] p1363ToDer(byte[] p1363) {
        if (p1363.length != P256_COMPONENT_LENGTH * 2) return p1363;
        byte[] r = toUnsignedDerInteger(p1363, 0, P256_COMPONENT_LENGTH);
        byte[] s = toUnsignedDerInteger(p1363, P256_COMPONENT_LENGTH, P256_COMPONENT_LENGTH);
        int totalLength = 2 + r.length + 2 + s.length;
        byte[] der = new byte[2 + totalLength];
        int pos = 0;
        der[pos++] = 0x30;
        der[pos++] = (byte) totalLength;
        der[pos++] = 0x02;
        der[pos++] = (byte) r.length;
        System.arraycopy(r, 0, der, pos, r.length); pos += r.length;
        der[pos++] = 0x02;
        der[pos++] = (byte) s.length;
        System.arraycopy(s, 0, der, pos, s.length);
        return der;
    }

    private static byte[] toUnsignedDerInteger(byte[] source, int offset, int length) {
        int start = offset;
        while (start < offset + length - 1 && source[start] == 0) start++;
        boolean needsPadding = (source[start] & 0x80) != 0;
        int componentLength = (offset + length) - start;
        byte[] result = new byte[needsPadding ? componentLength + 1 : componentLength];
        if (needsPadding) {
            result[0] = 0x00;
            System.arraycopy(source, start, result, 1, componentLength);
        } else {
            System.arraycopy(source, start, result, 0, componentLength);
        }
        return result;
    }

    // ── Utility ───────────────────────────────────────────────────────────

    private static String normalizeWorkerUrl(String url) {
        return url.endsWith("/") ? url : url + "/";
    }

    private static boolean isPlaceholder(String value) {
        if (value == null || value.isBlank()) return true;
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("your-subdomain")
                || lower.contains("paste")
                || lower.contains("replace")
                || lower.contains("public_key_here")
                || lower.contains("generate-keys");
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static byte[] decodeBase64(String input) {
        String normalized = input.trim();
        try {
            return Base64.getDecoder().decode(normalized);
        } catch (IllegalArgumentException ignored) {
            return Base64.getUrlDecoder().decode(normalized);
        }
    }

    private static Boolean parseJsonBoolean(String json, String field) {
        String pattern = "\"" + field + "\":";
        int start = json.indexOf(pattern);
        if (start < 0) return null;
        String value = json.substring(start + pattern.length()).trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("true"))  return true;
        if (value.startsWith("false")) return false;
        return null;
    }

    private static String parseJsonNumberAsString(String json, String field) {
        String pattern = "\"" + field + "\":";
        int start = json.indexOf(pattern);
        if (start < 0) return null;
        int pos = start + pattern.length();
        StringBuilder sb = new StringBuilder();
        while (pos < json.length() && (Character.isDigit(json.charAt(pos)) || json.charAt(pos) == '-')) {
            sb.append(json.charAt(pos));
            pos++;
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String parseJsonString(String json, String field) {
        String pattern = "\"" + field + "\":\"";
        int start = json.indexOf(pattern);
        if (start < 0) return null;
        int pos = start + pattern.length();
        StringBuilder builder = new StringBuilder();
        boolean escaped = false;
        for (int i = pos; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped)      { builder.append(c); escaped = false; continue; }
            if (c == '\\')    { escaped = true; continue; }
            if (c == '"')     { return builder.toString(); }
            builder.append(c);
        }
        return null;
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private record LicenseResult(boolean valid, String reason) {
        static LicenseResult valid(String reason)   { return new LicenseResult(true, reason); }
        static LicenseResult invalid(String reason) { return new LicenseResult(false, reason); }
    }
}
