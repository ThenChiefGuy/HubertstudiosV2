package com.HubertStudios.coreguard.license;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * Pure license-validation protocol client for the HubertStudios License Manager Worker.
 *
 * This class has NO Bukkit dependency and holds NO static mutable state — every field is
 * either final or set once in the constructor, and every validation call is a plain
 * method call against an immutable {@link License} instance. That means:
 *
 *   - It is safe for multiple plugins to shade their own private copy of this class
 *     into their own JARs without any risk of one plugin's validation state leaking
 *     into another's.
 *   - It is safe to unit test in isolation, with no Bukkit/server runtime required.
 *
 * Typical usage is through {@link LicenseGate}, which adds the Bukkit-specific lifecycle
 * (periodic re-checks, scheduler integration, disabling the plugin on failure). Plugins
 * that want raw control can use this class directly instead.
 *
 * <h2>Setup checklist for the plugin author</h2>
 * <ol>
 *   <li>Deploy the Worker backend (see the backend README) and run the
 *       {@code setup-tool.html} page to generate an ECDSA keypair.</li>
 *   <li>Paste the resulting public key PEM into the {@code publicKeyPem} you pass to this
 *       class's constructor (or into your plugin's config — do NOT hardcode it as a Java
 *       string constant if you ever want to rotate keys without recompiling, though
 *       hardcoding is fine for most setups since the public key is not a secret).</li>
 *   <li>Set the Worker's base URL similarly.</li>
 *   <li>Register your plugin in the dashboard, generate a license key, and put that key
 *       into your plugin's own config (e.g. license.yml).</li>
 * </ol>
 */
public final class License {

    /** Maximum age (in seconds) of a server response before it's rejected as stale/replayed. */
    private static final long DEFAULT_MAX_RESPONSE_AGE_SECONDS = 30L;
    /** Allowance for the local clock and the server clock disagreeing slightly. */
    private static final long DEFAULT_CLOCK_DRIFT_SECONDS = 5L;
    private static final int P256_COMPONENT_LENGTH = 32;

    private final String workerUrl;
    private final String pluginName;
    private final String pluginVersion;
    private final PublicKey publicKey;
    private final HttpClient httpClient;
    private final long maxResponseAgeSeconds;
    private final long clockDriftSeconds;

    /**
     * @param workerUrl       base URL of the deployed Worker, e.g. {@code https://license.yourdomain.com}
     *                        (the {@code /api/validate} path is appended automatically)
     * @param pluginName      must exactly match the {@code name} field registered in the dashboard
     * @param pluginVersion   reported to the server for logging/diagnostics only; never used to decide validity
     * @param publicKeyPem    the SPKI PEM public key printed by {@code scripts/setup-tool.html}
     * @param httpTimeout     per-request timeout
     */
    public License(String workerUrl, String pluginName, String pluginVersion, String publicKeyPem, Duration httpTimeout) {
        this(workerUrl, pluginName, pluginVersion, publicKeyPem, httpTimeout, DEFAULT_MAX_RESPONSE_AGE_SECONDS, DEFAULT_CLOCK_DRIFT_SECONDS);
    }

    public License(String workerUrl, String pluginName, String pluginVersion, String publicKeyPem, Duration httpTimeout,
                    long maxResponseAgeSeconds, long clockDriftSeconds) {
        this.workerUrl = normalizeBaseUrl(Objects.requireNonNull(workerUrl, "workerUrl"));
        this.pluginName = Objects.requireNonNull(pluginName, "pluginName");
        this.pluginVersion = pluginVersion == null ? "unknown" : pluginVersion;
        this.publicKey = loadPublicKey(Objects.requireNonNull(publicKeyPem, "publicKeyPem"));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(httpTimeout == null ? Duration.ofSeconds(10) : httpTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.maxResponseAgeSeconds = maxResponseAgeSeconds;
        this.clockDriftSeconds = clockDriftSeconds;
    }

    /**
     * Performs one validation round-trip against the Worker. Never throws for ordinary
     * failure modes (network errors, bad signatures, server-side rejection) — those all
     * come back as a {@code Result} with {@code valid() == false} and a human-readable
     * reason. Only truly unexpected conditions (e.g. SHA-256 unavailable on this JVM)
     * throw, and those indicate a broken runtime rather than a license problem.
     *
     * @param licenseKey the plugin's configured license key (plaintext, as the admin pasted it)
     * @param jarHash    SHA-256 hex of the running plugin JAR (see {@link #hashJar})
     * @param server     server identity fields the Worker uses for ban/allow matching; pass
     *                   {@link ServerInfo#EMPTY} if you have nothing meaningful to report
     */
    public Result validate(String licenseKey, String jarHash, ServerInfo server) {
        if (!hasUsableLicense(licenseKey)) {
            return Result.invalid("No license key configured.");
        }
        if (!isSha256Hex(jarHash)) {
            return Result.invalid("Could not calculate a valid SHA-256 hash for this plugin JAR.");
        }

        String licenseFingerprint = fingerprintLicense(licenseKey);
        String requestBody = buildRequestBody(licenseKey, jarHash, licenseFingerprint, server);

        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(workerUrl + "api/validate"))
                    .timeout(httpClient.connectTimeout().orElse(Duration.ofSeconds(10)))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return Result.invalid("Could not reach the license server: " + e.getMessage());
        }

        if (response.statusCode() == 0) {
            return Result.invalid("No response from license server.");
        }

        return verifyServerResponse(response.body(), licenseFingerprint, jarHash);
    }

    // ---- response verification ----

    private Result verifyServerResponse(String json, String expectedLicenseFingerprint, String expectedHash) {
        if (json == null || json.isBlank()) {
            return Result.invalid("Empty response from license server.");
        }

        Boolean valid = parseJsonBoolean(json, "valid");
        String plugin = parseJsonString(json, "plugin");
        String licenseFingerprint = parseJsonString(json, "licenseFingerprint");
        String hash = parseJsonString(json, "hash");
        String reason = parseJsonString(json, "reason");
        String timestamp = parseJsonString(json, "timestamp");
        String signature = parseJsonString(json, "signature");
        Boolean blocked = parseJsonBoolean(json, "blocked");
        String code = parseJsonNumberAsString(json, "code");
        String serverFingerprint = parseJsonString(json, "serverFingerprint");

        if (valid == null || signature == null || timestamp == null) {
            return Result.invalid("Malformed response from license server.");
        }
        if (!pluginName.equals(plugin)) {
            return Result.invalid("Response was for a different plugin (possible relay/MITM).");
        }
        if (!constantTimeEquals(expectedLicenseFingerprint, licenseFingerprint)) {
            return Result.invalid("Response license fingerprint did not match the request.");
        }
        // hash may legitimately be "hash-check-disabled" when the server has hash
        // validation turned off for this plugin — only enforce equality when the
        // server actually echoed back a real hex hash.
        if (isSha256Hex(hash) && !constantTimeEquals(expectedHash, hash)) {
            return Result.invalid("Response JAR hash did not match the request.");
        }

        long timestampSeconds;
        try {
            timestampSeconds = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return Result.invalid("Response timestamp was not a valid number.");
        }
        long nowSeconds = System.currentTimeMillis() / 1000L;
        long age = nowSeconds - timestampSeconds;
        if (age > maxResponseAgeSeconds + clockDriftSeconds || age < -clockDriftSeconds) {
            return Result.invalid("Response timestamp is stale or in the future (possible replay attack). Age=" + age + "s");
        }

        // Build the signature payload in the same format the server used. Modern
        // responses include serverFingerprint/blocked/code; legacy ones (4-field) only
        // include plugin/fingerprint/hash/timestamp. We detect which shape we got based
        // on whether serverFingerprint/blocked/code were present in the JSON at all —
        // NOT based on any caller-supplied "legacy mode" flag, since the whole point is
        // that the client should verify exactly what the server actually signed.
        String dataToSign;
        if (serverFingerprint != null && blocked != null && code != null) {
            dataToSign = valid + ":" + plugin + ":" + licenseFingerprint + ":" + hash + ":" + serverFingerprint + ":" + blocked + ":" + code + ":" + timestamp;
        } else {
            dataToSign = valid + ":" + plugin + ":" + licenseFingerprint + ":" + hash + ":" + timestamp;
        }

        if (!verifySignature(dataToSign, signature)) {
            return Result.invalid("Signature verification failed (response may be forged or tampered).");
        }

        if (!Boolean.TRUE.equals(valid)) {
            return Result.invalid(reason == null ? "License rejected by server." : reason);
        }
        return Result.valid(reason == null ? "OK" : reason);
    }

    private boolean verifySignature(String dataToSign, String signatureBase64) {
        try {
            byte[] rawSignature = decodeBase64(signatureBase64);
            byte[] derSignature = looksLikeDer(rawSignature) ? rawSignature : p1363ToDer(rawSignature);
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(dataToSign.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(derSignature);
        } catch (Exception e) {
            return false;
        }
    }

    // ---- request building ----

    private String buildRequestBody(String licenseKey, String jarHash, String licenseFingerprint, ServerInfo server) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        appendJsonField(sb, "plugin", pluginName, true);
        appendJsonField(sb, "version", pluginVersion, true);
        appendJsonField(sb, "license", licenseKey, true);
        appendJsonField(sb, "licenseFingerprint", licenseFingerprint, true);
        appendJsonField(sb, "hash", jarHash, true);
        sb.append("\"server\":{");
        appendJsonField(sb, "bindIp", server.bindIp(), true);
        appendJsonField(sb, "port", String.valueOf(server.port()), false);
        sb.append(',');
        appendJsonField(sb, "serverName", server.serverName(), true);
        appendJsonField(sb, "host", server.host(), true);
        appendJsonField(sb, "publicIp", server.publicIp(), true);
        appendJsonField(sb, "hostname", server.hostname(), true);
        appendJsonField(sb, "domain", server.domain(), true);
        sb.setLength(sb.length() - 1); // trim trailing comma from the last appendJsonField
        sb.append('}');
        sb.append('}');
        return sb.toString();
    }

    private static void appendJsonField(StringBuilder sb, String key, String value, boolean isString) {
        sb.append('"').append(key).append("\":");
        if (isString) {
            sb.append('"').append(escapeJson(value == null ? "" : value)).append('"');
        } else {
            sb.append(value == null ? "0" : value);
        }
        sb.append(',');
    }

    // ---- public static helpers (also used by LicenseGate) ----

    public static boolean hasUsableLicense(String value) {
        if (value == null) return false;
        String normalized = value.trim();
        if (normalized.isEmpty()) return false;
        String lower = normalized.toLowerCase(Locale.ROOT);
        return !lower.equals("license")
                && !lower.equals("put-license-here")
                && !lower.equals("xxx")
                && !lower.equals("your-license-key")
                && !lower.equals("customer-license-key");
    }

    public static String fingerprintLicense(String license) {
        return sha256Hex(license == null ? "" : license.trim());
    }

    public static boolean isSha256Hex(String value) {
        return value != null && value.matches("^[0-9a-fA-F]{64}$");
    }

    /** Computes the SHA-256 hex of a plugin JAR file. Pass the absolute path to the running JAR. */
    public static String hashJar(java.nio.file.Path jarPath) {
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(jarPath);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            return null;
        }
    }

    public static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is not available on this JVM", e);
        }
    }

    // ---- crypto plumbing ----

    private static PublicKey loadPublicKey(String pem) {
        try {
            String base64 = pem.replaceAll("-----BEGIN [^-]+-----", "")
                    .replaceAll("-----END [^-]+-----", "")
                    .replaceAll("\\s", "");
            byte[] keyBytes = Base64.getDecoder().decode(base64);
            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            return keyFactory.generatePublic(new X509EncodedKeySpec(keyBytes));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid EC public key PEM supplied to License: " + e.getMessage(), e);
        }
    }

    private static boolean looksLikeDer(byte[] signature) {
        return signature.length > 0 && signature[0] == 0x30;
    }

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
        System.arraycopy(r, 0, der, pos, r.length);
        pos += r.length;
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

    private static byte[] decodeBase64(String input) {
        String normalized = input.trim();
        try {
            return Base64.getDecoder().decode(normalized);
        } catch (IllegalArgumentException ignored) {
            return Base64.getUrlDecoder().decode(normalized);
        }
    }

    /** Constant-time-ish string comparison to avoid leaking partial matches via timing. Both inputs are short, low-stakes hex/bool strings, but cheap to do properly. */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        if (aBytes.length != bBytes.length) return false;
        int diff = 0;
        for (int i = 0; i < aBytes.length; i++) diff |= aBytes[i] ^ bBytes[i];
        return diff == 0;
    }

    private static String normalizeBaseUrl(String url) {
        return url.endsWith("/") ? url : url + "/";
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ---- minimal hand-rolled JSON field extraction (no external dependency) ----

    private static Boolean parseJsonBoolean(String json, String field) {
        String pattern = "\"" + field + "\":";
        int start = json.indexOf(pattern);
        if (start < 0) return null;
        String value = json.substring(start + pattern.length()).trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("true")) return true;
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
            if (escaped) {
                builder.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                return builder.toString();
            }
            builder.append(c);
        }
        return null;
    }

    /** Outcome of a single validation attempt. Immutable. */
    public static final class Result {
        private final boolean valid;
        private final String reason;

        private Result(boolean valid, String reason) {
            this.valid = valid;
            this.reason = reason;
        }

        public static Result valid(String reason) {
            return new Result(true, reason);
        }

        public static Result invalid(String reason) {
            return new Result(false, reason);
        }

        public boolean valid() {
            return valid;
        }

        public String reason() {
            return reason;
        }
    }

    /**
     * Server identity fields sent to the Worker for ban/allow-list matching. All fields
     * are optional (null/empty is fine) — the Worker also derives identifiers from the
     * connecting IP independently, this is supplementary information the server can't
     * see for itself (e.g. a configured public hostname behind NAT).
     */
    public static final class ServerInfo {
        public static final ServerInfo EMPTY = new ServerInfo(null, 0, null, null, null, null, null);

        private final String bindIp;
        private final int port;
        private final String serverName;
        private final String host;
        private final String publicIp;
        private final String hostname;
        private final String domain;

        public ServerInfo(String bindIp, int port, String serverName, String host, String publicIp, String hostname, String domain) {
            this.bindIp = bindIp;
            this.port = port;
            this.serverName = serverName;
            this.host = host;
            this.publicIp = publicIp;
            this.hostname = hostname;
            this.domain = domain;
        }

        public String bindIp() { return bindIp; }
        public int port() { return port; }
        public String serverName() { return serverName; }
        public String host() { return host; }
        public String publicIp() { return publicIp; }
        public String hostname() { return hostname; }
        public String domain() { return domain; }
    }
}
