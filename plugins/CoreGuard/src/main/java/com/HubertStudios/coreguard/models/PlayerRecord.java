package com.HubertStudios.coreguard.models;

public record PlayerRecord(
        String uuid,
        String name,
        String ip,
        long firstJoin,
        long lastJoin,
        long playtimeSeconds,
        int banCount,
        int muteCount,
        int warnCount,
        int kickCount
) {}
