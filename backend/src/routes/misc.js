// routes/misc.js

import { json } from "../lib/http.js";
import { listAuditLog, listServerSightings, listProducts, listLicenses } from "../lib/db.js";

async function handleOverview(env) {
    const products = await listProducts(env.DB);
    const licenses = await listLicenses(env.DB);
    const sightings = await listServerSightings(env.DB);

    const activeProducts = products.filter((p) => p.active).length;
    const activeLicenses = licenses.filter((l) => l.status === "active").length;
    const onlineServers = sightings.filter((s) => Date.now() - s.last_seen_at < 10 * 60 * 1000).length;

    return json({
        ok: true,
        stats: {
            totalPlugins: products.length,
            activePlugins: activeProducts,
            totalLicenses: licenses.length,
            activeLicenses,
            onlineServers,
            totalServersSeen: sightings.length,
        },
    });
}

function sightingToApiShape(row) {
    const ageMs = Date.now() - row.last_seen_at;
    return {
        id: row.id,
        address: row.address,
        plugin: row.product_display_name || row.product_name,
        version: row.version,
        players: 0, // not collected by /api/validate; reserved for a future heartbeat field
        status: ageMs < 10 * 60 * 1000 ? "online" : "offline",
        lastSeen: humanizeAge(ageMs),
        country: row.country || "Unknown",
    };
}

function humanizeAge(ms) {
    if (ms < 60_000) return "Just now";
    if (ms < 3_600_000) return `${Math.floor(ms / 60_000)} min ago`;
    if (ms < 86_400_000) return `${Math.floor(ms / 3_600_000)} hours ago`;
    return `${Math.floor(ms / 86_400_000)} days ago`;
}

async function handleActiveServers(env) {
    const rows = await listServerSightings(env.DB);
    return json({ ok: true, servers: rows.map(sightingToApiShape) });
}

function auditToApiShape(row) {
    return {
        id: row.id,
        time: new Date(row.created_at).toISOString().replace("T", " ").slice(0, 19),
        admin: row.admin_email,
        action: row.action,
        targetType: row.target_type,
        targetId: row.target_id,
        ip: row.ip,
        details: row.details,
    };
}

async function handleAuditLog(env) {
    const rows = await listAuditLog(env.DB, 200);
    return json({ ok: true, events: rows.map(auditToApiShape) });
}

export { handleOverview, handleActiveServers, handleAuditLog };
