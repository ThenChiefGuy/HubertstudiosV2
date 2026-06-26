// routes/settings.js

import { json } from "../lib/http.js";
import { GLOBAL_REQUIRE_HASH_KEY } from "../lib/kvKeys.js";
import { syncGlobalRequireHash } from "../lib/sync.js";
import { insertAuditLog } from "../lib/db.js";

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

export { handleGetSettings, handleUpdateSettings };
