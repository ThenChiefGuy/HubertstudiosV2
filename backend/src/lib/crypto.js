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

export {
    sha256Hex,
    isSha256Hex,
    pbkdf2Hex,
    verifyPassword,
    hmacHex,
    hmacVerifyHex,
    randomDigits,
    randomToken,
    signEcdsa,
    timingSafeEqualHex,
    toHex,
    fromHex,
};
