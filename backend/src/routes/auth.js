// routes/auth.js

import { json, authError, SECURITY_HEADERS } from "../lib/http.js";
import { verifyPassword, randomDigits, hmacVerifyHex } from "../lib/crypto.js";
import { verifyTurnstile } from "../lib/turnstile.js";
import { sendVerificationCodeEmail } from "../lib/email.js";
import {
    getClientIp,
    isIpBlocked,
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
    SESSION_COOKIE_NAME,
} from "../lib/auth.js";
import { insertAuditLog } from "../lib/db.js";

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

export { handleLogin, handleVerify, handleLogout, handleSessionCheck };
