package com.HubertStudios.coreguard.repositories;

import com.HubertStudios.coreguard.database.DatabaseManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.SQLException;

public class AuditRepository {
    private final DatabaseManager database;

    public AuditRepository(DatabaseManager database) {
        this.database = database;
    }

    public void log(CommandSender actor, String action, String targetUuid, String targetName, String details) {
        String actorUuid = actor instanceof Player p ? p.getUniqueId().toString() : "CONSOLE";
        String actorName = actor.getName();
        try (PreparedStatement ps = database.connection().prepareStatement("INSERT INTO audit_log(actor_uuid,actor_name,action,target_uuid,target_name,details,timestamp) VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, actorUuid);
            ps.setString(2, actorName);
            ps.setString(3, action);
            ps.setString(4, targetUuid);
            ps.setString(5, targetName);
            ps.setString(6, details);
            ps.setLong(7, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
