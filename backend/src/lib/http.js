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

export { json, noContent, authError, SECURITY_HEADERS };
