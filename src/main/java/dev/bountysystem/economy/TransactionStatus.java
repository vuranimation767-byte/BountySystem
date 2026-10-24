package dev.bountysystem.economy;

/**
 * Transaction lifecycle used for payment recovery:
 * PENDING = written before the money moved (outcome unknown after a crash),
 * COMPLETED = money moved and recorded, FAILED = rejected or requires manual review,
 * REJECTED = deliberately closed by an administrator (never retried, never auto-paid).
 */
public enum TransactionStatus {
    PENDING,
    COMPLETED,
    FAILED,
    REJECTED
}
