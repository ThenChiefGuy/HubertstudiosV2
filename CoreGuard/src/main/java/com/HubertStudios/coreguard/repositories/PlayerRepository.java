package com.HubertStudios.coreguard.repositories;

import com.HubertStudios.coreguard.database.DatabaseManager;
import com.HubertStudios.coreguard.models.PlayerRecord;
import org.bukkit.entity.Player;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PlayerRepository {
    private final DatabaseManager database;

    public PlayerRepository(DatabaseManager database) {
        this.database = database;
    }

    public void upsertJoin(Player player) {
        long now = System.currentTimeMillis();
        String ip = player.getAddress() == null ? "unknown" : player.getAddress().getAddress().getHostAddress();
        String sql = "INSERT INTO players(uuid, name, ip, first_join, last_join) VALUES(?,?,?,?,?) " +
                "ON CONFLICT(uuid) DO UPDATE SET name=excluded.name, ip=excluded.ip, last_join=excluded.last_join";
        try (PreparedStatement ps = database.connection().prepareStatement(sql)) {
            ps.setString(1, player.getUniqueId().toString());
            ps.setString(2, player.getName());
            ps.setString(3, ip);
            ps.setLong(4, now);
            ps.setLong(5, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<PlayerRecord> find(String uuid) {
        try (PreparedStatement ps = database.connection().prepareStatement("SELECT * FROM players WHERE uuid=?")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(read(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<PlayerRecord> findByIp(String ip) {
        List<PlayerRecord> out = new ArrayList<>();
        try (PreparedStatement ps = database.connection().prepareStatement("SELECT * FROM players WHERE ip=?")) {
            ps.setString(1, ip);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(read(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    public void increment(String uuid, String column) {
        if (!List.of("ban_count", "mute_count", "warn_count", "kick_count").contains(column)) return;
        try (PreparedStatement ps = database.connection().prepareStatement("UPDATE players SET " + column + "=" + column + "+1 WHERE uuid=?")) {
            ps.setString(1, uuid);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private PlayerRecord read(ResultSet rs) throws SQLException {
        return new PlayerRecord(
                rs.getString("uuid"),
                rs.getString("name"),
                rs.getString("ip"),
                rs.getLong("first_join"),
                rs.getLong("last_join"),
                rs.getLong("playtime_seconds"),
                rs.getInt("ban_count"),
                rs.getInt("mute_count"),
                rs.getInt("warn_count"),
                rs.getInt("kick_count")
        );
    }
}
