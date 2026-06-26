package com.HubertStudios.coreguard.repositories;

import com.HubertStudios.coreguard.database.DatabaseManager;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class PunishmentRepository {
    private final DatabaseManager database;

    public PunishmentRepository(DatabaseManager database) {
        this.database = database;
    }

    public void addPunishment(String targetUuid, String targetName, String type, String reason, String executorUuid, String executorName, Long expiresAt) {
        String sql = "INSERT INTO punishments(target_uuid,target_name,type,reason,executor_uuid,executor_name,created_at,expires_at,active) VALUES(?,?,?,?,?,?,?,?,1)";
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setString(1, targetUuid);
            ps.setString(2, targetName);
            ps.setString(3, type);
            ps.setString(4, reason);
            ps.setString(5, executorUuid);
            ps.setString(6, executorName);
            ps.setLong(7, System.currentTimeMillis());
            if (expiresAt == null) ps.setNull(8, Types.BIGINT);
            else ps.setLong(8, expiresAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("addPunishment failed for " + targetUuid, e);
        }
    }

    public void addWarning(String targetUuid, String targetName, String reason, String executorUuid, String executorName) {
        String sql = "INSERT INTO warnings(target_uuid,target_name,reason,executor_uuid,executor_name,created_at) VALUES(?,?,?,?,?,?)";
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setString(1, targetUuid);
            ps.setString(2, targetName);
            ps.setString(3, reason);
            ps.setString(4, executorUuid);
            ps.setString(5, executorName);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("addWarning failed for " + targetUuid, e);
        }
    }

    public List<String> warnings(String targetUuid) {
        List<String> out = new ArrayList<>();
        String sql = "SELECT id, datetime(created_at/1000,'unixepoch','localtime') AS ts, executor_name, reason FROM warnings WHERE target_uuid=? ORDER BY created_at DESC";
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setString(1, targetUuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add("#" + rs.getInt("id") + " | " + rs.getString("ts") + " | " + rs.getString("executor_name") + " | " + rs.getString("reason"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("warnings query failed", e);
        }
        return out;
    }

    public void clearWarnings(String targetUuid) {
        try (PreparedStatement ps = database.connection().prepareStatement("DELETE FROM warnings WHERE target_uuid=?")) {
            ps.setString(1, targetUuid);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("clearWarnings failed", e);
        }
    }

    public List<String> history(String targetUuid) {
        List<String> out = new ArrayList<>();
        String sql = "SELECT id, type, datetime(created_at/1000,'unixepoch','localtime') AS ts, executor_name, reason, expires_at FROM punishments WHERE target_uuid=? ORDER BY created_at DESC LIMIT 50";
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setString(1, targetUuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long expiresAt = rs.getLong("expires_at");
                    String expires = rs.wasNull() || expiresAt == 0L ? "permanent" : java.time.Instant.ofEpochMilli(expiresAt).toString();
                    out.add("#" + rs.getInt("id") + " | " + rs.getString("type") + " | " + rs.getString("ts") + " | " + rs.getString("executor_name") + " | " + rs.getString("reason") + " | expires: " + expires);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("history query failed", e);
        }
        return out;
    }

    /**
     * Records an active ban keyed by every identifier known at ban-time (uuid, lowercase
     * name, ip — any of which may be null/absent). This is the persisted source of truth
     * for ban enforcement; it survives restarts, unlike an in-memory map.
     */
    public void addActiveBan(String targetUuid, String targetName, String targetIp, String reason, String executorName, Long expiresAt) {
        String sql = "INSERT INTO active_bans(target_uuid,target_name,target_ip,reason,executor_name,created_at,expires_at,active) VALUES(?,?,?,?,?,?,?,1)";
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setString(1, targetUuid);
            ps.setString(2, targetName == null ? null : targetName.toLowerCase(java.util.Locale.ROOT));
            ps.setString(3, targetIp);
            ps.setString(4, reason);
            ps.setString(5, executorName);
            ps.setLong(6, System.currentTimeMillis());
            if (expiresAt == null) ps.setNull(7, Types.BIGINT);
            else ps.setLong(7, expiresAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("addActiveBan failed for " + targetUuid, e);
        }
    }

    /** Deactivates every active ban row matching the given uuid or lowercase name (an unban clears both). */
    public void deactivateBan(String targetUuid, String lowercaseName) {
        String sql = "UPDATE active_bans SET active=0 WHERE active=1 AND ((target_uuid IS NOT NULL AND target_uuid=?) OR (target_name IS NOT NULL AND target_name=?))";
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setString(1, targetUuid);
            ps.setString(2, lowercaseName);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("deactivateBan failed", e);
        }
    }

    /** Loads every currently-active (not yet expired) ban row, for rebuilding in-memory lockdown state on enable. */
    public List<ActiveBanRow> loadActiveBans() {
        List<ActiveBanRow> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        String sql = "SELECT target_uuid, target_name, target_ip, reason, expires_at FROM active_bans WHERE active=1 AND (expires_at IS NULL OR expires_at > ?)";
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setLong(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long expiresAt = rs.getLong("expires_at");
                    Long expires = rs.wasNull() ? null : expiresAt;
                    out.add(new ActiveBanRow(rs.getString("target_uuid"), rs.getString("target_name"), rs.getString("target_ip"), rs.getString("reason"), expires));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("loadActiveBans failed", e);
        }
        return out;
    }

    public record ActiveBanRow(String uuid, String lowercaseName, String ip, String reason, Long expiresAtMillis) {}
}
