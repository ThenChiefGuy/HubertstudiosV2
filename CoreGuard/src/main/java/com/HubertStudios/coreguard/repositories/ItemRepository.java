package com.HubertStudios.coreguard.repositories;

import com.HubertStudios.coreguard.database.DatabaseManager;
import com.HubertStudios.coreguard.models.ItemRecord;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ItemRepository {
    private final DatabaseManager database;

    public ItemRepository(DatabaseManager database) {
        this.database = database;
    }

    public void upsertSeen(String fingerprint, String material, String method, String holderUuid, String holderName, String location, int allowedAmount) {
        long now = System.currentTimeMillis();
        String sql = "INSERT INTO items(fingerprint, material, created_at, created_by, creation_method, last_holder_uuid, last_holder_name, last_seen_at, last_location, allowed_amount) VALUES(?,?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(fingerprint) DO UPDATE SET last_holder_uuid=excluded.last_holder_uuid, last_holder_name=excluded.last_holder_name, last_seen_at=excluded.last_seen_at, last_location=excluded.last_location";
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setString(1, fingerprint);
            ps.setString(2, material);
            ps.setLong(3, now);
            ps.setString(4, holderUuid);
            ps.setString(5, method);
            ps.setString(6, holderUuid);
            ps.setString(7, holderName);
            ps.setLong(8, now);
            ps.setString(9, location);
            ps.setInt(10, allowedAmount);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("upsertSeen failed for fingerprint " + fingerprint, e);
        }
        addHistory(fingerprint, holderUuid, holderName, location, method);
    }

    /**
     * Returns the originally recorded material for a fingerprint, or null if unknown.
     * Used to detect when an item currently carrying this fingerprint has a different
     * material than it did when first seen — that's a signal of NBT tampering (the
     * fingerprint tag was copied onto, or transplanted into, a different/upgraded item)
     * rather than a normal sighting, and previously this was silently overwritten on
     * every scan instead of being flagged.
     */
    public String originalMaterial(String fingerprint) {
        try (PreparedStatement ps = database.connection().prepareStatement("SELECT material FROM items WHERE fingerprint=?")) {
            ps.setString(1, fingerprint);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("material") : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("originalMaterial failed for fingerprint " + fingerprint, e);
        }
    }

    public Optional<ItemRecord> find(String fingerprint) {
        try (PreparedStatement ps = database.connection().prepareStatement("SELECT * FROM items WHERE fingerprint=?")) {
            ps.setString(1, fingerprint);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(read(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("find failed for fingerprint " + fingerprint, e);
        }
    }

    public void setBlacklisted(String fingerprint, boolean blacklisted) {
        try (PreparedStatement ps = database.connection().prepareStatement("UPDATE items SET blacklisted=? WHERE fingerprint=?")) {
            ps.setInt(1, blacklisted ? 1 : 0);
            ps.setString(2, fingerprint);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("setBlacklisted failed", e);
        }
    }

    public void addHistory(String fingerprint, String holderUuid, String holderName, String location, String action) {
        try (PreparedStatement ps = database.connection().prepareStatement("INSERT INTO item_history(fingerprint, holder_uuid, holder_name, location, action, timestamp) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, fingerprint);
            ps.setString(2, holderUuid);
            ps.setString(3, holderName);
            ps.setString(4, location);
            ps.setString(5, action);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("addHistory failed", e);
        }
    }

    public List<String> history(String fingerprint, int limit) {
        List<String> lines = new ArrayList<>();
        String sql = "SELECT datetime(timestamp/1000,'unixepoch','localtime') AS ts, action, holder_name, location FROM item_history WHERE fingerprint=? ORDER BY timestamp DESC LIMIT ?";
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setString(1, fingerprint);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) lines.add(rs.getString("ts") + " | " + rs.getString("action") + " | " + rs.getString("holder_name") + " | " + rs.getString("location"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("history failed", e);
        }
        return lines;
    }

    private ItemRecord read(ResultSet rs) throws SQLException {
        return new ItemRecord(rs.getString("fingerprint"), rs.getString("material"), rs.getLong("created_at"), rs.getString("created_by"), rs.getString("creation_method"), rs.getString("last_holder_uuid"), rs.getString("last_holder_name"), rs.getLong("last_seen_at"), rs.getString("last_location"), rs.getInt("allowed_amount"), rs.getInt("blacklisted") == 1);
    }
}
