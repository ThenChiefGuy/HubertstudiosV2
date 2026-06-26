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

export { verifyTurnstile };
