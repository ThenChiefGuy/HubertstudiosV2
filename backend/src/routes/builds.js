// routes/builds.js

import { json } from "../lib/http.js";
import { newId, getProductById, listBuilds, insertAuditLog } from "../lib/db.js";
import { syncBuild, removeBuild } from "../lib/sync.js";
import { isSha256Hex } from "../lib/crypto.js";

function buildToApiShape(row) {
    return {
        id: row.id,
        plugin: row.product_display_name || row.product_name,
        productId: row.product_id,
        hash: row.jar_hash,
        version: row.version,
        active: !!row.active,
        created: new Date(row.created_at).toISOString().slice(0, 10),
        reason: row.reason || undefined,
    };
}

async function handleListBuilds(env) {
    const rows = await listBuilds(env.DB);
    return json({ ok: true, builds: rows.map(buildToApiShape) });
}

async function handleCreateBuild(request, env, session, ip) {
    const body = await safeJsonBuilds(request);
    if (!body) return json({ ok: false, error: "Invalid request body." }, 400);

    const productId = String(body.productId || "");
    const product = await getProductById(env.DB, productId);
    if (!product) return json({ ok: false, error: "Plugin not found." }, 400);

    const hash = String(body.hash || "").trim().toLowerCase();
    if (!isSha256Hex(hash)) {
        return json({ ok: false, error: "Hash must be a 64-character lowercase SHA-256 hex string." }, 400);
    }
    const version = String(body.version || "").trim();
    const active = body.active !== false;
    const reason = String(body.reason || "");

    const id = newId("bld");
    const now = Date.now();
    try {
        await env.DB.prepare(
            `INSERT INTO builds (id, product_id, jar_hash, version, active, reason, created_at, updated_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
        )
            .bind(id, productId, hash, version, active ? 1 : 0, reason, now, now)
            .run();
    } catch (e) {
        if (String(e.message || "").includes("UNIQUE")) {
            return json({ ok: false, error: "That hash is already registered for this plugin." }, 409);
        }
        throw e;
    }

    const row = { id, product_id: productId, jar_hash: hash, version, active: active ? 1 : 0, reason, created_at: now };
    await syncBuild(env.KV, product, row);

    await insertAuditLog(env.DB, {
        adminEmail: session.adminEmail,
        action: "build.create",
        targetType: "Build",
        targetId: id,
        ip,
        details: `Registered official build ${product.display_name} ${version}.`,
    });

    return json({ ok: true, build: buildToApiShape({ ...row, product_name: product.name, product_display_name: product.display_name }) }, 201);
}

async function handleUpdateBuild(request, env, session, ip, id) {
    const existing = await env.DB.prepare("SELECT * FROM builds WHERE id = ?").bind(id).first();
    if (!existing) return json({ ok: false, error: "Build not found." }, 404);
    const product = await getProductById(env.DB, existing.product_id);

    const body = await safeJsonBuilds(request);
    if (!body) return json({ ok: false, error: "Invalid request body." }, 400);

    const version = body.version !== undefined ? String(body.version).trim() : existing.version;
    const active = body.active !== undefined ? !!body.active : !!existing.active;
    const reason = body.reason !== undefined ? String(body.reason) : existing.reason;

    await env.DB.prepare(`UPDATE builds SET version = ?, active = ?, reason = ?, updated_at = ? WHERE id = ?`)
        .bind(version, active ? 1 : 0, reason, Date.now(), id)
        .run();

    const row = await env.DB.prepare("SELECT * FROM builds WHERE id = ?").bind(id).first();
    if (product) await syncBuild(env.KV, product, row);

    await insertAuditLog(env.DB, {
        adminEmail: session.adminEmail,
        action: "build.update",
        targetType: "Build",
        targetId: id,
        ip,
        details: `Updated build ${product ? product.display_name : ""} ${version}.${!active ? " Marked inactive." : ""}`,
    });

    return json({ ok: true, build: buildToApiShape({ ...row, product_name: product?.name, product_display_name: product?.display_name }) });
}

async function handleDeleteBuild(env, session, ip, id) {
    const existing = await env.DB.prepare("SELECT * FROM builds WHERE id = ?").bind(id).first();
    if (!existing) return json({ ok: false, error: "Build not found." }, 404);
    const product = await getProductById(env.DB, existing.product_id);

    await env.DB.prepare("DELETE FROM builds WHERE id = ?").bind(id).run();
    if (product) await removeBuild(env.KV, product, existing);

    await insertAuditLog(env.DB, {
        adminEmail: session.adminEmail,
        action: "build.delete",
        targetType: "Build",
        targetId: id,
        ip,
        details: `Deleted build ${existing.jar_hash.slice(0, 12)}... (${existing.version}).`,
    });

    return json({ ok: true });
}

async function safeJsonBuilds(request) {
    try {
        return await request.json();
    } catch {
        return null;
    }
}

export { handleListBuilds, handleCreateBuild, handleUpdateBuild, handleDeleteBuild };
