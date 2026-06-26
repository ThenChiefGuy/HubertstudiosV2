// routes/bans.js

import { json } from "../lib/http.js";
import { newId, getProductById, listServerBans, insertAuditLog } from "../lib/db.js";
import { syncBan, removeBan } from "../lib/sync.js";

const BAN_DURATION_MS = {
    "1 day": 1 * 24 * 60 * 60 * 1000,
    "7 days": 7 * 24 * 60 * 60 * 1000,
    "30 days": 30 * 24 * 60 * 60 * 1000,
    "90 days": 90 * 24 * 60 * 60 * 1000,
    "6 months": 182 * 24 * 60 * 60 * 1000,
    "1 year": 365 * 24 * 60 * 60 * 1000,
};

function banToApiShape(row) {
    return {
        id: row.id,
        plugin: row.product_display_name || null,
        productId: row.product_id || null,
        identifier: row.identifier,
        reason: row.reason,
        until: row.expires_at ? new Date(row.expires_at).toISOString().slice(0, 10) : null,
        status: row.status,
    };
}

async function handleListBans(env) {
    const rows = await listServerBans(env.DB);
    return json({ ok: true, bans: rows.map(banToApiShape) });
}

async function handleCreateBan(request, env, session, ip) {
    const body = await safeJsonBans(request);
    if (!body) return json({ ok: false, error: "Invalid request body." }, 400);

    const identifier = String(body.identifier || "").trim();
    if (!identifier) return json({ ok: false, error: "IP or domain is required." }, 400);
    const reason = String(body.reason || "").trim();

    let productId = null;
    let product = null;
    if (body.productId) {
        product = await getProductById(env.DB, body.productId);
        if (!product) return json({ ok: false, error: "Plugin not found." }, 400);
        productId = product.id;
    }

    let expiresAt = null;
    if (body.permanent === false) {
        if (body.until) {
            const parsed = Date.parse(body.until);
            expiresAt = Number.isNaN(parsed) ? null : parsed;
        } else if (body.duration && BAN_DURATION_MS[body.duration]) {
            expiresAt = Date.now() + BAN_DURATION_MS[body.duration];
        }
    }

    const id = newId("ban");
    const now = Date.now();
    await env.DB.prepare(
        `INSERT INTO server_bans (id, product_id, identifier, reason, expires_at, status, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, 'active', ?, ?)`
    )
        .bind(id, productId, identifier.toLowerCase(), reason, expiresAt, now, now)
        .run();

    const row = { id, product_id: productId, identifier: identifier.toLowerCase(), reason, expires_at: expiresAt, status: "active" };
    await syncBan(env.KV, row, product);

    await insertAuditLog(env.DB, {
        adminEmail: session.adminEmail,
        action: "server_ban.create",
        targetType: "ServerBan",
        targetId: id,
        ip,
        details: `${productId ? `Plugin ban for ${product.display_name}` : "Global ban"} added for ${identifier}.`,
    });

    return json(
        { ok: true, ban: banToApiShape({ ...row, product_display_name: product?.display_name }) },
        201
    );
}

async function handleUpdateBan(request, env, session, ip, id) {
    const existing = await env.DB.prepare("SELECT * FROM server_bans WHERE id = ?").bind(id).first();
    if (!existing) return json({ ok: false, error: "Ban not found." }, 404);
    const product = existing.product_id ? await getProductById(env.DB, existing.product_id) : null;

    const body = await safeJsonBans(request);
    if (!body) return json({ ok: false, error: "Invalid request body." }, 400);

    const reason = body.reason !== undefined ? String(body.reason) : existing.reason;
    const status = body.active !== undefined ? (body.active ? "active" : "expired") : existing.status;

    let expiresAt = existing.expires_at;
    if (body.permanent === true) expiresAt = null;
    else if (body.until) {
        const parsed = Date.parse(body.until);
        expiresAt = Number.isNaN(parsed) ? expiresAt : parsed;
    } else if (body.duration && BAN_DURATION_MS[body.duration]) {
        expiresAt = Date.now() + BAN_DURATION_MS[body.duration];
    }

    await env.DB.prepare(`UPDATE server_bans SET reason = ?, expires_at = ?, status = ?, updated_at = ? WHERE id = ?`)
        .bind(reason, expiresAt, status, Date.now(), id)
        .run();

    const row = await env.DB.prepare("SELECT * FROM server_bans WHERE id = ?").bind(id).first();
    if (status === "active") {
        await syncBan(env.KV, row, product);
    } else {
        await removeBan(env.KV, row, product);
    }

    await insertAuditLog(env.DB, {
        adminEmail: session.adminEmail,
        action: "server_ban.update",
        targetType: "ServerBan",
        targetId: id,
        ip,
        details: `Updated ban for ${existing.identifier}.`,
    });

    return json({ ok: true, ban: banToApiShape({ ...row, product_display_name: product?.display_name }) });
}

async function handleDeleteBan(env, session, ip, id) {
    const existing = await env.DB.prepare("SELECT * FROM server_bans WHERE id = ?").bind(id).first();
    if (!existing) return json({ ok: false, error: "Ban not found." }, 404);
    const product = existing.product_id ? await getProductById(env.DB, existing.product_id) : null;

    await env.DB.prepare("DELETE FROM server_bans WHERE id = ?").bind(id).run();
    await removeBan(env.KV, existing, product);

    await insertAuditLog(env.DB, {
        adminEmail: session.adminEmail,
        action: "server_ban.delete",
        targetType: "ServerBan",
        targetId: id,
        ip,
        details: `Removed ban for ${existing.identifier}.`,
    });

    return json({ ok: true });
}

async function safeJsonBans(request) {
    try {
        return await request.json();
    } catch {
        return null;
    }
}

export { handleListBans, handleCreateBan, handleUpdateBan, handleDeleteBan };
