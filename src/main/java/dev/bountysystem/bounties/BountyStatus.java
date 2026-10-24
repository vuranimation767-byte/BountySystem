package dev.bountysystem.bounties;

/**
 * Bounty lifecycle: ACTIVE (escrowed and payable), COMPLETED (target dealt with,
 * full combined reward paid out), CANCELLED (escrow refunded to the placer).
 */
public enum BountyStatus {
    ACTIVE,
    COMPLETED,
    CANCELLED
}
