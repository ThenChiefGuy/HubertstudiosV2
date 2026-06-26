// lib/middleware.js

import { json } from "./http.js";
import { resolveSession, readCookie, SESSION_COOKIE_NAME, getClientIp } from "./auth.js";

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

export { requireAdminSession };
