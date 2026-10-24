package dev.bountysystem.economy;

import java.util.UUID;

/**
 * Economy abstraction for escrowing and paying out bounties.
 *
 * <p>Two production-safe implementations exist: {@link VaultEconomyProvider}
 * (the standard economy bridge) and {@link ItemEconomyProvider} (payment with
 * real inventory items). When neither is available the plugin runs with a
 * {@code null} provider and disables bounties with a clear startup message —
 * a fake economy is never used in production; the in-memory test provider
 * lives in the test sourceset only.</p>
 */
public interface EconomyProvider {

    /** Human-readable provider name for logs and diagnostics. */
    String name();

    /**
     * Atomically checks that the player can afford {@code amount} and deducts it.
     *
     * @return {@code false} when the player has insufficient funds
     */
    boolean withdraw(UUID playerId, double amount);

    /**
     * Credits the amount; never removes money.
     *
     * @return {@code false} when the payment could not be delivered (e.g. item
     *         payment to an offline player) — {@code false} means no money moved
     */
    boolean deposit(UUID playerId, double amount);
}
