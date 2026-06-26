package com.hubertstudios.orbitalstrike.strikes;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Executes one full strike sequence (which may internally repeat per the
 * type's repeat-count config) for a given target location and the player
 * who is credited as the source (caster for normal use, or the bomb
 * invoker for /orbital bomb - the target Location is what matters for
 * the actual effect, "source" is used for messages/attribution/owner-of-wolf
 * etc).
 *
 * Implementations MUST be safe to call repeatedly with no shared mutable
 * state between calls (each call is independent), and must do all
 * scheduling via FoliaUtil so they remain correct under Folia's region
 * threading model.
 */
public interface StrikeExecutor {

    /**
     * @param target   the resolved impact point (ignored by the dog strike,
     *                  which always centers on the source player instead)
     * @param source   the player credited with causing this strike
     * @param isBomb   true if invoked via /orbital bomb (skips warnings/extra
     *                  flavor that assume an aim-and-fire sequence already happened)
     */
    void execute(Location target, Player source, boolean isBomb);
}
