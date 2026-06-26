package com.HubertStudios.coreguard.database;

import com.HubertStudios.coreguard.CoreGuard;

import java.io.File;
import java.sql.*;
import java.util.logging.Level;

public class DatabaseManager {
    private final CoreGuard plugin;
    private Connection connection;
    private String jdbcUrl;

    public DatabaseManager(CoreGuard plugin) {
        this.plugin = plugin;
    }

    public synchronized void open() throws SQLException {
        File dbFile = new File(plugin.getDataFolder(), plugin.getConfig().getString("storage.sqlite-file", "data/coreguard.db"));
        File parent = dbFile.getParentFile();
        if (parent != null) parent.mkdirs();
        jdbcUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        connection = DriverManager.getConnection(jdbcUrl);
        applyPragmas();
        migrate();
    }

    private void applyPragmas() throws SQLException {
        try (Statement st = connection.createStatement()) {
            if (plugin.getConfig().getBoolean("storage.sqlite-wal-enabled", true)) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
            }
            st.execute("PRAGMA busy_timeout=5000");
            st.execute("PRAGMA temp_store=MEMORY");
            st.execute("PRAGMA cache_size=-8000");
        }
    }

    public synchronized Connection connection() {
        try {
            if (connection == null || connection.isClosed()) {
                if (jdbcUrl == null) throw new SQLException("Database has not been opened yet");
                plugin.getLogger().warning("SQLite connection was closed; reconnecting.");
                connection = DriverManager.getConnection(jdbcUrl);
                applyPragmas();
            }
            if (!connection.isValid(2)) {
                plugin.getLogger().warning("SQLite connection failed health check; reconnecting.");
                try { connection.close(); } catch (SQLException ignored) {}
                connection = DriverManager.getConnection(jdbcUrl);
                applyPragmas();
            }
            return connection;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to obtain a healthy SQLite connection", e);
            throw new IllegalStateException("CoreGuard database connection is not available", e);
        }
    }

    private void migrate() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS schema_version (id INTEGER PRIMARY KEY CHECK (id = 1), version INTEGER NOT NULL DEFAULT 0)");
            st.executeUpdate("INSERT OR IGNORE INTO schema_version(id, version) VALUES(1, 0)");
        }
        int current = schemaVersion();
        if (current < 1) applyV1();
        if (current < 2) applyV2();
        if (current < 3) applyV3();
        setSchemaVersion(3);
    }

    private int schemaVersion() throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SELECT version FROM schema_version WHERE id=1")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void setSchemaVersion(int version) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE schema_version SET version=? WHERE id=1")) {
            ps.setInt(1, version);
            ps.executeUpdate();
        }
    }

    private void applyV1() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS items (fingerprint TEXT PRIMARY KEY, material TEXT NOT NULL, created_at INTEGER NOT NULL, created_by TEXT, creation_method TEXT, last_holder_uuid TEXT, last_holder_name TEXT, last_seen_at INTEGER, last_location TEXT, allowed_amount INTEGER DEFAULT 1, blacklisted INTEGER DEFAULT 0)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS item_history (id INTEGER PRIMARY KEY AUTOINCREMENT, fingerprint TEXT NOT NULL, holder_uuid TEXT, holder_name TEXT, location TEXT, action TEXT, timestamp INTEGER NOT NULL)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_item_history_fp ON item_history(fingerprint)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS players (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, ip TEXT, first_join INTEGER, last_join INTEGER, playtime_seconds INTEGER DEFAULT 0, ban_count INTEGER DEFAULT 0, mute_count INTEGER DEFAULT 0, warn_count INTEGER DEFAULT 0, kick_count INTEGER DEFAULT 0)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_players_ip ON players(ip)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS punishments (id INTEGER PRIMARY KEY AUTOINCREMENT, target_uuid TEXT, target_name TEXT, type TEXT NOT NULL, reason TEXT, executor_uuid TEXT, executor_name TEXT, created_at INTEGER NOT NULL, expires_at INTEGER, active INTEGER DEFAULT 1)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_punishments_uuid ON punishments(target_uuid)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS warnings (id INTEGER PRIMARY KEY AUTOINCREMENT, target_uuid TEXT, target_name TEXT, reason TEXT, executor_uuid TEXT, executor_name TEXT, created_at INTEGER NOT NULL)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_warnings_uuid ON warnings(target_uuid)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS audit_log (id INTEGER PRIMARY KEY AUTOINCREMENT, actor_uuid TEXT, actor_name TEXT, action TEXT NOT NULL, target_uuid TEXT, target_name TEXT, details TEXT, timestamp INTEGER NOT NULL)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON audit_log(timestamp DESC)");
        }
    }

    private void applyV2() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS ip_bans (ip TEXT PRIMARY KEY, reason TEXT, executor_name TEXT, created_at INTEGER NOT NULL, expires_at INTEGER)");
        }
    }

    private void applyV3() throws SQLException {
        try (Statement st = connection.createStatement()) {
            // Source of truth for active bans. A ban is keyed by whichever identifiers
            // were known at ban-time (uuid, lowercase name, ip). Any one of them matching
            // on login is enough to enforce the ban, because on offline-mode/cracked
            // servers neither UUID nor name nor IP is guaranteed to stay constant for a
            // given person, so no single identifier can be relied on alone.
            st.executeUpdate("CREATE TABLE IF NOT EXISTS active_bans (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "target_uuid TEXT, " +
                    "target_name TEXT, " +
                    "target_ip TEXT, " +
                    "reason TEXT, " +
                    "executor_name TEXT, " +
                    "created_at INTEGER NOT NULL, " +
                    "expires_at INTEGER, " +
                    "active INTEGER NOT NULL DEFAULT 1)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_active_bans_uuid ON active_bans(target_uuid)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_active_bans_name ON active_bans(target_name)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_active_bans_ip ON active_bans(target_ip)");
        }
    }

    public synchronized void close() {
        if (connection == null) return;
        try {
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            }
            connection.close();
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not cleanly close SQLite connection: " + e.getMessage());
        } finally {
            connection = null;
        }
    }
}
