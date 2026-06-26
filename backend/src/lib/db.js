// lib/db.js
//
// Thin helpers over the D1 binding. Every write here is the source of truth;
// lib/sync.js is responsible for mirroring the relevant subset into KV
// afterwards. Nothing in this file touches KV.

function newId(prefix) {
    // Not cryptographically sensitive (license/build/ban IDs are not secrets,
    // the license *key* itself is the secret) — just needs to be unique.
    const bytes = crypto.getRandomValues(new Uint8Array(9));
    const b64 = btoa(String.fromCharCode(...bytes)).replace(/[+/=]/g, "").slice(0, 12);
    return `${prefix}_${b64}`;
}

async function getProductByName(db, name) {
    return db.prepare("SELECT * FROM products WHERE name = ?").bind(name).first();
}

async function getProductById(db, id) {
    return db.prepare("SELECT * FROM products WHERE id = ?").bind(id).first();
}

async function listProducts(db) {
    const { results } = await db.prepare("SELECT * FROM products ORDER BY created_at DESC").all();
    return results || [];
}

async function listAllowedProductNames(db) {
    const { results } = await db.prepare("SELECT name FROM products WHERE active = 1").all();
    return (results || []).map((r) => r.name);
}

async function listLegacyProductNames(db) {
    const { results } = await db.prepare("SELECT name FROM products WHERE mode = 'legacy'").all();
    return (results || []).map((r) => r.name);
}

async function getLicenseByKey(db, licenseKey) {
    return db.prepare("SELECT * FROM licenses WHERE license_key = ?").bind(licenseKey).first();
}

async function getLicenseById(db, id) {
    return db.prepare("SELECT * FROM licenses WHERE id = ?").bind(id).first();
}

async function listLicensesForProduct(db, productId) {
    const { results } = await db
        .prepare("SELECT * FROM licenses WHERE product_id = ? ORDER BY created_at DESC")
        .bind(productId)
        .all();
    return results || [];
}

async function listLicenses(db) {
    const { results } = await db
        .prepare(
            `SELECT licenses.*, products.name AS product_name, products.display_name AS product_display_name
             FROM licenses JOIN products ON products.id = licenses.product_id
             ORDER BY licenses.created_at DESC`
        )
        .all();
    return results || [];
}

async function getBuildByHash(db, productId, jarHash) {
    return db
        .prepare("SELECT * FROM builds WHERE product_id = ? AND jar_hash = ?")
        .bind(productId, jarHash)
        .first();
}

async function listBuilds(db) {
    const { results } = await db
        .prepare(
            `SELECT builds.*, products.name AS product_name, products.display_name AS product_display_name
             FROM builds JOIN products ON products.id = builds.product_id
             ORDER BY builds.created_at DESC`
        )
        .all();
    return results || [];
}

async function listServerBans(db) {
    const { results } = await db
        .prepare(
            `SELECT server_bans.*, products.name AS product_name, products.display_name AS product_display_name
             FROM server_bans LEFT JOIN products ON products.id = server_bans.product_id
             ORDER BY server_bans.created_at DESC`
        )
        .all();
    return results || [];
}

async function findGlobalBan(db, identifier) {
    return db
        .prepare(
            "SELECT * FROM server_bans WHERE product_id IS NULL AND identifier = ? AND status = 'active'"
        )
        .bind(identifier)
        .first();
}

async function findProductBan(db, productId, identifier) {
    return db
        .prepare(
            "SELECT * FROM server_bans WHERE product_id = ? AND identifier = ? AND status = 'active'"
        )
        .bind(productId, identifier)
        .first();
}

async function insertAuditLog(db, { adminEmail, action, targetType, targetId, ip, details }) {
    await db
        .prepare(
            `INSERT INTO admin_audit_log (id, created_at, admin_email, action, target_type, target_id, ip, details)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
        )
        .bind(newId("evt"), Date.now(), adminEmail || "", action, targetType, targetId || "", ip || "", details || "")
        .run();
}

async function listAuditLog(db, limit = 200) {
    const { results } = await db
        .prepare("SELECT * FROM admin_audit_log ORDER BY created_at DESC LIMIT ?")
        .bind(limit)
        .all();
    return results || [];
}

async function upsertServerSighting(db, { productId, address, version, country, players = 0 }) {
    const id = await sha256HexLocal(`${productId}:${address}`);
    const now = Date.now();
    await db
        .prepare(
            `INSERT INTO server_sightings (id, product_id, address, version, last_seen_at, first_seen_at, country, players)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)
             ON CONFLICT(id) DO UPDATE SET
                version = excluded.version,
                last_seen_at = excluded.last_seen_at,
                country = excluded.country,
                players = excluded.players`
        )
        .bind(id, productId, address || "", version || "", now, now, country || "", players)
        .run();
}

async function countOnlineServersByProduct(db, windowMinutes = 10) {
    const threshold = Date.now() - windowMinutes * 60 * 1000;
    const { results } = await db
        .prepare(
            `SELECT product_id, COUNT(*) as count
             FROM server_sightings
             WHERE last_seen_at >= ?
             GROUP BY product_id`
        )
        .bind(threshold)
        .all();
    const map = {};
    for (const row of results || []) {
        map[row.product_id] = Number(row.count);
    }
    return map;
}

async function listServerSightings(db) {
    const { results } = await db
        .prepare(
            `SELECT server_sightings.*, products.name AS product_name, products.display_name AS product_display_name
             FROM server_sightings JOIN products ON products.id = server_sightings.product_id
             ORDER BY last_seen_at DESC LIMIT 500`
        )
        .all();
    return results || [];
}

async function sha256HexLocal(value) {
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
    return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export {
    newId,
    getProductByName,
    getProductById,
    listProducts,
    listAllowedProductNames,
    listLegacyProductNames,
    getLicenseByKey,
    getLicenseById,
    listLicensesForProduct,
    listLicenses,
    getBuildByHash,
    listBuilds,
    listServerBans,
    findGlobalBan,
    findProductBan,
    insertAuditLog,
    listAuditLog,
    upsertServerSighting,
    listServerSightings,
    countOnlineServersByProduct,
};
