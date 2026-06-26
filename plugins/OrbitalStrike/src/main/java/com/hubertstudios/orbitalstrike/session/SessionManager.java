package com.hubertstudios.orbitalstrike.session;

import com.hubertstudios.orbitalstrike.config.StrikeType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds all active {@link RodSession}s and per-player cooldown timestamps.
 * Backed by ConcurrentHashMap since Folia may touch this from multiple
 * region threads concurrently (a player firing a rod in one region while
 * another rod they were given is interacted with on a different region's
 * thread, in edge cases involving teleporting between regions).
 */
public final class SessionManager {

    private final Map<UUID, RodSession> sessionsById = new ConcurrentHashMap<>();
    private final Map<UUID, Long> globalCooldownUntilTick = new ConcurrentHashMap<>();
    private final Map<String, Long> perTypeCooldownUntilTick = new ConcurrentHashMap<>();

    public RodSession create(UUID casterId, StrikeType type, long createdAtTick) {
        UUID id = UUID.randomUUID();
        RodSession session = new RodSession(id, casterId, type, createdAtTick);
        sessionsById.put(id, session);
        return session;
    }

    public RodSession get(UUID sessionId) {
        return sessionsById.get(sessionId);
    }

    public void remove(UUID sessionId) {
        RodSession s = sessionsById.remove(sessionId);
        if (s != null) {
            s.markExpired();
        }
    }

    public void setGlobalCooldown(UUID playerId, long untilTick) {
        globalCooldownUntilTick.put(playerId, untilTick);
    }

    public long getGlobalCooldownUntil(UUID playerId) {
        return globalCooldownUntilTick.getOrDefault(playerId, 0L);
    }

    public void setTypeCooldown(UUID playerId, StrikeType type, long untilTick) {
        perTypeCooldownUntilTick.put(key(playerId, type), untilTick);
    }

    public long getTypeCooldownUntil(UUID playerId, StrikeType type) {
        return perTypeCooldownUntilTick.getOrDefault(key(playerId, type), 0L);
    }

    private String key(UUID playerId, StrikeType type) {
        return playerId + ":" + type.key();
    }
}
