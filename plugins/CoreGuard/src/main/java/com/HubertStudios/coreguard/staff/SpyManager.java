package com.HubertStudios.coreguard.staff;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SpyManager {
    private final Map<UUID, Set<UUID>> spying = new ConcurrentHashMap<>();

    public void toggle(UUID spy, UUID target) {
        Set<UUID> targets = spying.computeIfAbsent(spy, k -> ConcurrentHashMap.newKeySet());
        if (!targets.add(target)) {
            targets.remove(target);
            if (targets.isEmpty()) spying.remove(spy);
        }
    }

    public boolean watches(UUID spy, UUID target) {
        Set<UUID> targets = spying.get(spy);
        return targets != null && targets.contains(target);
    }

    public boolean isSpying(UUID spy) {
        Set<UUID> targets = spying.get(spy);
        return targets != null && !targets.isEmpty();
    }

    public void clearAll(UUID spy) {
        spying.remove(spy);
    }
}
