package com.hubertstudios.orbitalstrike.session;

import com.hubertstudios.orbitalstrike.config.StrikeType;
import com.hubertstudios.orbitalstrike.util.FoliaUtil;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Tracks the state of a single targeting rod instance, from the moment it's
 * given to a player through both reel stages. One session exists per
 * in-flight rod; identified by a unique id stamped into the rod item's
 * persistent data container (see RodFactory), NOT by player UUID alone -
 * this matters because a player could theoretically be handed a second rod
 * (e.g. via /orbital <type> <amount> [player]) while another is still
 * pending, and each must be tracked independently.
 */
public final class RodSession {

    public enum Stage { AWAITING_AIM, AIMED_AWAITING_FIRE, COMPLETE }

    private final UUID sessionId;
    private final UUID casterId;
    private final StrikeType type;
    private final long createdAtTick;

    private volatile Stage stage = Stage.AWAITING_AIM;
    private volatile Location lockedTarget;
    private volatile FoliaUtil.CancellableTask markerTask;
    private volatile boolean expired = false;

    public RodSession(UUID sessionId, UUID casterId, StrikeType type, long createdAtTick) {
        this.sessionId = sessionId;
        this.casterId = casterId;
        this.type = type;
        this.createdAtTick = createdAtTick;
    }

    public UUID sessionId() {
        return sessionId;
    }

    public UUID casterId() {
        return casterId;
    }

    public StrikeType type() {
        return type;
    }

    public long createdAtTick() {
        return createdAtTick;
    }

    public Stage stage() {
        return stage;
    }

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    public Location lockedTarget() {
        return lockedTarget;
    }

    public void setLockedTarget(Location lockedTarget) {
        this.lockedTarget = lockedTarget;
    }

    public FoliaUtil.CancellableTask markerTask() {
        return markerTask;
    }

    public void setMarkerTask(FoliaUtil.CancellableTask markerTask) {
        this.markerTask = markerTask;
    }

    public void cancelMarkerTask() {
        if (markerTask != null) {
            markerTask.cancel();
            markerTask = null;
        }
    }

    public boolean isExpired() {
        return expired;
    }

    public void markExpired() {
        this.expired = true;
        cancelMarkerTask();
    }
}
