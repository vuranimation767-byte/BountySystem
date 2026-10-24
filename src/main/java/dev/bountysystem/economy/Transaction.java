package dev.bountysystem.economy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Ledger entry for one money movement (or attempted movement).
 *
 * @param id        unique transaction id
 * @param type      what the payment does (escrow/refund/payout)
 * @param bountyIds bounties involved in this movement
 * @param playerId  who the money was charged from or paid to
 * @param amount    amount moved (or attempted)
 * @param status    lifecycle status
 * @param note      short explanation for failed/reconciled entries; empty when normal
 * @param createdAt when the movement was first recorded
 * @param updatedAt last status change
 */
public record Transaction(UUID id, TransactionType type, List<UUID> bountyIds, UUID playerId,
                          double amount, TransactionStatus status, String note,
                          Instant createdAt, Instant updatedAt) {

    public Transaction {
        bountyIds = List.copyOf(bountyIds);
        note = note == null ? "" : note;
    }
}
