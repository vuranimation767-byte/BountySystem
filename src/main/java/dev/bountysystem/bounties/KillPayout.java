package dev.bountysystem.bounties;

import java.util.List;

/**
 * Outcome of settling a victim's bounties on a kill.
 *
 * <p>{@code claimed} is empty whenever nothing was paid (no active bounties,
 * or the deposit was rejected and everything was restored).</p>
 */
public record KillPayout(List<Bounty> claimed, double payout) {

    public KillPayout {
        claimed = List.copyOf(claimed);
    }
}
