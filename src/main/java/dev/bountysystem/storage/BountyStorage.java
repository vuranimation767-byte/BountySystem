package dev.bountysystem.storage;

import dev.bountysystem.bounties.Bounty;
import dev.bountysystem.bounties.BountyStatus;
import dev.bountysystem.economy.Transaction;
import dev.bountysystem.economy.TransactionStatus;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent bounty and transaction storage.
 *
 * <p>Implementations do not need to be thread-safe: the bounty manager
 * serialises all storage access through a single background thread.</p>
 */
public interface BountyStorage {

    /**
     * Opens or creates the backing store, applying schema versioning and
     * recovery for missing, corrupted or future-versioned files.
     *
     * @throws IllegalStateException when the store is unusable even after recovery
     */
    void open();

    /** Every bounty ever stored, in creation order; unreadable rows are skipped with a warning. */
    List<Bounty> loadAll() throws SQLException;

    /** Persists a new bounty record alone (setup/seed scenarios). */
    void insert(Bounty bounty) throws SQLException;

    /** Applies state changes to bounty rows atomically: all succeed or none do. */
    void updateStatuses(Map<UUID, BountyStatus> statuses) throws SQLException;

    /**
     * Records a fresh bounty together with its (completed) escrow transaction in
     * one database transaction.
     */
    void applyPlacement(Bounty bounty, Transaction escrow) throws SQLException;

    /**
     * Marks bounties COMPLETED and stores the PENDING payout transaction in one
     * database transaction — persisted strictly before the deposit is attempted.
     */
    void applyCompletion(List<Bounty> claimed, Transaction payout) throws SQLException;

    /** Adds a ledger entry. */
    void insertTransaction(Transaction transaction) throws SQLException;

    /** Updates the status (and note) of a ledger entry; throws when the id is unknown. */
    void updateTransactionStatus(UUID transactionId, TransactionStatus status, String note) throws SQLException;

    /** All PENDING and FAILED ledger entries, oldest first (reconciliation input). */
    List<Transaction> loadOpenTransactions() throws SQLException;

    /** Every ledger entry ever recorded, oldest first. */
    List<Transaction> loadTransactions() throws SQLException;

    /** Releases the backing store. */
    void close();
}
