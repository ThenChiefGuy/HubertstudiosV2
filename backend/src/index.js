// Combined backend index.js
// Bundled all modules into one file.

/* lib/http.js */
// lib/http.js

const SECURITY_HEADERS = {
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Referrer-Policy": "no-referrer",
    "Content-Security-Policy": "default-src 'none'; frame-ancestors 'none'",
    "Permissions-Policy": "geolocation=(), camera=(), microphone=()",
    "Strict-Transport-Security": "max-age=63072000; includeSubDomains; preload",
    "Cross-Origin-Opener-Policy": "same-origin",
    "Cross-Origin-Resource-Policy": "same-origin",
};

function json(payload, status = 200, extraHeaders = {}) {
    return new Response(JSON.stringify(payload), {
        status,
        headers: {
            "Content-Type": "application/json",
            ...SECURITY_HEADERS,
            ...extraHeaders,
        },
    });
}

function noContent(status = 204, extraHeaders = {}) {
    return new Response(null, { status, headers: { ...SECURITY_HEADERS, ...extraHeaders } });
}

/** Generic-on-purpose error for auth flows — never reveals which field was wrong. */
function authError(status = 401) {
    return json({ ok: false, error: "Invalid credentials." }, status);
}

/* lib/crypto.js */
// lib/crypto.js
//
// All WebCrypto-based. No Node crypto module (Workers don't have it).
//
// Password storage model:
//   ADMIN_PASSWORD_HASH and ADMIN_PASSWORD_SALT are Worker secrets, generated
//   ONCE during setup with the standalone setup-tool.html page (see README)
//   and never derived or stored anywhere else. The plaintext password only
//   ever exists transiently in the request body during login, and is never
//   logged.

const PBKDF2_ITERATIONS = 210_000; // OWASP 2023+ recommendation floor for PBKDF2-SHA256
const PBKDF2_KEYLEN_BITS = 256;

function toHex(buffer) {
    return [...new Uint8Array(buffer)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function fromHex(hex) {
    const clean = String(hex || "").trim();
    const bytes = new Uint8Array(clean.length / 2);
    for (let i = 0; i < bytes.length; i++) bytes[i] = parseInt(clean.substr(i * 2, 2), 16);
    return bytes;
}

/** Constant-time comparison of two equal-length hex strings (or byte arrays via hex encoding). */
function timingSafeEqualHex(aHex, bHex) {
    const a = String(aHex || "");
    const b = String(bHex || "");
    if (a.length !== b.length) {
        // Still do a dummy comparison of equal length to avoid leaking length
        // timing in the common case, then return false.
        let dummy = 0;
        for (let i = 0; i < a.length; i++) dummy |= a.charCodeAt(i) ^ (b.charCodeAt(i % Math.max(b.length, 1)) || 0);
        return false;
    }
    let diff = 0;
    for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
    return diff === 0;
}

async function sha256Hex(value) {
    const data = typeof value === "string" ? new TextEncoder().encode(value) : value;
    const digest = await crypto.subtle.digest("SHA-256", data);
    return toHex(digest);
}

function isSha256Hex(value) {
    return typeof value === "string" && /^[a-f0-9]{64}$/.test(value);
}

/**
 * Derives a PBKDF2-SHA256 key from a password + salt and returns hex.
 * saltHex must be a hex-encoded salt (generated once at setup time).
 */
async function pbkdf2Hex(password, saltHex, iterations = PBKDF2_ITERATIONS) {
    const keyMaterial = await crypto.subtle.importKey(
        "raw",
        new TextEncoder().encode(password),
        { name: "PBKDF2" },
        false,
        ["deriveBits"]
    );
    const derived = await crypto.subtle.deriveBits(
        {
            name: "PBKDF2",
            salt: fromHex(saltHex),
            iterations,
            hash: "SHA-256",
        },
        keyMaterial,
        PBKDF2_KEYLEN_BITS
    );
    return toHex(derived);
}

/**
 * Verifies a plaintext password against the stored PBKDF2 hash + salt secrets.
 * Always runs the full derivation (no early return) so timing doesn't leak
 * whether the email was even recognized — callers should call this
 * unconditionally rather than branching on email-exists first.
 */
async function verifyPassword(password, expectedHashHex, saltHex, iterations) {
    const actualHex = await pbkdf2Hex(password || "", saltHex, iterations);
    return timingSafeEqualHex(actualHex, expectedHashHex);
}

/** HMAC-SHA256 of a value using SESSION_SECRET (or any secret), returned as hex. Used to hash 6-digit codes and sign cookies. */
async function hmacHex(secret, value) {
    const key = await crypto.subtle.importKey(
        "raw",
        new TextEncoder().encode(secret),
        { name: "HMAC", hash: "SHA-256" },
        false,
        ["sign"]
    );
    const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(value));
    return toHex(sig);
}

async function hmacVerifyHex(secret, value, expectedHex) {
    const actual = await hmacHex(secret, value);
    return timingSafeEqualHex(actual, expectedHex);
}

function randomDigits(length) {
    const bytes = crypto.getRandomValues(new Uint8Array(length));
    return [...bytes].map((b) => String(b % 10)).join("");
}

function randomToken(byteLength = 32) {
    const bytes = crypto.getRandomValues(new Uint8Array(byteLength));
    return toHex(bytes);
}

function pemToBuffer(pem) {
    const base64 = String(pem || "")
        .replace(/-----BEGIN.*?-----/g, "")
        .replace(/-----END.*?-----/g, "")
        .replace(/\s/g, "");
    const binary = atob(base64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes.buffer;
}

function bufferToBase64(buffer) {
    return btoa(String.fromCharCode(...new Uint8Array(buffer)));
}

/** Signs `dataToSign` (a string) with the ECDSA P-256 PRIVATE_KEY secret (PKCS8 PEM). Returns base64 signature. */
async function signEcdsa(privateKeyPem, dataToSign) {
    const key = await crypto.subtle.importKey(
        "pkcs8",
        pemToBuffer(privateKeyPem),
        { name: "ECDSA", namedCurve: "P-256" },
        false,
        ["sign"]
    );
    const signature = await crypto.subtle.sign(
        { name: "ECDSA", hash: "SHA-256" },
        key,
        new TextEncoder().encode(dataToSign)
    );
    return bufferToBase64(signature);
}

/* lib/kvKeys.js */
// lib/kvKeys.js
//
// Single source of truth for KV key names. Every other module imports from
// here instead of building key strings inline, so the naming scheme only
// ever needs to change in one place.
//
// Key scheme (GLOBAL-license model — there is no per-customer key anywhere):
//
//   ALLOWED_PRODUCTS                  -> {"products":["CoreGuard", ...]}
//   LEGACY_PRODUCTS                   -> {"products":["DonutHomes", ...]}
//   <product>:DISABLED                -> "true" | "false"
//   <product>:REQUIRE_HASH            -> "true" | "false"
//   REQUIRE_HASH                      -> "true" | "false"   (global hash toggle fallback)
//   LICENSE:<key>                     -> JSON license record (legacy products)
//   <product>.license:<key>           -> JSON license record (modern products)
//   <product>:<hash>                  -> JSON build record
//   SERVERBAN:<identifier>            -> JSON ban record (global)
//   <product>.serverban:<identifier>  -> JSON ban record (plugin-specific)
//
// All KV values are written by lib/sync.js immediately after the
// corresponding D1 write succeeds — D1 is always the source of truth, KV is
// a read-through cache for the hot /api/validate path only.

const ALLOWED_PRODUCTS_KEY = "ALLOWED_PRODUCTS";
const LEGACY_PRODUCTS_KEY = "LEGACY_PRODUCTS";
const GLOBAL_REQUIRE_HASH_KEY = "REQUIRE_HASH";

function productDisabledKey(productName) {
    return `${productName}:DISABLED`;
}

function productRequireHashKey(productName) {
    return `${productName}:REQUIRE_HASH`;
}

function legacyLicenseKey(licenseKey) {
    return `LICENSE:${licenseKey}`;
}

function modernLicenseKey(productName, licenseKey) {
    return `${productName}.license:${licenseKey}`;
}

function buildKey(productName, jarHash) {
    return `${productName}:${jarHash}`;
}

function globalBanKey(identifier) {
    return `SERVERBAN:${identifier.toLowerCase()}`;
}

function pluginBanKey(productName, identifier) {
    return `${productName}.serverban:${identifier.toLowerCase()}`;
}

// --- Auth-related KV keys (not part of the original license scheme, but
// live in the same namespace since Workers KV has no concept of "tables") ---

function loginAttemptKey(ip) {
    return `AUTH:LOGIN_ATTEMPTS:${ip}`;
}

function codeAttemptKey(ip) {
    return `AUTH:CODE_ATTEMPTS:${ip}`;
}

function ipBlockKey(ip) {
    return `AUTH:BLOCKED_IP:${ip}`;
}

function verifyChallengeKey(challengeId) {
    return `AUTH:CHALLENGE:${challengeId}`;
}

function sessionKey(sessionId) {
    return `AUTH:SESSION:${sessionId}`;
}

/* lib/auth.js */
// lib/auth.js
//
// Session model:
//   - Session ID is a random 32-byte token (hex). The session record itself
//     (admin email, createdAt, csrfToken) lives in KV under AUTH:SESSION:<id>
//     with TTL = SESSION_MAX_AGE_SECONDS.
//   - The cookie carries only the session ID, HMAC-signed with SESSION_SECRET
//     so a forged/guessed ID can't be substituted without also forging the
//     signature. (KV lookup would catch a forged ID anyway since it wouldn't
//     exist, but signing means we can reject obviously-tampered cookies
//     without a KV round-trip, and protects against KV enumeration timing.)
//   - CSRF token is a separate random value stored in the same session
//     record and required on every mutating admin request via the
//     X-CSRF-Token header. It is never the same value as the session ID.
//
// Rate limiting model:
//   - Failed login attempts and failed code attempts are counted per-IP in
//     KV with a sliding TTL window. 5 failures -> IP is blocked for
//     BLOCK_DURATION_SECONDS. Blocks are checked BEFORE doing any password
//     work, so a blocked IP never even reaches the expensive PBKDF2 step.


const SESSION_MAX_AGE_SECONDS = 8 * 60 * 60; // 8 hours, per spec
const MAX_FAILED_ATTEMPTS = 5;
const ATTEMPT_WINDOW_SECONDS = 15 * 60; // failures older than this don't count
const BLOCK_DURATION_SECONDS = 15 * 60;

const SESSION_COOKIE_NAME = "__Host-hs_session";

// ---- IP extraction ----

function getClientIp(request) {
    return (
        request.headers.get("CF-Connecting-IP") ||
        request.headers.get("X-Forwarded-For")?.split(",")[0]?.trim() ||
        "0.0.0.0"
    );
}

// ---- Rate limiting / IP blocking ----

async function isIpBlocked(kv, ip) {
    const blocked = await kv.get(ipBlockKey(ip));
    return !!blocked;
}

async function blockIp(kv, ip) {
    await kv.put(ipBlockKey(ip), "1", { expirationTtl: BLOCK_DURATION_SECONDS });
}

/**
 * Records a failed attempt for the given counter key. If this push reaches
 * MAX_FAILED_ATTEMPTS, blocks the IP and resets the counter. Returns the new
 * count so callers can decide whether to mention "attempts remaining" (we
 * intentionally do not surface this to the client — see handlers — but it's
 * available for server-side logging).
 */
async function recordFailedAttempt(kv, counterKey, ip) {
    const raw = await kv.get(counterKey);
    const count = (parseInt(raw, 10) || 0) + 1;
    if (count >= MAX_FAILED_ATTEMPTS) {
        await blockIp(kv, ip);
        await kv.delete(counterKey);
        return count;
    }
    await kv.put(counterKey, String(count), { expirationTtl: ATTEMPT_WINDOW_SECONDS });
    return count;
}

async function clearFailedAttempts(kv, counterKey) {
    await kv.delete(counterKey);
}

async function recordFailedLogin(kv, ip) {
    return recordFailedAttempt(kv, loginAttemptKey(ip), ip);
}

async function clearFailedLogin(kv, ip) {
    return clearFailedAttempts(kv, loginAttemptKey(ip));
}

async function recordFailedCode(kv, ip) {
    return recordFailedAttempt(kv, codeAttemptKey(ip), ip);
}

async function clearFailedCode(kv, ip) {
    return clearFailedAttempts(kv, codeAttemptKey(ip));
}

// ---- Verification challenge (email code) ----

/**
 * Creates a pending email-verification challenge after a correct email+password.
 * Stores only the HMAC of the 6-digit code (never the code itself) plus the
 * admin email it's bound to, with a 10-minute TTL. Returns the challengeId
 * to hand back to the client (in a short-lived, signed cookie) and the
 * plaintext code to send via the email provider.
 */
async function createVerificationChallenge(kv, secret, adminEmail, code) {
    const challengeId = randomToken(16);
    const codeHash = await hmacHex(secret, code);
    await kv.put(
        `AUTH:CHALLENGE:${challengeId}`,
        JSON.stringify({ adminEmail, codeHash, createdAt: Date.now() }),
        { expirationTtl: 10 * 60 }
    );
    return challengeId;
}

async function getVerificationChallenge(kv, challengeId) {
    const raw = await kv.get(`AUTH:CHALLENGE:${challengeId}`);
    return raw ? JSON.parse(raw) : null;
}

async function deleteVerificationChallenge(kv, challengeId) {
    await kv.delete(`AUTH:CHALLENGE:${challengeId}`);
}

// ---- Session ----

async function createSession(kv, secret, adminEmail) {
    const sessionId = randomToken(32);
    const csrfToken = randomToken(32);
    await kv.put(
        sessionKey(sessionId),
        JSON.stringify({ adminEmail, csrfToken, createdAt: Date.now() }),
        { expirationTtl: SESSION_MAX_AGE_SECONDS }
    );
    const signature = await hmacHex(secret, sessionId);
    return { sessionId, signature, csrfToken };
}

async function getSession(kv, sessionCookieValue) {
    if (!sessionCookieValue || !sessionCookieValue.includes(".")) return null;
    const [sessionId, signature] = sessionCookieValue.split(".");
    return { sessionId, signature };
}

/** Verifies the cookie signature, then looks up the session in KV. Returns the session record (with sessionId attached) or null. */
async function resolveSession(kv, secret, sessionCookieValue) {
    const parsed = await getSession(kv, sessionCookieValue);
    if (!parsed) return null;
    const signatureValid = await hmacVerifyHex(secret, parsed.sessionId, parsed.signature);
    if (!signatureValid) return null;
    const raw = await kv.get(sessionKey(parsed.sessionId));
    if (!raw) return null;
    const record = JSON.parse(raw);
    return { ...record, sessionId: parsed.sessionId };
}

async function destroySession(kv, sessionId) {
    await kv.delete(sessionKey(sessionId));
}

function buildSessionCookie(sessionId, signature, maxAgeSeconds = SESSION_MAX_AGE_SECONDS) {
    const value = `${sessionId}.${signature}`;
    return `${SESSION_COOKIE_NAME}=${value}; Path=/; HttpOnly; Secure; SameSite=None; Max-Age=${maxAgeSeconds}`;
}

function buildClearSessionCookie() {
    return `${SESSION_COOKIE_NAME}=; Path=/; HttpOnly; Secure; SameSite=None; Max-Age=0`;
}

function readCookie(request, name) {
    const header = request.headers.get("Cookie") || "";
    const match = header.match(new RegExp(`(?:^|;\\s*)${name}=([^;]+)`));
    return match ? decodeURIComponent(match[1]) : null;
}

/* lib/db.js */
// lib/db.js
//
// Thin helpers over the D1 binding. Every write here is the source of truth;
// lib/sync.js is responsible for mirroring the relevant subset into KV
// afterwards. Nothing in this file touches KV.

function newId(prefix) {
    // Not cryptographically sensitive (license/build/ban IDs are not secrets,
    // the license *key* itself is the secret) — just needs to be unique.
    const bytes = crypto.getRandomValues(new Uint8Array(9));
    const b64 = btoa(String.fromCharCode(...bytes)).replace(/[+/=]/g, "").slice(0, 12);
    return `${prefix}_${b64}`;
}

async function getProductByName(db, name) {
    return db.prepare("SELECT * FROM products WHERE name = ?").bind(name).first();
}

async function getProductById(db, id) {
    return db.prepare("SELECT * FROM products WHERE id = ?").bind(id).first();
}

async function listProducts(db) {
    const { results } = await db.prepare("SELECT * FROM products ORDER BY created_at DESC").all();
    return results || [];
}

async function listAllowedProductNames(db) {
    const { results } = await db.prepare("SELECT name FROM products WHERE active = 1").all();
    return (results || []).map((r) => r.name);
}

async function listLegacyProductNames(db) {
    const { results } = await db.prepare("SELECT name FROM products WHERE mode = 'legacy'").all();
    return (results || []).map((r) => r.name);
}

async function getLicenseByKey(db, licenseKey) {
    return db.prepare("SELECT * FROM licenses WHERE license_key = ?").bind(licenseKey).first();
}

async function getLicenseById(db, id) {
    return db.prepare("SELECT * FROM licenses WHERE id = ?").bind(id).first();
}

async function listLicensesForProduct(db, productId) {
    const { results } = await db
        .prepare("SELECT * FROM licenses WHERE product_id = ? ORDER BY created_at DESC")
        .bind(productId)
        .all();
    return results || [];
}

async function listLicenses(db) {
    const { results } = await db
        .prepare(
            `SELECT licenses.*, products.name AS product_name, products.display_name AS product_display_name
             FROM licenses JOIN products ON products.id = licenses.product_id
             ORDER BY licenses.created_at DESC`
        )
        .all();
    return results || [];
}

async function getBuildByHash(db, productId, jarHash) {
    return db
        .prepare("SELECT * FROM builds WHERE product_id = ? AND jar_hash = ?")
        .bind(productId, jarHash)
        .first();
}

async function listBuilds(db) {
    const { results } = await db
        .prepare(
            `SELECT builds.*, products.name AS product_name, products.display_name AS product_display_name
             FROM builds JOIN products ON products.id = builds.product_id
             ORDER BY builds.created_at DESC`
        )
        .all();
    return results || [];
}

async function listServerBans(db) {
    const { results } = await db
        .prepare(
            `SELECT server_bans.*, products.name AS product_name, products.display_name AS product_display_name
             FROM server_bans LEFT JOIN products ON products.id = server_bans.product_id
             ORDER BY server_bans.created_at DESC`
        )
        .all();
    return results || [];
}

async function findGlobalBan(db, identifier) {
    return db
        .prepare(
            "SELECT * FROM server_bans WHERE product_id IS NULL AND identifier = ? AND status = 'active'"
        )
        .bind(identifier)
        .first();
}

async function findProductBan(db, productId, identifier) {
    return db
        .prepare(
            "SELECT * FROM server_bans WHERE product_id = ? AND identifier = ? AND status = 'active'"
        )
        .bind(productId, identifier)
        .first();
}

async function insertAuditLog(db, { adminEmail, action, targetType, targetId, ip, details }) {
    await db
        .prepare(
            `INSERT INTO admin_audit_log (id, created_at, admin_email, action, target_type, target_id, ip, details)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
        )
        .bind(newId("evt"), Date.now(), adminEmail || "", action, targetType, targetId || "", ip || "", details || "")
        .run();
}

async function listAuditLog(db, limit = 200) {
    const { results } = await db
        .prepare("SELECT * FROM admin_audit_log ORDER BY created_at DESC LIMIT ?")
        .bind(limit)
        .all();
    return results || [];
}

async function upsertServerSighting(db, { productId, address, version, country, players = 0 }) {
    const id = await sha256HexLocal(`${productId}:${address}`);
    const now = Date.now();
    await db
        .prepare(
            `INSERT INTO server_sightings (id, product_id, address, version, last_seen_at, first_seen_at, country, players)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)
             ON CONFLICT(id) DO UPDATE SET
                version = excluded.version,
                last_seen_at = excluded.last_seen_at,
                country = excluded.country,
                players = excluded.players`
        )
        .bind(id, productId, address || "", version || "", now, now, country || "", players)
        .run();
}

async function countOnlineServersByProduct(db, windowMinutes = 10) {
    const threshold = Date.now() - windowMinutes * 60 * 1000;
    const { results } = await db
        .prepare(
            `SELECT product_id, COUNT(*) as count
             FROM server_sightings
             WHERE last_seen_at >= ?
             GROUP BY product_id`
        )
        .bind(threshold)
        .all();
    const map = {};
    for (const row of results || []) {
        map[row.product_id] = Number(row.count);
    }
    return map;
}

async function listServerSightings(db) {
    const { results } = await db
        .prepare(
            `SELECT server_sightings.*, products.name AS product_name, products.display_name AS product_display_name
             FROM server_sightings JOIN products ON products.id = server_sightings.product_id
             ORDER BY last_seen_at DESC LIMIT 500`
        )
        .all();
    return results || [];
}

async function sha256HexLocal(value) {
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
    return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/* lib/email.js */
// lib/email.js
//
// Uses Resend's HTTP API (https://resend.com/docs/api-reference/emails/send-email).
// Resend has a free tier (no credit card needed to start) and the API is a
// single fetch call — no SDK, no Node-only dependency, works fine in a
// Worker. Swapping to another provider only requires changing this file —
// nothing else in the Worker depends on the provider's shape.

async function sendVerificationCodeEmail(env, toEmail, code) {
    const res = await fetch("https://api.resend.com/emails", {
        method: "POST",
        headers: {
            Authorization: `Bearer ${env.EMAIL_API_KEY}`,
            "Content-Type": "application/json",
        },
        body: JSON.stringify({
            from: env.EMAIL_FROM,
            to: [toEmail],
            subject: "Your verification code",
            text: `Your HubertStudios License Manager verification code is: ${code}\n\nThis code expires in 10 minutes. If you did not request this, you can ignore this email.`,
            html: `<p>Your HubertStudios License Manager verification code is:</p><p style="font-size:28px;font-weight:700;letter-spacing:4px">${code}</p><p>This code expires in 10 minutes. If you did not request this, you can ignore this email.</p>`,
        }),
    });

    if (!res.ok) {
        const text = await res.text().catch(() => "");
        throw new Error(`Email send failed (${res.status}): ${text}`);
    }
}

/* lib/middleware.js */
// lib/middleware.js


const MUTATING_METHODS = new Set(["POST", "PUT", "PATCH", "DELETE"]);

/**
 * Requires a valid session for ALL admin requests, and additionally requires
 * a matching X-CSRF-Token header for mutating methods. Returns either an
 * error Response (caller should return it immediately) or null if the
 * request is authorized to proceed (the resolved session and IP are passed
 * to the handler via the third/fourth callback args).
 */
async function requireAdminSession(request, env) {
    const cookieValue = readCookie(request, SESSION_COOKIE_NAME);
    const session = await resolveSession(env.KV, env.SESSION_SECRET, cookieValue);
    if (!session) {
        return { error: json({ ok: false, error: "Not authenticated." }, 401) };
    }

    if (MUTATING_METHODS.has(request.method)) {
        const csrfHeader = request.headers.get("X-CSRF-Token") || "";
        if (!csrfHeader || csrfHeader !== session.csrfToken) {
            return { error: json({ ok: false, error: "Invalid or missing CSRF token." }, 403) };
        }
    }

    return { session, ip: getClientIp(request) };
}

/* lib/sync.js */
// lib/sync.js
//
// Mirrors D1 state into KV after a successful D1 write. D1 is always written
// first by the caller; these functions are called afterwards and must not be
// treated as a substitute for the D1 write itself. If a KV write fails here,
// the D1 row is still correct — the KV cache will self-heal on the next sync
// call (e.g. next edit of the same row).
//
// Never write license keys, build hashes, or ban identifiers to KV without
// having just written the same data to D1 in the same request.


/** Recomputes and writes ALLOWED_PRODUCTS and LEGACY_PRODUCTS from D1. Call after any product create/update/delete. */
async function syncProductLists(db, kv) {
    const allowed = await listAllowedProductNames(db);
    const legacy = await listLegacyProductNames(db);
    await kv.put(ALLOWED_PRODUCTS_KEY, JSON.stringify({ products: allowed }));
    await kv.put(LEGACY_PRODUCTS_KEY, JSON.stringify({ products: legacy }));
}

/** Writes the per-product DISABLED and REQUIRE_HASH flags for a single product row. */
async function syncProductFlags(kv, product) {
    await kv.put(productDisabledKey(product.name), product.active ? "false" : "true");
    await kv.put(productRequireHashKey(product.name), product.require_hash ? "true" : "false");
}

/** Removes a product's KV-cached flags (call on product delete, in addition to syncProductLists). */
async function removeProductFlags(kv, product) {
    await kv.delete(productDisabledKey(product.name));
    await kv.delete(productRequireHashKey(product.name));
}

function licenseToKvRecord(license) {
    return {
        id: license.id,
        active: license.status === "active",
        status: license.status,
        reason:
            license.status === "revoked"
                ? "License has been revoked."
                : license.status === "expired"
                ? "License has expired."
                : undefined,
        expiresAt: license.expires_at || null,
        allowedServers: license.allowed_servers ? JSON.parse(license.allowed_servers) : null,
        blockedServers: license.blocked_servers ? JSON.parse(license.blocked_servers) : null,
    };
}

/** Writes a single license to its correct KV key (legacy LICENSE: or modern <product>.license:). */
async function syncLicense(kv, product, license) {
    const key = product.mode === "legacy" ? legacyLicenseKey(license.license_key) : modernLicenseKey(product.name, license.license_key);
    await kv.put(key, JSON.stringify(licenseToKvRecord(license)));
}

/** Removes a license from KV under both possible key shapes (covers a product mode change after the license was cached under the old shape). */
async function removeLicense(kv, product, license) {
    await kv.delete(legacyLicenseKey(license.license_key));
    await kv.delete(modernLicenseKey(product.name, license.license_key));
}

function buildToKvRecord(build) {
    return {
        id: build.id,
        active: !!build.active,
        version: build.version,
        reason: build.reason || undefined,
    };
}

async function syncBuild(kv, product, build) {
    await kv.put(buildKey(product.name, build.jar_hash), JSON.stringify(buildToKvRecord(build)));
}

async function removeBuild(kv, product, build) {
    await kv.delete(buildKey(product.name, build.jar_hash));
}

function banToKvRecord(ban) {
    return {
        id: ban.id,
        reason: ban.reason || "Banned.",
        expiresAt: ban.expires_at || null,
    };
}

/** Writes a ban to the correct global/plugin-specific KV key based on whether product_id is set. */
async function syncBan(kv, ban, product) {
    if (ban.product_id && product) {
        await kv.put(pluginBanKey(product.name, ban.identifier), JSON.stringify(banToKvRecord(ban)));
    } else {
        await kv.put(globalBanKey(ban.identifier), JSON.stringify(banToKvRecord(ban)));
    }
}

async function removeBan(kv, ban, product) {
    if (ban.product_id && product) {
        await kv.delete(pluginBanKey(product.name, ban.identifier));
    } else {
        await kv.delete(globalBanKey(ban.identifier));
    }
}

async function syncGlobalRequireHash(kv, enabled) {
    await kv.put(GLOBAL_REQUIRE_HASH_KEY, enabled ? "true" : "false");
}

/* lib/turnstile.js */
// lib/turnstile.js

/**
 * Verifies a Turnstile token server-side against Cloudflare's siteverify
 * endpoint. Must be called on every login attempt — the client-side widget
 * alone proves nothing, since a request can be replayed/forged without it.
 */
async function verifyTurnstile(secretKey, token, remoteIp) {
    if (!token) return false;
    try {
        const body = new URLSearchParams();
        body.set("secret", secretKey);
        body.set("response", token);
        if (remoteIp) body.set("remoteip", remoteIp);

        const res = await fetch("https://challenges.cloudflare.com/turnstile/v0/siteverify", {
            method: "POST",
            headers: { "Content-Type": "application/x-www-form-urlencoded" },
            body,
        });
        if (!res.ok) return false;
        const data = await res.json();
        return data.success === true;
    } catch {
        return false;
    }
}

/* routes/auth.js */
// routes/auth.js


const CHALLENGE_COOKIE_NAME = "__Host-hs_challenge";

/**
 * POST /api/auth/login
 * body: { email, password, turnstileToken }
 *
 * On success, creates a pending verification challenge, emails a 6-digit
 * code, and sets a short-lived cookie carrying the challenge ID (the code
 * itself is never put in a cookie or returned to the client).
 *
 * Always responds with the same generic error for wrong email OR wrong
 * password OR missing Turnstile — never reveals which one failed.
 */
async function handleLogin(request, env) {
    const ip = getClientIp(request);

    if (await isIpBlocked(env.KV, ip)) {
        return json({ ok: false, error: "Too many attempts. Try again later." }, 429);
    }

    let body;
    try {
        body = await request.json();
    } catch {
        return json({ ok: false, error: "Invalid request body." }, 400);
    }

    const email = typeof body.email === "string" ? body.email.trim().toLowerCase() : "";
    const password = typeof body.password === "string" ? body.password : "";
    const turnstileToken = typeof body.turnstileToken === "string" ? body.turnstileToken : "";

    const turnstileOk = await verifyTurnstile(env.TURNSTILE_SECRET_KEY, turnstileToken, ip);
    if (!turnstileOk) {
        return json({ ok: false, error: "Security check failed. Please retry." }, 400);
    }

    // Always run the password derivation, even if the email doesn't match,
    // so response timing doesn't reveal whether the email was recognized.
    const expectedEmail = String(env.ADMIN_EMAIL || "").trim().toLowerCase();
    const emailMatches = email.length > 0 && email === expectedEmail;
    const passwordOk = await verifyPassword(password, env.ADMIN_PASSWORD_HASH, env.ADMIN_PASSWORD_SALT);

    if (!emailMatches || !passwordOk) {
        await recordFailedLogin(env.KV, ip);
        return authError();
    }

    await clearFailedLogin(env.KV, ip);

    const code = randomDigits(6);
    const challengeId = await createVerificationChallenge(env.KV, env.SESSION_SECRET, expectedEmail, code);

    try {
        await sendVerificationCodeEmail(env, expectedEmail, code);
    } catch (err) {
        // Don't leak provider error details to the client.
        return json({ ok: false, error: "Could not send verification email. Try again shortly." }, 502);
    }

    await insertAuditLog(env.DB, {
        adminEmail: expectedEmail,
        action: "auth.login.code_sent",
        targetType: "Auth",
        targetId: "",
        ip,
        details: "Email/password verified; verification code sent.",
    });

    return json({ ok: true, step: "verify" }, 200, {
        "Set-Cookie": `${CHALLENGE_COOKIE_NAME}=${challengeId}; Path=/; HttpOnly; Secure; SameSite=None; Max-Age=600`,
    });
}

/**
 * POST /api/auth/verify
 * body: { code }
 *
 * Reads the challenge ID from the cookie set by /login. Compares the HMAC of
 * the submitted code against the stored hash. On success, deletes the
 * challenge (single use) and issues the real session cookie.
 */
async function handleVerify(request, env) {
    const ip = getClientIp(request);

    if (await isIpBlocked(env.KV, ip)) {
        return json({ ok: false, error: "Too many attempts. Try again later." }, 429);
    }

    const challengeId = readCookie(request, CHALLENGE_COOKIE_NAME);
    if (!challengeId) {
        return json({ ok: false, error: "Verification session expired. Please log in again." }, 400);
    }

    let body;
    try {
        body = await request.json();
    } catch {
        return json({ ok: false, error: "Invalid request body." }, 400);
    }

    const code = typeof body.code === "string" ? body.code.trim() : "";
    if (!/^\d{6}$/.test(code)) {
        await recordFailedCode(env.KV, ip);
        return json({ ok: false, error: "Invalid verification code." }, 400);
    }

    const challenge = await getVerificationChallenge(env.KV, challengeId);
    if (!challenge) {
        return json({ ok: false, error: "Verification code expired. Please log in again." }, 400);
    }

    const codeMatches = await hmacVerifyHex(env.SESSION_SECRET, code, challenge.codeHash);
    if (!codeMatches) {
        await recordFailedCode(env.KV, ip);
        return json({ ok: false, error: "Invalid verification code." }, 400);
    }

    await clearFailedCode(env.KV, ip);
    await deleteVerificationChallenge(env.KV, challengeId);

    const { sessionId, signature } = await createSession(env.KV, env.SESSION_SECRET, challenge.adminEmail);

    await insertAuditLog(env.DB, {
        adminEmail: challenge.adminEmail,
        action: "auth.login.success",
        targetType: "Auth",
        targetId: "",
        ip,
        details: "Verification code accepted; session created.",
    });

    const responseHeaders = new Headers({
        "Content-Type": "application/json",
        ...SECURITY_HEADERS,
    });
    responseHeaders.append("Set-Cookie", buildSessionCookie(sessionId, signature));
    responseHeaders.append(
        "Set-Cookie",
        `${CHALLENGE_COOKIE_NAME}=; Path=/; HttpOnly; Secure; SameSite=None; Max-Age=0`
    );
    return new Response(JSON.stringify({ ok: true }), { status: 200, headers: responseHeaders });
}

/** POST /api/auth/logout */
async function handleLogout(request, env) {
    const cookieValue = readCookie(request, SESSION_COOKIE_NAME);
    if (cookieValue) {
        const [sessionId] = cookieValue.split(".");
        if (sessionId) await destroySession(env.KV, sessionId);
    }
    return json({ ok: true }, 200, { "Set-Cookie": buildClearSessionCookie() });
}

/** GET /api/auth/session — used by the dashboard to check if the cookie is still valid. */
async function handleSessionCheck(request, env) {
    const cookieValue = readCookie(request, SESSION_COOKIE_NAME);
    const session = await resolveSession(env.KV, env.SESSION_SECRET, cookieValue);
    if (!session) return json({ ok: false }, 401);
    return json({ ok: true, email: session.adminEmail, csrfToken: session.csrfToken });
}

/* routes/bans.js */
// routes/bans.js


const BAN_DURATION_MS = {
    "1 day": 1 * 24 * 60 * 60 * 1000,
    "7 days": 7 * 24 * 60 * 60 * 1000,
    "30 days": 30 * 24 * 60 * 60 * 1000,
    "90 days": 90 * 24 * 60 * 60 * 1000,
    "6 months": 182 * 24 * 60 * 60 * 1000,
    "1 year": 365 * 24 * 60 * 60 * 1000,
};

function banToApiShape(row) {
    return {
        id: row.id,
        plugin: row.product_display_name || null,
        productId: row.product_id || null,
        identifier: row.identifier,
        reason: row.reason,
        until: row.expires_at ? new Date(row.expires_at).toISOString().slice(0, 10) : null,
        status: row.status,
    };
}

async function handleListBans(env) {
    const rows = await listServerBans(env.DB);
    return json({ ok: true, bans: rows.map(banToApiShape) });
}

async function handleCreateBan(request, env, session, ip) {
    const body = await safeJsonBans(request);
    if (!body) return json({ ok: false, error: "Invalid request body." }, 400);

    const identifier = String(body.identifier || "").trim();
    if (!identifier) return json({ ok: false, error: "IP or domain is required." }, 400);
    const reason = String(body.reason || "").trim();

    let productId = null;
    let product = null;
    if (body.productId) {
        product = await getProductById(env.DB, body.productId);
        if (!product) return json({ ok: false, error: "Plugin not found." }, 400);
        productId = product.id;
    }

    let expiresAt = null;
    if (body.permanent === false) {
        if (body.until) {
            const parsed = Date.parse(body.until);
            expiresAt = Number.isNaN(parsed) ? null : parsed;
        } else if (body.duration && BAN_DURATION_MS[body.duration]) {
            expiresAt = Date.now() + BAN_DURATION_MS[body.duration];
        }
    }

    const id = newId("ban");
    const now = Date.now();
    await env.DB.prepare(
        `INSERT INTO server_bans (id, product_id, identifier, reason, expires_at, status, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, 'active', ?, ?)`
    )
        .bind(id, productId, identifier.toLowerCase(), reason, expiresAt, now, now)
        .run();

    const row = { id, product_id: productId, identifier: identifier.toLowerCase(), reason, expires_at: expiresAt, status: "active" };
    await syncBan(env.KV, row, product);

    await insertAuditLog(env.DB, {
        adminEmail: session.adminEmail,
        action: "server_ban.create",
        targetType: "ServerBan",
        targetId: id,
        ip,
        details: `${productId ? `Plugin ban for ${product.display_name}` : "Global ban"} added for ${identifier}.`,
    });

    return json(
        { ok: true, ban: banToApiShape({ ...row, product_display_name: product?.display_name }) },
        201
    );
}

async function handleUpdateBan(request, env, session, ip, id) {
    const existing = await env.DB.prepare("SELECT * FROM server_bans WHERE id = ?").bind(id).first();
    if (!existing) return json({ ok: false, error: "Ban not found." }, 404);
    const product = existing.product_id ? await getProductById(env.DB, existing.product_id) : null;

    const body = await safeJsonBans(request);
    if (!body) return json({ ok: false, error: "Invalid request body." }, 400);

    const reason = body.reason !== undefined ? String(body.reason) : existing.reason;
    const status = body.active !== undefined ? (body.active ? "active" : "expired") : existing.status;

    let expiresAt = existing.expires_at;
    if (body.permanent === true) expiresAt = null;
    else if (body.until) {
        const parsed = Date.parse(body.until);
        expiresAt = Number.isNaN(parsed) ? expiresAt : parsed;
    } else if (body.duration && BAN_DURATION_MS[body.duration]) {
        expiresAt = Date.now() + BAN_DURATION_MS[body.duration];
    }

    await env.DB.prepare(`UPDATE server_bans SET reason = ?, expires_at = ?, status = ?, updated_at = ? WHERE id = ?`)
        .bind(reason, expiresAt, status, Date.now(), id)
        .run();

    const row = await env.DB.prepare("SELECT * FROM server_bans WHERE id = ?").bind(id).first();
    if (status === "active") {
        await syncBan(env.KV, row, product);
    } else {
        await removeBan(env.KV, row, product);
    }

    await insertAuditLog(env.DB, {
        adminEmail: session.adminEmail,
        action: "server_ban.update",
        targetType: "ServerBan",
        targetId: id,
        ip,
        details: `Updated ban for ${existing.identifier}.`,
    });

    return json({ ok: true, ban: banToApiShape({ ...row, product_display_name: product?.display_name }) });
}

async function handleDeleteBan(env, session, ip, id) {
    const existing = await env.DB.prepare("SELECT * FROM server_bans WHERE id = ?").bind(id).first();
    if (!existing) return json({ ok: false, error: "Ban not found." }, 404);
    const product = existing.product_id ? await getProductById(env.DB, existing.product_id) : null;

    await env.DB.prepare("DELETE FROM server_bans WHERE id = ?").bind(id).run();
    await removeBan(env.KV, existing, product);

    await insertAuditLog(env.DB, {
        adminEmail: session.adminEmail,
        action: "server_ban.delete",
        targetType: "ServerBan",
        targetId: id,
        ip,
        details: `Removed ban for ${existing.identifier}.`,
    });

    return json({ ok: true });
}

async function safeJsonBans(request) {
    try {
        return await request.json();
    } catch {
        return null;
    }
}

/* routes/builds.js */
// routes/builds.js


function buildToApiShape(row) {
    return {
        id: row.id,
        plugin: row.product_display_name || row.product_name,
        productId: row.product_id,
        hash: row.jar_hash,
        version: row.version,
        active: !!row.active,
        created: new Date(row.created_at).toISOString().slice(0, 10),
        reason: row.reason || undefined,
    };
}

async function handleListBuilds(env) {
    const rows = await listBuilds(env.DB);
    return json({ ok: true, builds: rows.map(buildToApiShape) });
}

async function handleCreateBuild(request, env, session, ip) {
    const body = await safeJsonBuilds(request);
    if (!body) return json({ ok: false, error: "Invalid request body." }, 400);

    const productId = String(body.productId || "");
    const product = await getProductById(env.DB, productId);
    if (!product) return json({ ok: false, error: "Plugin not found." }, 400);

    const hash = String(body.hash || "").trim().toLowerCase();
    if (!isSha256Hex(hash)) {
        return json({ ok: false, error: "Hash must be a 64-character lowercase SHA-256 hex string." }, 400);
    }
    const version = String(body.version || "").trim();
    const active = body.active !== false;
    const reason = String(body.reason || "");

    const id = newId("bld");
    const now = Date.now();
    try {
        await env.DB.prepare(
            `INSERT INTO builds (id, product_id, jar_hash, version, active, reason, created_at, updated_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
        )
            .bind(id, productId, hash, version, active ? 1 : 0, reason, now, now)
            .run();
    } catch (e) {
        if (String(e.message || "").includes("UNIQUE")) {
            return json({ ok: false, error: "That hash is already registered for this plugin." }, 409);
        }
        throw e;
    }

    const row = { id, product_id: productId, jar_hash: hash, version, active: active ? 1 : 0, reason, created_at: now };
    await syncBuild(env.KV, product, row);

    await insertAuditLog(env.DB, {
        adminEmail: session.adminEmail,
        action: "build.create",
        targetType: "Build",
        targetId: id,
        ip,
        details: `Registered official build ${product.display_name} ${version}.`,
    });

    return json({ ok: true, build: buildToApiShape({ ...row, product_name: product.name, product_display_name: product.display_name }) }, 201);
}

async function handleUpdateBuild(request, env, session, ip, id) {
    const existing = await env.DB.prepare("SELECT * FROM builds WHERE id = ?").bind(id).first();
    if (!existing) return json({ ok: false, error: "Build not found." }, 404);
    const product = await getProductById(env.DB, existing.product_id);

    const body = await safeJsonBuilds(request);
    if (!body) return json({ ok: false, error: "Invalid request body." }, 400);

    const version = body.version !== undefined ? String(body.version).trim() : existing.version;
    const active = body.active !== undefined ? !!body.active : !!existing.active;
    const reason = body.reason !== undefined ? String(body.reason) : existing.reason;

    await env.DB.prepare(`UPDATE builds SET version = ?, active = ?, reason = ?, updated_at = ? WHERE id = ?`)
        .bind(version, active ? 1 : 0, reason, Date.now(), id)
        .run();

    const row = await env.DB.prepare("SELECT * FROM builds WHERE id = ?").bind(id).first();
    if (product) await syncBuild(env.KV, product, row);

    await insertAuditLog(env.DB, {
        adminEmail: session.adminEmail,
        action: "build.update",
        targetType: "Build",
        targetId: id,
        ip,
        details: `Updated build ${product ? product.display_name : ""} ${version}.${!active ? " Marked inactive." : ""}`,
    });

    return json({ ok: true, build: buildToApiShape({ ...row, product_name: product?.name, product_display_name: product?.display_name }) });
}

async function handleDeleteBuild(env, session, ip, id) {
    const existing = await env.DB.prepare("SELECT * FROM builds WHERE id = ?").bind(id).first();
    if (!existing) return json({ ok: false, error: "Build not found." }, 404);
    const product = await getProductById(env.DB, existing.product_id);

    await env.DB.prepare("DELETE FROM builds WHERE id = ?").bind(id).run();
    if (product) await removeBuild(env.KV, product, existing);

    await insertAuditLog(env.DB, {
        adminEmail: session.adminEmail,
        action: "build.delete",
        targetType: "Build",
        targetId: id,
        ip,
        details: `Deleted build ${existing.jar_hash.slice(0, 12)}... (${existing.version}).`,
    });

    return json({ ok: true });
}

async function safeJsonBuilds(request) {
    try {
        return await request.json();
    } catch {
        return null;
    }
}

/* routes/licenses.js */
// routes/licenses.js


const LICENSE_DURATION_MS = {
    "30 days": 30 * 24 * 60 * 60 * 1000,
    "3 months": 90 * 24 * 60 * 60 * 1000,
    "6 months": 182 * 24 * 60 * 60 * 1000,
    "1 year": 365 * 24 * 60 * 60 * 1000,
    Lifetime: null,
};

function licenseToApiShape(row) {
    return {
        id: row.id,
        plugin: row.product_display_name || row.product_name,
        productId: row.product_id,
        key: row.license_key,
        label: row.label,
        status: row.status,
        duration: row.duration_label,
        expiry: row.expires_at ? new Date(row.expires_at).toISOString().slice(0, 10) : null,
        created: new Date(row.created_at).toISOString().slice(0, 10),
        servers: 0,
        notes: row.notes,
    };
}

async function handleListLicenses(env) {
    const rows = await listLicenses(env.DB);
    return json({ ok: true, licenses: rows.map(licenseToApiShape) });
}

function generateLicenseKey(prefix) {
    // e.g. AUTH-9F2K-7H3M-QX84-LP21 style — readable, grouped, uppercase.
    const raw = randomToken(10).toUpperCase().replace(/[^A-Z0-9]/g, "");
    const groups = raw.match(/.{1,4}/g) || [raw];
    const cleanPrefix = (prefix || "LIC").toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, 4) || "LIC";
    return `${cleanPrefix}-${groups.slice(0, 4).join("-")}`;
}

async function handleCreateLicense(request, env, session, ip) {
    const body = await safeJsonLicenses(request);
    if (!body) return json({ ok: false, error: "Invalid request body." }, 400);

    const productId = String(body.productId || "");
    const product = await getProductById(env.DB, productId);
    if (!product) return json({ ok: false, error: "Plugin not found." }, 400);

    const label = String(body.label || "").trim() || "Global Key";
    const durationLabel = Object.keys(LICENSE_DURATION_MS).includes(body.duration) ? body.duration : "Lifetime";
    const active = body.active !== false;
    const notes = String(body.notes || "");

    const key = String(body.key || "").trim() || generateLicenseKey(product.name);
    const now = Date.now();
    const expiresAt = LICENSE_DURATION_MS[durationLabel] ? now + LICENSE_DURATION_MS[durationLabel] : null;

    const id = newId("lic");
    try {
        await env.DB.prepare(
            `INSERT INTO licenses (id, product_id, license_key, label, status, duration_label, expires_at, notes, created_at, updated_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
        )
            .bind(id, productId, key, label, active ? "active" : "revoked", durationLabel, expiresAt, notes, now, now)
            .run();
    } catch (e) {
        if (String(e.message || "").includes("UNIQUE")) {
            return json({ ok: false, error: "That license key already exists." }, 409);
        }
        throw e;
    }

    const row = await getLicenseById(env.DB, id);
    await syncLicense(env.KV, product, row);

    await insertAuditLog(env.DB, {
        adminEmail: session.adminEmail,
        action: "license.create",
        targetType: "License",
        targetId: id,
        ip,
        details: `Created ${durationLabel} key "${label}" for ${product.display_name}.`,
    });

    return json({ ok: true, license: { ...licenseToApiShape({ ...row, product_name: product.name, product_display_name: product.display_name }) } }, 201);
}

async function handleUpdateLicense(request, env, session, ip, id) {
    const existing = await getLicenseById(env.DB, id);
    if (!existing) return json({ ok: false, error: "License not found." }, 404);
    const product = await getProductById(env.DB, existing.product_id);

    const body = await safeJsonLicenses(request);
    if (!body) return json({ ok: false, error: "Invalid request body." }, 400);

    const label = body.label !== undefined ? String(body.label).trim() : existing.label;
    const notes = body.notes !== undefined ? String(body.notes) : existing.notes;
    let status = existing.status;
    if (body.status && ["active", "expired", "revoked"].includes(body.status)) status = body.status;
    else if (body.active !== undefined) status = body.active ? "active" : "revoked";

    let durationLabel = existing.duration_label;
    let expiresAt = existing.expires_at;
    if (body.duration && Object.keys(LICENSE_DURATION_MS).includes(body.duration) && body.duration !== existing.duration_label) {
        durationLabel = body.duration;
        expiresAt = LICENSE_DURATION_MS[durationLabel] ? Date.now() + LICENSE_DURATION_MS[durationLabel] : null;
    }

    await env.DB.prepare(
        `UPDATE licenses SET label = ?, status = ?, duration_label = ?, expires_at = ?, notes = ?, updated_at = ? WHERE id = ?`
    )
        .bind(label, status, durationLabel, expiresAt, notes, Date.now(), id)
        .run();

    const row = await getLicenseById(env.DB, id);
    await syncLicense(env.KV, product, row);

    await insertAuditLog(env.DB, {
        adminEmail: session.adminEmail,
        action: status === "revoked" && existing.status !== "revoked" ? "license.revoke" : "license.update",
        targetType: "License",
        targetId: id,
        ip,
        details: `Updated license "${label}" for ${product.display_name}.`,
    });

    return json({ ok: true, license: licenseToApiShape({ ...row, product_name: product.name, product_display_name: product.display_name }) });
}

async function handleDeleteLicense(env, session, ip, id) {
    const existing = await getLicenseById(env.DB, id);
    if (!existing) return json({ ok: false, error: "License not found." }, 404);
    const product = await getProductById(env.DB, existing.product_id);

    await env.DB.prepare("DELETE FROM licenses WHERE id = ?").bind(id).run();
    if (product) await removeLicense(env.KV, product, existing);

    await insertAuditLog(env.DB, {
        adminEmail: session.adminEmail,
        action: "license.delete",
        targetType: "License",
        targetId: id,
        ip,
        details: `Deleted license "${existing.label}".`,
    });

    return json({ ok: true });
}

async function safeJsonLicenses(request) {
    try {
        return await request.json();
    } catch {
        return null;
    }
}

/* routes/misc.js */
// routes/misc.js


async function handleOverview(env) {
    const products = await listProducts(env.DB);
    const licenses = await listLicenses(env.DB);
    const sightings = await listServerSightings(env.DB);

    const activeProducts = products.filter((p) => p.active).length;
    const activeLicenses = licenses.filter((l) => l.status === "active").length;
    const onlineServers = sightings.filter((s) => Date.now() - s.last_seen_at < 10 * 60 * 1000).length;

    return json({
        ok: true,
        stats: {
            totalPlugins: products.length,
            activePlugins: activeProducts,
            totalLicenses: licenses.length,
            activeLicenses,
            onlineServers,
            totalServersSeen: sightings.length,
        },
    });
}

function sightingToApiShape(row) {
    const ageMs = Date.now() - row.last_seen_at;
    return {
        id: row.id,
        address: row.address,
        plugin: row.product_display_name || row.product_name,
        version: row.version,
        players: 0, // not collected by /api/validate; reserved for a future heartbeat field
        status: ageMs < 10 * 60 * 1000 ? "online" : "offline",
        lastSeen: humanizeAge(ageMs),
        country: row.country || "Unknown",
    };
}

function humanizeAge(ms) {
    if (ms < 60_000) return "Just now";
    if (ms < 3_600_000) return `${Math.floor(ms / 60_000)} min ago`;
    if (ms < 86_400_000) return `${Math.floor(ms / 3_600_000)} hours ago`;
    return `${Math.floor(ms / 86_400_000)} days ago`;
}

async function handleActiveServers(env) {
    const rows = await listServerSightings(env.DB);
    return json({ ok: true, servers: rows.map(sightingToApiShape) });
}

function auditToApiShape(row) {
    return {
        id: row.id,
        time: new Date(row.created_at).toISOString().replace("T", " ").slice(0, 19),
        admin: row.admin_email,
        action: row.action,
        targetType: row.target_type,
        targetId: row.target_id,
        ip: row.ip,
        details: row.details,
    };
}

async function handleAuditLog(env) {
    const rows = await listAuditLog(env.DB, 200);
    return json({ ok: true, events: rows.map(auditToApiShape) });
}

/* routes/products.js */
// routes/products.js
//
// NOTE on images: there is no R2 bucket in this deployment. Plugin images
// are stored as a base64 data URL (e.g. "data:image/png;base64,....")
// directly in the products.image_data column and returned inline in the
// JSON response as `image`. The dashboard can put that string straight into
// an <img src="..."> tag — no separate image-serving route needed.
//
// Trade-off: D1 rows have practical size limits and this bloats the table a
// bit, but plugin icons are small (a few KB to maybe 100-200KB), so for the
// expected number of plugins this is simple and avoids needing any object
// storage account at all.


const MAX_IMAGE_BYTES = 1.5 * 1024 * 1024; // ~1.5MB raw image, before base64 overhead (~2MB encoded) — keeps D1 rows reasonable

function productToApiShape(row, licenseCount = 0, serverCount = 0) {
    return {
        id: row.id,
        name: row.name,
        displayName: row.display_name,
        description: row.description,
        image: row.image_data || null,
        active: !!row.active,
        mode: row.mode,
        requireHash: !!row.require_hash,
        licenses: licenseCount,
        servers: serverCount,
    };
}

async function handleListProducts(env) {
    const rows = await listProducts(env.DB);
    const [counts, serverCountMap] = await Promise.all([
        env.DB.prepare(
            `SELECT product_id, COUNT(*) AS license_count FROM licenses GROUP BY product_id`
        ).all(),
        countOnlineServersByProduct(env.DB),
    ]);
    const countMap = new Map((counts.results || []).map((r) => [r.product_id, r.license_count]));
    return json({ ok: true, products: rows.map((r) => productToApiShape(r, countMap.get(r.id) || 0, serverCountMap[r.id] || 0)) });
}

/** Validates an incoming `image` value: must be a data: URL, and under the size cap. Returns the value to store (or null/undefined passthrough). */
function validateImageData(image, existing) {
    if (image === undefined) return { ok: true, value: existing };
    if (image === null || image === "") return { ok: true, value: null };
    if (typeof image !== "string" || !image.startsWith("data:image/")) {
        return { ok: false, error: "Image must be a data URL (data:image/...)." };
    }
    // Rough byte-size estimate from base64 length.
    const base64Part = image.split(",")[1] || "";
    const approxBytes = Math.floor((base64Part.length * 3) / 4);
    if (approxBytes > MAX_IMAGE_BYTES) {
        return { ok: false, error: "Image is too large. Please use a smaller image (under ~1.5MB)." };
    }
    return { ok: true, value: image };
}

async function handleCreateProduct(request, env, session, ip) {
    const body = await safeJsonProducts(request);
    if (!body) return json({ ok: false, error: "Invalid request body." }, 400);

    const name = String(body.name || "").trim();
    const displayName = String(body.displayName || "").trim();
    if (!name || !displayName) {
        return json({ ok: false, error: "Name and display name are required." }, 400);
    }
    if (await getProductByName(env.DB, name)) {
        return json({ ok: false, error: "A product with that name already exists." }, 409);
    }

    const imageResult = validateImageData(body.image, null);
    if (!imageResult.ok) return json({ ok: false, error: imageResult.error }, 400);

    const id = newId("prod");
    const now = Date.now();
    const mode = body.mode === "legacy" ? "legacy" : "modern";
    const active = body.active !== false;
    const requireHash = body.requireHash !== false;

    await env.DB.prepare(
        `INSERT INTO products (id, name, display_name, description, image_data, mode, active, require_hash, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
    )
        .bind(id, name, displayName, String(body.description || ""), imageResult.value, mode, active ? 1 : 0, requireHash ? 1 : 0, now, now)
        .run();

    const row = await getProductById(env.DB, id);
    await syncProductLists(env.DB, env.KV);
    await syncProductFlags(env.KV, row);

    await insertAuditLog(env.DB, {
        adminEmail: session.adminEmail,
        action: "plugin.create",
        targetType: "Plugin",
        targetId: id,
        ip,
        details: `Created plugin ${displayName} (${name}).`,
    });

    return json({ ok: true, product: productToApiShape(row, 0, 0) }, 201);
}

async function handleUpdateProduct(request, env, session, ip, id) {
    const existing = await getProductById(env.DB, id);
    if (!existing) return json({ ok: false, error: "Plugin not found." }, 404);

    const body = await safeJsonProducts(request);
    if (!body) return json({ ok: false, error: "Invalid request body." }, 400);

    const imageResult = validateImageData(body.image, existing.image_data);
    if (!imageResult.ok) return json({ ok: false, error: imageResult.error }, 400);

    const displayName = body.displayName !== undefined ? String(body.displayName).trim() : existing.display_name;
    const description = body.description !== undefined ? String(body.description) : existing.description;
    const mode = body.mode === "legacy" || body.mode === "modern" ? body.mode : existing.mode;
    const active = body.active !== undefined ? !!body.active : !!existing.active;
    const requireHash = body.requireHash !== undefined ? !!body.requireHash : !!existing.require_hash;

    await env.DB.prepare(
        `UPDATE products SET display_name = ?, description = ?, image_data = ?, mode = ?, active = ?, require_hash = ?, updated_at = ? WHERE id = ?`
    )
        .bind(displayName, description, imageResult.value, mode, active ? 1 : 0, requireHash ? 1 : 0, Date.now(), id)
        .run();

    const row = await getProductById(env.DB, id);
    await syncProductLists(env.DB, env.KV);
    await syncProductFlags(env.KV, row);

    await insertAuditLog(env.DB, {
        adminEmail: session.adminEmail,
        action: "plugin.update",
        targetType: "Plugin",
        targetId: id,
        ip,
        details: `Updated plugin ${row.display_name}.`,
    });

    return json({ ok: true, product: productToApiShape(row, 0, 0) });
}

async function handleDeleteProduct(env, session, ip, id) {
    const existing = await getProductById(env.DB, id);
    if (!existing) return json({ ok: false, error: "Plugin not found." }, 404);

    await env.DB.prepare("DELETE FROM products WHERE id = ?").bind(id).run();
    await removeProductFlags(env.KV, existing);
    await syncProductLists(env.DB, env.KV);

    await insertAuditLog(env.DB, {
        adminEmail: session.adminEmail,
        action: "plugin.delete",
        targetType: "Plugin",
        targetId: id,
        ip,
        details: `Deleted plugin ${existing.display_name}. Cascade removed its licenses, builds, and bans.`,
    });

    return json({ ok: true });
}

async function safeJsonProducts(request) {
    try {
        return await request.json();
    } catch {
        return null;
    }
}

/* routes/settings.js */
// routes/settings.js


async function handleGetSettings(env) {
    const stored = await env.KV.get(GLOBAL_REQUIRE_HASH_KEY);
    const globalRequireHash = stored === null ? true : stored === "true";
    const emailProvider = "Resend";
    const emailFrom = env.EMAIL_FROM || null;
    return json({ ok: true, settings: { globalRequireHash, emailProvider, emailFrom } });
}

async function handleUpdateSettings(request, env, session, ip) {
    let body;
    try {
        body = await request.json();
    } catch {
        return json({ ok: false, error: "Invalid request body." }, 400);
    }

    const enabled = !!body?.globalRequireHash;
    await syncGlobalRequireHash(env.KV, enabled);
    await insertAuditLog(env.DB, {
        adminEmail: session.adminEmail,
        action: "settings.update",
        targetType: "Settings",
        targetId: "globalRequireHash",
        ip,
        details: `Set globalRequireHash to ${enabled}.`,
    });

    const emailProvider = "Resend";
    const emailFrom = env.EMAIL_FROM || null;
    return json({ ok: true, settings: { globalRequireHash: enabled, emailProvider, emailFrom } });
}

/* routes/validate.js */
// routes/validate.js
//
// Implements validation in a strict order (numbered in comments below).
// Reads exclusively from KV on the hot path — KV is kept in sync with D1 by
// lib/sync.js on every admin mutation. If a KV key is unexpectedly missing
// (e.g. first deploy before any sync ran, or KV eventual-consistency lag),
// this falls back to a direct D1 read so validation never fails open due to
// cache staleness — it fails to the authoritative source instead.
//
// Important: a missing/false answer must never default to "valid". Every
// early return in this file is a rejection; the single success path is the
// last statement of the function.


async function handleValidate(request, env) {
    // Step 1: Parse JSON.
    let body;
    try {
        body = await request.json();
    } catch {
        return reject(env, { plugin: "unknown", licenseFingerprint: "", hash: "invalid" }, "Invalid request body.", 400);
    }

    // Step 2: Validate plugin name.
    const plugin = typeof body.plugin === "string" ? body.plugin.trim() : "";
    const version = typeof body.version === "string" ? body.version.trim() : "";
    const license = typeof body.license === "string" ? body.license.trim() : "";
    const licenseFingerprint =
        typeof body.licenseFingerprint === "string" ? body.licenseFingerprint.trim().toLowerCase() : "";
    const hash = typeof body.hash === "string" ? body.hash.trim().toLowerCase() : "";
    const server = typeof body.server === "object" && body.server !== null ? body.server : {};

    const ctx = { plugin: plugin || "unknown", licenseFingerprint, hash: hash || "invalid", legacyMode: false, serverFingerprintSource: null };

    if (!plugin) {
        return reject(env, ctx, "Missing plugin name.", 400);
    }

    // Step 3-4: Load ALLOWED_PRODUCTS, reject if not allowed.
    const allowedProducts = await readJsonList(env.KV, ALLOWED_PRODUCTS_KEY, "products");
    if (!allowedProducts.includes(plugin)) {
        return reject(env, ctx, "Unknown or unsupported plugin.", 403);
    }

    // Step 5-6: Load LEGACY_PRODUCTS, determine legacy mode.
    const legacyProducts = await readJsonList(env.KV, LEGACY_PRODUCTS_KEY, "products");
    ctx.legacyMode = legacyProducts.includes(plugin);

    // Step 7: Validate license fingerprint = sha256(license).
    const expectedFingerprint = await sha256Hex(license);
    ctx.licenseFingerprint = expectedFingerprint;
    if (!license || !isSha256Hex(licenseFingerprint) || licenseFingerprint !== expectedFingerprint) {
        return reject(env, ctx, "Invalid license fingerprint.", 400);
    }

    // We need the product row (id, mode, require_hash) for several later
    // steps and for the D1 fallback path, so fetch it once now.
    const product = await getProductByName(env.DB, plugin);
    if (!product) {
        return reject(env, ctx, "Unknown or unsupported plugin.", 403);
    }

    // Step 8: Check global/product disable (same flag in this implementation).
    const globalDisabled = await env.KV.get(productDisabledKey(plugin));
    const isDisabled = globalDisabled !== null ? globalDisabled === "true" : !product.active;
    if (isDisabled) {
        return reject(env, ctx, `${plugin} is currently disabled.`, 403);
    }

    // Step 9: Read the license record from the correct key shape.
    const licenseKvKey = ctx.legacyMode ? legacyLicenseKey(license) : modernLicenseKey(plugin, license);
    let licenseRecord = await readJsonOrNull(env.KV, licenseKvKey);
    if (!licenseRecord) {
        // Fallback to D1 in case KV hasn't been synced yet for this key.
        const row = await getLicenseByKey(env.DB, license);
        if (row && row.product_id === product.id) {
            licenseRecord = licenseToKvRecord(row);
        }
    }

    // Step 10: Check license exists and active.
    if (!licenseRecord) {
        return reject(env, ctx, "License does not exist.", 403);
    }
    if (!licenseRecord.active) {
        return reject(env, ctx, licenseRecord.reason || "License is disabled.", 403);
    }

    // Step 11: Check license expiry.
    if (licenseRecord.expiresAt && Date.now() > licenseRecord.expiresAt) {
        return reject(env, ctx, "License has expired.", 403);
    }

    // Step 12: Collect server identifiers. (Note: server.motd is deliberately
    // never read here, by design — MOTD must never be used for ban matching.)
    const identifiers = collectServerIdentifiers(request, server);
    ctx.serverFingerprintSource = identifiers[0] || null;

    // Step 13: Check license-specific allowedServers / blockedServers.
    if (Array.isArray(licenseRecord.allowedServers) && licenseRecord.allowedServers.length > 0) {
        const allowedLower = licenseRecord.allowedServers.map((s) => String(s).toLowerCase());
        const matchesAllowed = identifiers.some((id) => allowedLower.includes(id.toLowerCase()));
        if (!matchesAllowed) {
            return reject(env, ctx, "This server is not authorized for this license.", 403);
        }
    }
    if (Array.isArray(licenseRecord.blockedServers) && licenseRecord.blockedServers.length > 0) {
        const blockedLower = licenseRecord.blockedServers.map((s) => String(s).toLowerCase());
        const matchesBlocked = identifiers.some((id) => blockedLower.includes(id.toLowerCase()));
        if (matchesBlocked) {
            return reject(env, ctx, "This server has been blocked from using this license.", 403);
        }
    }

    // Step 14: Check plugin-specific server ban.
    for (const identifier of identifiers) {
        const banRecord = await readJsonOrNull(env.KV, pluginBanKey(plugin, identifier));
        if (banRecord) {
            return reject(env, ctx, banRecord.reason || "This server is banned.", 403);
        }
    }
    if (identifiers.length > 0) {
        const productBan = await findProductBan(env.DB, product.id, identifiers[0]);
        if (productBan) {
            return reject(env, ctx, productBan.reason || "This server is banned.", 403);
        }
    }

    // Step 15: Check global server ban.
    for (const identifier of identifiers) {
        const banRecord = await readJsonOrNull(env.KV, globalBanKey(identifier));
        if (banRecord) {
            return reject(env, ctx, banRecord.reason || "This server is banned.", 403);
        }
    }
    if (identifiers.length > 0) {
        const globalBan = await findGlobalBan(env.DB, identifiers[0]);
        if (globalBan) {
            return reject(env, ctx, globalBan.reason || "This server is banned.", 403);
        }
    }

    // Step 16: Check hash validation if enabled.
    const hashEnabled = await isHashValidationEnabled(env, plugin, product);
    if (hashEnabled) {
        if (!isSha256Hex(hash)) {
            return reject(env, ctx, "Missing or invalid JAR hash.", 400);
        }
        let buildRecord = await readJsonOrNull(env.KV, buildKey(plugin, hash));
        if (!buildRecord) {
            const row = await getBuildByHash(env.DB, product.id, hash);
            if (row) buildRecord = buildToKvRecord(row);
        }
        if (!buildRecord) {
            return reject(env, ctx, "Not an official build. Unknown JAR hash.", 403);
        }
        if (!buildRecord.active) {
            return reject(
                env,
                ctx,
                buildRecord.reason || `Version ${buildRecord.version || version || "unknown"} has been deactivated.`,
                403
            );
        }
    }

    // Best-effort presentation-data upsert for the "Active Servers" dashboard
    // page. Never allowed to affect the validation outcome — wrapped so a D1
    // hiccup here can't turn a valid license invalid.
    try {
        await upsertServerSighting(env.DB, {
            productId: product.id,
            address: identifiers[0] || "unknown",
            version: version || "unknown",
            country: request.headers.get("CF-IPCountry") || "",
        });
    } catch {
        /* presentation-only, ignore failures */
    }

    // Step 17: Return signed response.
    return accept(env, { ...ctx, hash: hashEnabled ? hash : "hash-check-disabled" }, version);
}

// ---- helpers ----

function collectServerIdentifiers(request, server) {
    const candidates = [
        request.headers.get("CF-Connecting-IP"),
        request.headers.get("X-Forwarded-For")?.split(",")[0]?.trim(),
        server.publicIp,
        server.ip,
        server.bindIp,
        server.host,
        server.hostname,
        server.domain,
        server.serverName,
    ];
    const seen = new Set();
    const out = [];
    for (const c of candidates) {
        if (typeof c !== "string") continue;
        const trimmed = c.trim();
        // Exclude obviously-useless wildcard binds; they're not a real identifier.
        if (!trimmed || trimmed === "0.0.0.0" || trimmed === "::") continue;
        const lower = trimmed.toLowerCase();
        if (seen.has(lower)) continue;
        seen.add(lower);
        out.push(trimmed);
    }
    return out;
}

async function isHashValidationEnabled(env, plugin, product) {
    const perProduct = await env.KV.get(productRequireHashKey(plugin));
    if (perProduct !== null) return perProduct === "true";
    if (typeof product.require_hash === "number") return !!product.require_hash;
    const global = await env.KV.get(GLOBAL_REQUIRE_HASH_KEY);
    return global !== "false"; // default to enabled if unset, fail safe
}

async function readJsonList(kv, key, listProp) {
    const raw = await kv.get(key);
    if (!raw) return [];
    try {
        const parsed = JSON.parse(raw);
        return Array.isArray(parsed[listProp]) ? parsed[listProp] : [];
    } catch {
        return [];
    }
}

async function readJsonOrNull(kv, key) {
    const raw = await kv.get(key);
    if (!raw) return null;
    try {
        return JSON.parse(raw);
    } catch {
        return null;
    }
}

async function reject(env, ctx, reason, status) {
    return signedResponse(env, false, ctx, reason, status, {});
}

async function accept(env, ctx, version) {
    return signedResponse(env, true, ctx, "OK", 200, { version: version || "unknown" });
}

/**
 * Builds and signs the response. Signature format:
 *   legacy:  valid:plugin:licenseFingerprint:hash:timestamp
 *   modern:  valid:plugin:licenseFingerprint:hash:serverFingerprint:blocked:code:timestamp
 *
 * Legacy products (LEGACY_PRODUCTS membership, resolved earlier into
 * ctx.legacyMode) always get the legacy format; everything else gets the
 * modern format. The two formats are not compatible prefixes of each other
 * (timestamp sits in a different position), so this is threaded through ctx
 * explicitly rather than re-derived from the response shape.
 */
async function signedResponse(env, valid, ctx, reason, status, extra) {
    const timestamp = Math.floor(Date.now() / 1000).toString();
    const blocked = !valid;
    const code = status;
    // serverFingerprint: a short, non-reversible identifier for *this*
    // validation attempt's primary server identifier, so the Java client can
    // detect if a signed response is being replayed against a different
    // server. Derived from the first collected identifier, or "none".
    const serverFingerprint = ctx.serverFingerprintSource
        ? await sha256Hex(ctx.serverFingerprintSource)
        : "none";

    const legacyFormat = ctx.legacyMode === true;
    const dataToSign = legacyFormat
        ? `${valid}:${ctx.plugin}:${ctx.licenseFingerprint}:${ctx.hash}:${timestamp}`
        : `${valid}:${ctx.plugin}:${ctx.licenseFingerprint}:${ctx.hash}:${serverFingerprint}:${blocked}:${code}:${timestamp}`;

    const signature = await signEcdsa(env.PRIVATE_KEY, dataToSign);

    const payload = {
        valid,
        plugin: ctx.plugin,
        licenseFingerprint: ctx.licenseFingerprint,
        hash: ctx.hash,
        reason,
        timestamp,
        signature,
        ...extra,
    };

    // Legacy responses must not include modern-only fields, because legacy clients
    // verify the 5-field signature payload. Modern clients use these fields to
    // reconstruct the 8-field payload. Including them on a legacy-signed response
    // makes clients verify the wrong payload and reject a valid license.
    if (!legacyFormat) {
        payload.serverFingerprint = serverFingerprint;
        payload.blocked = blocked;
        payload.code = code;
    }

    return json(payload, valid ? 200 : status);
}

/* index.js */
// index.js
const __index_export_default__ = {
    async fetch(request, env, ctx) {
        try {
            return await route(request, env, ctx);
        } catch (err) {
            console.error("Unhandled error:", err);
            return json({ ok: false, error: "Internal error." }, 500);
        }
    },
};

async function route(request, env) {
    const method = request.method;
    const requestOrigin = request.headers.get("Origin") || "";
    const allowedOrigin = String(env.DASHBOARD_ORIGIN || "").trim();
    const corsAllowed = allowedOrigin !== "" && requestOrigin === allowedOrigin;
    if (method === "OPTIONS") {
        if (!corsAllowed) return new Response(null, { status: 403 });
        return new Response(null, { status: 204, headers: {
            "Access-Control-Allow-Origin": allowedOrigin,
            "Access-Control-Allow-Credentials": "true",
            "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
            "Access-Control-Allow-Headers": "Content-Type, X-CSRF-Token",
            "Access-Control-Max-Age": "86400",
        }});
    }
    const response = await dispatch(request, env);
    if (!corsAllowed) return response;
    const corsHeaders = new Headers(response.headers);
    corsHeaders.set("Access-Control-Allow-Origin", allowedOrigin);
    corsHeaders.set("Access-Control-Allow-Credentials", "true");
    corsHeaders.set("Vary", "Origin");
    return new Response(response.body, { status: response.status, statusText: response.statusText, headers: corsHeaders });
}

async function dispatch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;
    const method = request.method;
    if (path === "/api/validate" && method === "POST") return handleValidate(request, env);
    if (path === "/api/auth/login" && method === "POST") return handleLogin(request, env);
    if (path === "/api/auth/verify" && method === "POST") return handleVerify(request, env);
    if (path === "/api/auth/logout" && method === "POST") return handleLogout(request, env);
    if (path === "/api/auth/session" && method === "GET") return handleSessionCheck(request, env);
    if (path.startsWith("/api/admin/")) {
        const auth = await requireAdminSession(request, env);
        if (auth.error) return auth.error;
        const { session, ip } = auth;
        if (path === "/api/admin/overview" && method === "GET") return handleOverview(env);
        if (path === "/api/admin/products" && method === "GET") return handleListProducts(env);
        if (path === "/api/admin/products" && method === "POST") return handleCreateProduct(request, env, session, ip);
        const productMatch = path.match(/^\/api\/admin\/products\/([^/]+)$/);
        if (productMatch && method === "PUT") return handleUpdateProduct(request, env, session, ip, productMatch[1]);
        if (productMatch && method === "DELETE") return handleDeleteProduct(env, session, ip, productMatch[1]);
        if (path === "/api/admin/licenses" && method === "GET") return handleListLicenses(env);
        if (path === "/api/admin/licenses" && method === "POST") return handleCreateLicense(request, env, session, ip);
        const licenseMatch = path.match(/^\/api\/admin\/licenses\/([^/]+)$/);
        if (licenseMatch && method === "PUT") return handleUpdateLicense(request, env, session, ip, licenseMatch[1]);
        if (licenseMatch && method === "DELETE") return handleDeleteLicense(env, session, ip, licenseMatch[1]);
        if (path === "/api/admin/builds" && method === "GET") return handleListBuilds(env);
        if (path === "/api/admin/builds" && method === "POST") return handleCreateBuild(request, env, session, ip);
        const buildMatch = path.match(/^\/api\/admin\/builds\/([^/]+)$/);
        if (buildMatch && method === "PUT") return handleUpdateBuild(request, env, session, ip, buildMatch[1]);
        if (buildMatch && method === "DELETE") return handleDeleteBuild(env, session, ip, buildMatch[1]);
        if (path === "/api/admin/bans" && method === "GET") return handleListBans(env);
        if (path === "/api/admin/bans" && method === "POST") return handleCreateBan(request, env, session, ip);
        const banMatch = path.match(/^\/api\/admin\/bans\/([^/]+)$/);
        if (banMatch && method === "PUT") return handleUpdateBan(request, env, session, ip, banMatch[1]);
        if (banMatch && method === "DELETE") return handleDeleteBan(env, session, ip, banMatch[1]);
        if (path === "/api/admin/active-servers" && method === "GET") return handleActiveServers(env);
        if (path === "/api/admin/audit-log" && method === "GET") return handleAuditLog(env);
        if (path === "/api/admin/settings" && method === "GET") return handleGetSettings(env);
        if (path === "/api/admin/settings" && method === "PUT") return handleUpdateSettings(request, env, session, ip);
        return json({ ok: false, error: "Not found." }, 404);
    }
    if (path === "/health" && method === "GET") return json({ ok: true });
    return json({ ok: false, error: "Not found." }, 404);
}

export default __index_export_default__;
