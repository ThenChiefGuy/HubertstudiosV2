// routes/products.js
//
// NOTE on images: there is no R2 bucket in this deployment. Plugin images
// are stored as a base64 data URL (e.g. "data:image/png;base64,....")
// directly in the products.image_data column and returned inline in the
// JSON response as `image`. The dashboard can put that string straight into
// an <img src="..."> tag — no separate image-serving route needed.
//
// Trade-off: D1 rows have practical size limits and this bloats the table a
// bit, but plugin icons are small (a few KB to maybe 100-200KB), so for the
// expected number of plugins this is simple and avoids needing any object
// storage account at all.

import { json } from "../lib/http.js";
import { newId, getProductById, getProductByName, listProducts, insertAuditLog, countOnlineServersByProduct } from "../lib/db.js";
import { syncProductLists, syncProductFlags, removeProductFlags } from "../lib/sync.js";

const MAX_IMAGE_BYTES = 1.5 * 1024 * 1024; // ~1.5MB raw image, before base64 overhead (~2MB encoded) — keeps D1 rows reasonable

function productToApiShape(row, licenseCount = 0, serverCount = 0) {
    return {
        id: row.id,
        name: row.name,
        displayName: row.display_name,
        description: row.description,
        image: row.image_data || null,
        active: !!row.active,
        mode: row.mode,
        requireHash: !!row.require_hash,
        licenses: licenseCount,
        servers: serverCount,
    };
}

async function handleListProducts(env) {
    const rows = await listProducts(env.DB);
    const [counts, serverCountMap] = await Promise.all([
        env.DB.prepare(
            `SELECT product_id, COUNT(*) AS license_count FROM licenses GROUP BY product_id`
        ).all(),
        countOnlineServersByProduct(env.DB),
    ]);
    const countMap = new Map((counts.results || []).map((r) => [r.product_id, r.license_count]));
    return json({ ok: true, products: rows.map((r) => productToApiShape(r, countMap.get(r.id) || 0, serverCountMap[r.id] || 0)) });
}

/** Validates an incoming `image` value: must be a data: URL, and under the size cap. Returns the value to store (or null/undefined passthrough). */
function validateImageData(image, existing) {
    if (image === undefined) return { ok: true, value: existing };
    if (image === null || image === "") return { ok: true, value: null };
    if (typeof image !== "string" || !image.startsWith("data:image/")) {
        return { ok: false, error: "Image must be a data URL (data:image/...)." };
    }
    // Rough byte-size estimate from base64 length.
    const base64Part = image.split(",")[1] || "";
    const approxBytes = Math.floor((base64Part.length * 3) / 4);
    if (approxBytes > MAX_IMAGE_BYTES) {
        return { ok: false, error: "Image is too large. Please use a smaller image (under ~1.5MB)." };
    }
    return { ok: true, value: image };
}

async function handleCreateProduct(request, env, session, ip) {
    const body = await safeJsonProducts(request);
    if (!body) return json({ ok: false, error: "Invalid request body." }, 400);

    const name = String(body.name || "").trim();
    const displayName = String(body.displayName || "").trim();
    if (!name || !displayName) {
        return json({ ok: false, error: "Name and display name are required." }, 400);
    }
    if (await getProductByName(env.DB, name)) {
        return json({ ok: false, error: "A product with that name already exists." }, 409);
    }

    const imageResult = validateImageData(body.image, null);
    if (!imageResult.ok) return json({ ok: false, error: imageResult.error }, 400);

    const id = newId("prod");
    const now = Date.now();
    const mode = body.mode === "legacy" ? "legacy" : "modern";
    const active = body.active !== false;
    const requireHash = body.requireHash !== false;

    await env.DB.prepare(
        `INSERT INTO products (id, name, display_name, description, image_data, mode, active, require_hash, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
    )
        .bind(id, name, displayName, String(body.description || ""), imageResult.value, mode, active ? 1 : 0, requireHash ? 1 : 0, now, now)
        .run();

    const row = await getProductById(env.DB, id);
    await syncProductLists(env.DB, env.KV);
    await syncProductFlags(env.KV, row);

    await insertAuditLog(env.DB, {
        adminEmail: session.adminEmail,
        action: "plugin.create",
        targetType: "Plugin",
        targetId: id,
        ip,
        details: `Created plugin ${displayName} (${name}).`,
    });

    return json({ ok: true, product: productToApiShape(row, 0, 0) }, 201);
}

async function handleUpdateProduct(request, env, session, ip, id) {
    const existing = await getProductById(env.DB, id);
    if (!existing) return json({ ok: false, error: "Plugin not found." }, 404);

    const body = await safeJsonProducts(request);
    if (!body) return json({ ok: false, error: "Invalid request body." }, 400);

    const imageResult = validateImageData(body.image, existing.image_data);
    if (!imageResult.ok) return json({ ok: false, error: imageResult.error }, 400);

    const displayName = body.displayName !== undefined ? String(body.displayName).trim() : existing.display_name;
    const description = body.description !== undefined ? String(body.description) : existing.description;
    const mode = body.mode === "legacy" || body.mode === "modern" ? body.mode : existing.mode;
    const active = body.active !== undefined ? !!body.active : !!existing.active;
    const requireHash = body.requireHash !== undefined ? !!body.requireHash : !!existing.require_hash;

    await env.DB.prepare(
        `UPDATE products SET display_name = ?, description = ?, image_data = ?, mode = ?, active = ?, require_hash = ?, updated_at = ? WHERE id = ?`
    )
        .bind(displayName, description, imageResult.value, mode, active ? 1 : 0, requireHash ? 1 : 0, Date.now(), id)
        .run();

    const row = await getProductById(env.DB, id);
    await syncProductLists(env.DB, env.KV);
    await syncProductFlags(env.KV, row);

    await insertAuditLog(env.DB, {
        adminEmail: session.adminEmail,
        action: "plugin.update",
        targetType: "Plugin",
        targetId: id,
        ip,
        details: `Updated plugin ${row.display_name}.`,
    });

    return json({ ok: true, product: productToApiShape(row, 0, 0) });
}

async function handleDeleteProduct(env, session, ip, id) {
    const existing = await getProductById(env.DB, id);
    if (!existing) return json({ ok: false, error: "Plugin not found." }, 404);

    await env.DB.prepare("DELETE FROM products WHERE id = ?").bind(id).run();
    await removeProductFlags(env.KV, existing);
    await syncProductLists(env.DB, env.KV);

    await insertAuditLog(env.DB, {
        adminEmail: session.adminEmail,
        action: "plugin.delete",
        targetType: "Plugin",
        targetId: id,
        ip,
        details: `Deleted plugin ${existing.display_name}. Cascade removed its licenses, builds, and bans.`,
    });

    return json({ ok: true });
}

async function safeJsonProducts(request) {
    try {
        return await request.json();
    } catch {
        return null;
    }
}

export {
    handleListProducts,
    handleCreateProduct,
    handleUpdateProduct,
    handleDeleteProduct,
};
