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
} from "./kvKeys.js";
import { listAllowedProductNames, listLegacyProductNames } from "./db.js";

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

export {
    syncProductLists,
    syncProductFlags,
    removeProductFlags,
    syncLicense,
    removeLicense,
    syncBuild,
    removeBuild,
    syncBan,
    removeBan,
    syncGlobalRequireHash,
    licenseToKvRecord,
    buildToKvRecord,
    banToKvRecord,
};
