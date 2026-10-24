package dev.bountysystem.economy;

/** What a tracked payment does: ESCROW (placement charge), REFUND (returning escrow), PAYOUT (claim reward). */
public enum TransactionType {
    ESCROW,
    REFUND,
    PAYOUT
}
