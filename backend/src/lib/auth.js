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

import { hmacHex, hmacVerifyHex, randomToken } from "./crypto.js";
import { loginAttemptKey, codeAttemptKey, ipBlockKey, sessionKey } from "./kvKeys.js";

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

export {
    SESSION_COOKIE_NAME,
    SESSION_MAX_AGE_SECONDS,
    MAX_FAILED_ATTEMPTS,
    getClientIp,
    isIpBlocked,
    blockIp,
    recordFailedLogin,
    clearFailedLogin,
    recordFailedCode,
    clearFailedCode,
    createVerificationChallenge,
    getVerificationChallenge,
    deleteVerificationChallenge,
    createSession,
    resolveSession,
    destroySession,
    buildSessionCookie,
    buildClearSessionCookie,
    readCookie,
};
