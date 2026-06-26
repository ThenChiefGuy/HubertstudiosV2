package com.HubertStudios.coreguard.models;

public record ItemRecord(
        String fingerprint,
        String material,
        long createdAt,
        String createdBy,
        String creationMethod,
        String lastHolderUuid,
        String lastHolderName,
        long lastSeenAt,
        String lastLocation,
        int allowedAmount,
        boolean blacklisted
) {}
