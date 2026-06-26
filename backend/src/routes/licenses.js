// routes/licenses.js

import { json } from "../lib/http.js";
import { newId, getLicenseById, getProductById, listLicenses, insertAuditLog } from "../lib/db.js";
import { syncLicense, removeLicense } from "../lib/sync.js";
import { randomToken } from "../lib/crypto.js";

const LICENSE_DURATION_MS = {
    "30 days": 30 * 24 * 60 * 60 * 1000,
    "3 months": 90 * 24 * 60 * 60 * 1000,
    "6 months": 182 * 24 * 60 * 60 * 1000,
    "1 year": 365 * 24 * 60 * 60 * 1000,
    Lifetime: null,
};

function licenseToApiShape(row) {
    return {
        id: row.id,
        plugin: row.product_display_name || row.product_name,
        productId: row.product_id,
        key: row.license_key,
        label: row.label,
        status: row.status,
        duration: row.duration_label,
        expiry: row.expires_at ? new Date(row.expires_at).toISOString().slice(0, 10) : null,
        created: new Date(row.created_at).toISOString().slice(0, 10),
        servers: 0,
        notes: row.notes,
    };
}

async function handleListLicenses(env) {
    const rows = await listLicenses(env.DB);
    return json({ ok: true, licenses: rows.map(licenseToApiShape) });
}

function generateLicenseKey(prefix) {
    // e.g. AUTH-9F2K-7H3M-QX84-LP21 style — readable, grouped, uppercase.
    const raw = randomToken(10).toUpperCase().replace(/[^A-Z0-9]/g, "");
    const groups = raw.match(/.{1,4}/g) || [raw];
    const cleanPrefix = (prefix || "LIC").toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, 4) || "LIC";
    return `${cleanPrefix}-${groups.slice(0, 4).join("-")}`;
}

async function handleCreateLicense(request, env, session, ip) {
    const body = await safeJsonLicenses(request);
    if (!body) return json({ ok: false, error: "Invalid request body." }, 400);

    const productId = String(body.productId || "");
    const product = await getProductById(env.DB, productId);
    if (!product) return json({ ok: false, error: "Plugin not found." }, 400);

    const label = String(body.label || "").trim() || "Global Key";
    const durationLabel = Object.keys(LICENSE_DURATION_MS).includes(body.duration) ? body.duration : "Lifetime";
    const active = body.active !== false;
    const notes = String(body.notes || "");

    const key = String(body.key || "").trim() || generateLicenseKey(product.name);
    const now = Date.now();
    const expiresAt = LICENSE_DURATION_MS[durationLabel] ? now + LICENSE_DURATION_MS[durationLabel] : null;

    const id = newId("lic");
    try {
        await env.DB.prepare(
            `INSERT INTO licenses (id, product_id, license_key, label, status, duration_label, expires_at, notes, created_at, updated_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
        )
            .bind(id, productId, key, label, active ? "active" : "revoked", durationLabel, expiresAt, notes, now, now)
            .run();
    } catch (e) {
        if (String(e.message || "").includes("UNIQUE")) {
            return json({ ok: false, error: "That license key already exists." }, 409);
        }
        throw e;
    }

    const row = await getLicenseById(env.DB, id);
    await syncLicense(env.KV, product, row);

    await insertAuditLog(env.DB, {
        adminEmail: session.adminEmail,
        action: "license.create",
        targetType: "License",
        targetId: id,
        ip,
        details: `Created ${durationLabel} key "${label}" for ${product.display_name}.`,
    });

    return json({ ok: true, license: { ...licenseToApiShape({ ...row, product_name: product.name, product_display_name: product.display_name }) } }, 201);
}

async function handleUpdateLicense(request, env, session, ip, id) {
    const existing = await getLicenseById(env.DB, id);
    if (!existing) return json({ ok: false, error: "License not found." }, 404);
    const product = await getProductById(env.DB, existing.product_id);

    const body = await safeJsonLicenses(request);
    if (!body) return json({ ok: false, error: "Invalid request body." }, 400);

    const label = body.label !== undefined ? String(body.label).trim() : existing.label;
    const notes = body.notes !== undefined ? String(body.notes) : existing.notes;
    let status = existing.status;
    if (body.status && ["active", "expired", "revoked"].includes(body.status)) status = body.status;
    else if (body.active !== undefined) status = body.active ? "active" : "revoked";

    let durationLabel = existing.duration_label;
    let expiresAt = existing.expires_at;
    if (body.duration && Object.keys(LICENSE_DURATION_MS).includes(body.duration) && body.duration !== existing.duration_label) {
        durationLabel = body.duration;
        expiresAt = LICENSE_DURATION_MS[durationLabel] ? Date.now() + LICENSE_DURATION_MS[durationLabel] : null;
    }

    await env.DB.prepare(
        `UPDATE licenses SET label = ?, status = ?, duration_label = ?, expires_at = ?, notes = ?, updated_at = ? WHERE id = ?`
    )
        .bind(label, status, durationLabel, expiresAt, notes, Date.now(), id)
        .run();

    const row = await getLicenseById(env.DB, id);
    await syncLicense(env.KV, product, row);

    await insertAuditLog(env.DB, {
        adminEmail: session.adminEmail,
        action: status === "revoked" && existing.status !== "revoked" ? "license.revoke" : "license.update",
        targetType: "License",
        targetId: id,
        ip,
        details: `Updated license "${label}" for ${product.display_name}.`,
    });

    return json({ ok: true, license: licenseToApiShape({ ...row, product_name: product.name, product_display_name: product.display_name }) });
}

async function handleDeleteLicense(env, session, ip, id) {
    const existing = await getLicenseById(env.DB, id);
    if (!existing) return json({ ok: false, error: "License not found." }, 404);
    const product = await getProductById(env.DB, existing.product_id);

    await env.DB.prepare("DELETE FROM licenses WHERE id = ?").bind(id).run();
    if (product) await removeLicense(env.KV, product, existing);

    await insertAuditLog(env.DB, {
        adminEmail: session.adminEmail,
        action: "license.delete",
        targetType: "License",
        targetId: id,
        ip,
        details: `Deleted license "${existing.label}".`,
    });

    return json({ ok: true });
}

async function safeJsonLicenses(request) {
    try {
        return await request.json();
    } catch {
        return null;
    }
}

export { handleListLicenses, handleCreateLicense, handleUpdateLicense, handleDeleteLicense };
