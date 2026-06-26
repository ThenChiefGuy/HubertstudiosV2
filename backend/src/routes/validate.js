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

import { json } from "../lib/http.js";
import { sha256Hex, isSha256Hex, signEcdsa } from "../lib/crypto.js";
import {
    ALLOWED_PRODUCTS_KEY,
    LEGACY_PRODUCTS_KEY,
    GLOBAL_REQUIRE_HASH_KEY,
    productDisabledKey,
    productRequireHashKey,
    legacyLicenseKey,
    modernLicenseKey,
    buildKey,
    globalBanKey,
    pluginBanKey,
} from "../lib/kvKeys.js";
import { getProductByName, getLicenseByKey, getBuildByHash, findGlobalBan, findProductBan, upsertServerSighting } from "../lib/db.js";
import { licenseToKvRecord, buildToKvRecord } from "../lib/sync.js";

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

export { handleValidate };
