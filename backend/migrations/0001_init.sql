-- 0001_init.sql
-- HubertStudios License Manager — initial schema.
--
-- Design notes:
-- * Licenses are GLOBAL per plugin (one shared key per plugin), not per customer.
--   There is intentionally no "customers" table — this matches the dashboard
--   frontend exactly, which has no customer concept anywhere.
-- * D1 (SQLite) is the source of truth. KV is a read-through cache populated
--   from D1 on every admin mutation (see lib/sync.js in the Worker). Never
--   write to KV without writing to D1 first.
-- * All timestamps are stored as INTEGER unix milliseconds (consistent with
--   Date.now() in the Worker) so comparisons never need string parsing.
-- * NO R2 — plugin images are stored as a base64 data URL directly in the
--   products table (image_data). This avoids needing an R2 bucket/binding,
--   since R2 isn't available on the account this is deployed to. Images are
--   small (plugin icons), so this is fine for the expected volume.

PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS products (
    id              TEXT PRIMARY KEY,           -- e.g. "prod_xxxxx"
    name            TEXT NOT NULL UNIQUE,       -- machine name used in KV keys, e.g. "CoreGuard"
    display_name    TEXT NOT NULL,              -- human label shown in dashboard
    description     TEXT NOT NULL DEFAULT '',
    image_data      TEXT,                       -- base64 data URL, e.g. "data:image/png;base64,...", nullable
    mode            TEXT NOT NULL DEFAULT 'modern' CHECK (mode IN ('legacy','modern')),
    active          INTEGER NOT NULL DEFAULT 1, -- 0/1 — product-wide disable (":DISABLED")
    require_hash    INTEGER NOT NULL DEFAULT 1, -- 0/1 — plugin-specific hash toggle
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_products_active ON products(active);

CREATE TABLE IF NOT EXISTS licenses (
    id                TEXT PRIMARY KEY,             -- e.g. "lic_xxxxx"
    product_id        TEXT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    license_key       TEXT NOT NULL UNIQUE,         -- the actual global key string
    label             TEXT NOT NULL DEFAULT '',
    status            TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','expired','revoked')),
    duration_label    TEXT NOT NULL DEFAULT 'Lifetime', -- display-only ("1 year", "Lifetime", ...)
    expires_at        INTEGER,                      -- unix ms, NULL = never expires
    allowed_servers   TEXT,                          -- JSON array of identifiers, NULL = no allowlist restriction
    blocked_servers   TEXT,                          -- JSON array of identifiers, NULL = none blocked
    notes             TEXT NOT NULL DEFAULT '',
    created_at        INTEGER NOT NULL,
    updated_at        INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_licenses_product ON licenses(product_id);
CREATE INDEX IF NOT EXISTS idx_licenses_status ON licenses(status);

CREATE TABLE IF NOT EXISTS builds (
    id              TEXT PRIMARY KEY,            -- e.g. "bld_xxxxx"
    product_id      TEXT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    jar_hash        TEXT NOT NULL,               -- sha256 hex, lowercase, 64 chars
    version         TEXT NOT NULL DEFAULT '',
    active          INTEGER NOT NULL DEFAULT 1,  -- 0/1
    reason          TEXT NOT NULL DEFAULT '',    -- shown to the plugin when active=0
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL,
    UNIQUE(product_id, jar_hash)
);

CREATE INDEX IF NOT EXISTS idx_builds_product ON builds(product_id);

CREATE TABLE IF NOT EXISTS server_bans (
    id              TEXT PRIMARY KEY,             -- e.g. "ban_xxxxx"
    product_id      TEXT REFERENCES products(id) ON DELETE CASCADE, -- NULL = global ban
    identifier      TEXT NOT NULL,                -- IP or domain/hostname, matched case-insensitively for domains
    reason          TEXT NOT NULL DEFAULT '',
    expires_at      INTEGER,                      -- unix ms, NULL = permanent
    status          TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','expired')),
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_server_bans_product ON server_bans(product_id);
CREATE INDEX IF NOT EXISTS idx_server_bans_identifier ON server_bans(identifier);

CREATE TABLE IF NOT EXISTS admin_audit_log (
    id              TEXT PRIMARY KEY,             -- e.g. "evt_xxxxx"
    created_at      INTEGER NOT NULL,
    admin_email     TEXT NOT NULL,
    action          TEXT NOT NULL,                -- e.g. "license.create"
    target_type     TEXT NOT NULL,                -- e.g. "License"
    target_id       TEXT NOT NULL DEFAULT '',
    ip              TEXT NOT NULL DEFAULT '',
    details         TEXT NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS idx_audit_created ON admin_audit_log(created_at DESC);

-- Tracks live/recently-seen servers for the "Active Servers" dashboard page.
-- Upserted on every successful /api/validate call; this is presentation data
-- only and is never consulted by validation logic itself.
CREATE TABLE IF NOT EXISTS server_sightings (
    id              TEXT PRIMARY KEY,             -- stable hash of product_id+best server identifier
    product_id      TEXT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    address         TEXT NOT NULL DEFAULT '',     -- best-known host/ip:port label
    version         TEXT NOT NULL DEFAULT '',
    last_seen_at    INTEGER NOT NULL,
    first_seen_at   INTEGER NOT NULL,
    country         TEXT NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS idx_sightings_product ON server_sightings(product_id);
CREATE INDEX IF NOT EXISTS idx_sightings_last_seen ON server_sightings(last_seen_at DESC);
