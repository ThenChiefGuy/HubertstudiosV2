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

export {
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
    loginAttemptKey,
    codeAttemptKey,
    ipBlockKey,
    verifyChallengeKey,
    sessionKey,
};
