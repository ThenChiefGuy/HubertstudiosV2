package com.hubertstudios.orbitalstrike.listeners;

import com.hubertstudios.orbitalstrike.config.PluginConfig;
import com.hubertstudios.orbitalstrike.config.StrikeType;
import com.hubertstudios.orbitalstrike.session.RodFactory;
import com.hubertstudios.orbitalstrike.session.RodSession;
import com.hubertstudios.orbitalstrike.session.SessionManager;
import com.hubertstudios.orbitalstrike.strikes.StrikeExecutor;
import com.hubertstudios.orbitalstrike.strikes.StrikeRegistry;
import com.hubertstudios.orbitalstrike.targeting.TargetingService;
import com.hubertstudios.orbitalstrike.util.FoliaUtil;
import com.hubertstudios.orbitalstrike.util.TextUtil;
import org.bukkit.Location;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implements the two-reel aim/fire flow:
 *   - 1st reel (PlayerFishEvent with state FISHING -> caught state on a rod
 *     with no bobber yet... in practice Bukkit fires a single PlayerFishEvent
 *     per right-click-with-rod-and-no-bobber-out, with state IN_GROUND/FAILED_ATTEMPT/
 *     CAUGHT_FISH depending on what's in front of the player. To get a reliable
 *     "first reel" / "second reel" distinction regardless of what's in front of
 *     the rod, we track stage purely via RodSession state, not via the fish
 *     event's reported state: ANY PlayerFishEvent fired while holding an
 *     Orbital rod advances that rod's session by one stage.
 *
 * Stage 1 (AWAITING_AIM -> AIMED_AWAITING_FIRE): resolves and locks the
 * target via TargetingService (skipped for DOG, which has no target),
 * starts the optional live marker.
 *
 * Stage 2 (AIMED_AWAITING_FIRE -> COMPLETE): cancels the marker, consumes
 * the rod, hands off to the StrikeRegistry's executor for that type.
 */
public final class FishingRodListener implements Listener {

    private final Plugin plugin;
    private final PluginConfig config;
    private final SessionManager sessions;
    private final RodFactory rodFactory;
    private final TargetingService targeting;
    private final StrikeRegistry strikes;

    // Marker entities keyed by session id, so the live-aim updater task can
    // move/remove the correct BlockDisplay for a given in-progress session.
    private final Map<UUID, BlockDisplay> activeMarkers = new ConcurrentHashMap<>();

    public FishingRodListener(Plugin plugin, PluginConfig config, SessionManager sessions,
                               RodFactory rodFactory, TargetingService targeting, StrikeRegistry strikes) {
        this.plugin = plugin;
        this.config = config;
        this.sessions = sessions;
        this.rodFactory = rodFactory;
        this.targeting = targeting;
        this.strikes = strikes;
    }

    @EventHandler(ignoreCancelled = false)
    public void onFish(PlayerFishEvent event) {
        Player player = event.getPlayer();
        ItemStack rodItem = player.getInventory().getItemInMainHand();
        UUID sessionId = rodFactory.readSessionId(rodItem);
        if (sessionId == null) {
            // Also check off-hand, in case the player swapped hands.
            rodItem = player.getInventory().getItemInOffHand();
            sessionId = rodFactory.readSessionId(rodItem);
            if (sessionId == null) {
                return;
            }
        }

        RodSession session = sessions.get(sessionId);
        if (session == null || session.isExpired()) {
            return;
        }

        // Always cancel the vanilla fishing behavior (no bobber, no XP, no item loss).
        event.setCancelled(true);

        switch (session.stage()) {
            case AWAITING_AIM -> handleFirstReel(player, session);
            case AIMED_AWAITING_FIRE -> handleSecondReel(player, session);
            case COMPLETE -> { /* stale event after completion; ignore */ }
        }
    }

    private void handleFirstReel(Player player, RodSession session) {
        StrikeType type = session.type();

        if (type == StrikeType.DOG) {
            // DOG has no raycast target; the first reel is just a "prepare" beat.
            session.setStage(RodSession.Stage.AIMED_AWAITING_FIRE);
            playSound(player, config.soundTargetLocked());
            return;
        }

        Location target = targeting.resolveTarget(player);
        if (target == null) {
            player.sendMessage(TextUtil.prefixed(config.prefix(), "&cNo valid target in range."));
            sessions.remove(session.sessionId());
            removeRodItem(player, session.sessionId());
            return;
        }

        session.setLockedTarget(target);
        session.setStage(RodSession.Stage.AIMED_AWAITING_FIRE);
        playSound(player, config.soundTargetLocked());

        String mode = config.targetingMode();
        if ("none".equals(mode)) {
            return;
        }
        if ("fixed".equals(mode)) {
            BlockDisplay marker = targeting.spawnMarker(target);
            if (marker != null) activeMarkers.put(session.sessionId(), marker);
            return;
        }
        // "raycast" mode: keep re-resolving and moving the marker live until fired/expired.
        BlockDisplay marker = targeting.spawnMarker(target);
        if (marker != null) activeMarkers.put(session.sessionId(), marker);

        FoliaUtil.CancellableTask liveTask = FoliaUtil.runAtLocationTimer(plugin, player.getLocation(), 1,
                config.raycastUpdateIntervalTicks(), handle -> {
                    if (!player.isOnline() || session.isExpired() || session.stage() != RodSession.Stage.AIMED_AWAITING_FIRE) {
                        handle.cancel();
                        return;
                    }
                    Location updated = targeting.resolveTarget(player);
                    if (updated != null) {
                        session.setLockedTarget(updated);
                        BlockDisplay m = activeMarkers.get(session.sessionId());
                        if (m != null) {
                            targeting.moveMarker(m, updated);
                        }
                    }
                });
        session.setMarkerTask(liveTask);
    }

    private void handleSecondReel(Player player, RodSession session) {
        session.setStage(RodSession.Stage.COMPLETE);
        session.cancelMarkerTask();

        BlockDisplay marker = activeMarkers.remove(session.sessionId());
        if (marker != null) {
            targeting.removeMarker(marker);
        }

        playSound(player, config.soundFire());
        removeRodItem(player, session.sessionId());

        Location target = session.type() == StrikeType.DOG ? player.getLocation() : session.lockedTarget();
        StrikeExecutor executor = strikes.get(session.type());
        if (executor != null && target != null) {
            executor.execute(target, player, false);
        }

        sessions.remove(session.sessionId());
    }

    /** Removes the specific rod ItemStack (matched by stamped session id) from the player's hands/inventory. */
    private void removeRodItem(Player player, UUID sessionId) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (sessionId.equals(rodFactory.readSessionId(main))) {
            player.getInventory().setItemInMainHand(null);
            return;
        }
        ItemStack off = player.getInventory().getItemInOffHand();
        if (sessionId.equals(rodFactory.readSessionId(off))) {
            player.getInventory().setItemInOffHand(null);
            return;
        }
        // Fallback: scan the rest of the inventory in case it was moved out of hand.
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && sessionId.equals(rodFactory.readSessionId(stack))) {
                stack.setAmount(0);
                return;
            }
        }
    }

    private void playSound(Player player, org.bukkit.Sound sound) {
        if (sound != null) {
            player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
        }
    }
}
