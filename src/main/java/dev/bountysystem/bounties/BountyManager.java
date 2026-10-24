package dev.bountysystem.bounties;

import dev.bountysystem.configuration.BountyConfig;
import dev.bountysystem.economy.EconomyProvider;
import dev.bountysystem.economy.Transaction;
import dev.bountysystem.economy.TransactionStatus;
import dev.bountysystem.economy.TransactionType;
import dev.bountysystem.storage.BountyStorage;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Core bounty business logic and payment handling — independent of commands and GUI.
 *
 * <p>Payout rules:</p>
 * <ul>
 *   <li>a placer may hold at most one ACTIVE bounty per target; duplicate requests are rejected
 *       until the first one is cancelled or completed</li>
 *   <li>many different placers can have active bounties on one target at the same time</li>
 *   <li>placement escrows the reward from the placer only after every validation passed;
 *       the bounty record and its ESCROW ledger entry are then committed atomically</li>
 *   <li>cancelling refunds the full escrow to the placer; a rejected refund leaves the bounty
 *       active (nobody loses money for an unsuccessful placement)</li>
 *   <li>a kill completes every active bounty on the victim EXCEPT self-placed ones when
 *       {@code bounties.self-claim: false} (they stay alive until another player kills)</li>
 * </ul>
 *
 * <p>Payment guarantees (honest limitations — see README "Transaction guarantees"):</p>
 * <ul>
 *   <li>Before any deposit attempt the claim rows AND the PENDING payout ledger entry are
 *       committed in one database transaction. A rejected deposit rolls everything back, so
 *       no bounty is ever paid twice.</li>
 *   <li>Because the economy provider and the database cannot participate in one atomic
 *       transaction, a crash at the exact boundary between "deposit succeeded" and "ledger
 *       marked COMPLETED" is unverifiable at restart. Such PENDING entries are marked FAILED
 *       and require manual review — payouts are never auto-retried (at-most-once).</li>
 *   <li>Refund retries are safe: a FAILED refund means the deposit was explicitly rejected,
 *       i.e. no money moved.</li>
 * </ul>
 */
public final class BountyManager {

    private static final long DB_CALL_TIMEOUT_SECONDS = 10;
    private static final long DB_FLUSH_TIMEOUT_SECONDS = 5;

    @FunctionalInterface
    private interface StorageCall<T> {
        T run() throws SQLException;
    }

    @FunctionalInterface
    private interface StorageAction {
        void run() throws SQLException;
    }

    private final Logger logger;
    private volatile BountyConfig config;
    private final BountyStorage storage;
    private final EconomyProvider economy; // null until a production economy is hooked in
    private final Map<UUID, Bounty> activeById = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> lastPlacement = new ConcurrentHashMap<>();
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BountySystem-DB");
        thread.setDaemon(true);
        return thread;
    });

    public BountyManager(Logger logger, BountyConfig config, BountyStorage storage, EconomyProvider economy) {
        this.logger = logger;
        this.config = config;
        this.storage = storage;
        this.economy = economy;
    }

    /** Whether placement and settlement are currently possible. */
    public boolean economyAvailable() {
        return economy != null;
    }

    /** Swaps in a freshly validated configuration (limits, cooldown, refund ratio, self-claim rule). */
    public void applyReloadedConfig(BountyConfig fresh) {
        this.config = fresh;
    }

    /** Current configuration snapshot (the command layer reads it for messages). */
    public BountyConfig config() {
        return config;
    }

    /** Number of active bounties currently in memory. */
    public int activeCount() {
        return activeById.size();
    }

    /**
     * Creates a bounty, escrowing the reward from the placer.
     *
     * @throws IllegalArgumentException on invalid amount, self-bounty, duplicate request
     *                                  or insufficient funds
     * @throws IllegalStateException    when no economy provider is installed or the record
     *                                  could not be persisted (escrow is refunded automatically)
     */
    public Bounty create(UUID placerId, UUID targetId, double amount) {
        EconomyProvider provider = economy();
        checkCooldown(placerId);
        validateAmount(amount);
        if (placerId.equals(targetId)) {
            throw new IllegalArgumentException("you cannot place a bounty on yourself");
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        synchronized (activeById) {
            // multiple active bounties per placer/target are allowed — cooldown + funds are the limits
            // charge only after every check passed — and never for an unrecordable bounty
            if (!provider.withdraw(placerId, amount)) {
                throw new IllegalArgumentException("you do not have enough money for this bounty");
            }
            Bounty bounty = new Bounty(UUID.randomUUID(), placerId, targetId, amount, now, BountyStatus.ACTIVE);
            Transaction escrow = new Transaction(UUID.randomUUID(), TransactionType.ESCROW,
                    List.of(bounty.id()), placerId, amount, TransactionStatus.COMPLETED, "", now, now);
            try {
                runNow(() -> storage.applyPlacement(bounty, escrow));
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to record bounty " + bounty.id() + " — refunding the escrow.", e);
                recordRefund(provider, placerId, bounty.amount(), "bounty could not be recorded", bounty.id());
                throw new IllegalStateException("the bounty could not be recorded — your escrow was refunded", e);
            }
            activeById.put(bounty.id(), bounty);
            lastPlacement.put(placerId, now);
            logger.info("Placed bounty " + bounty.id() + ": " + placerId + " → " + targetId
                    + " for " + bounty.amount()
                    + (config.refundRatio() < 1.0 ? " (refund ratio " + config.refundRatio() + ")" : ""));
            return bounty;
        }
    }

    private void checkCooldown(UUID placerId) {
        int cooldownSeconds = config.placementCooldownSeconds();
        if (cooldownSeconds <= 0) {
            return;
        }
        Instant last = lastPlacement.get(placerId);
        if (last != null && Instant.now().isBefore(last.plusSeconds(cooldownSeconds))) {
            throw new IllegalArgumentException("you are placing bounties too quickly — wait "
                    + cooldownSeconds + "s between placements");
        }
    }

    /**
     * Cancels the placer's oldest active bounty on the target and refunds the escrow
     * (reduced by the configured cancellation refund ratio). The id-based
     * {@link #cancelById(UUID, String)} removes a specific bounty.
     *
     * @throws IllegalArgumentException when the placer has no active bounty on that target
     * @throws IllegalStateException    when the refund was rejected — the bounty then stays active
     */
    public Bounty cancel(UUID placerId, UUID targetId) {
        Bounty bounty;
        synchronized (activeById) {
            bounty = findActive(placerId, targetId);
            if (bounty == null) {
                throw new IllegalArgumentException("you have no active bounty on that player");
            }
        }
        return doCancel(bounty);
    }

    /**
     * Cancels one of the placer's active bounties by its id — a full UUID or a unique
     * 8-character prefix of one. Only the placer may cancel.
     */
    public Bounty cancelById(UUID placerId, String idInput) {
        Bounty bounty;
        synchronized (activeById) {
            bounty = activeBountyById(placerId, idInput);
        }
        return doCancel(bounty);
    }

    private Bounty activeBountyById(UUID placerId, String idInput) {
        List<Bounty> mine = activeBountiesBy(placerId);
        try {
            UUID fullId = UUID.fromString(idInput.trim());
            return mine.stream().filter(bounty -> bounty.id().equals(fullId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("none of your active bounties matches that id"));
        } catch (IllegalArgumentException notUuid) {
            List<Bounty> matching = mine.stream()
                    .filter(bounty -> bounty.id().toString().startsWith(idInput.trim()))
                    .toList();
            if (matching.size() == 1) {
                return matching.getFirst();
            }
            if (matching.isEmpty()) {
                throw new IllegalArgumentException("none of your active bounties matches that id");
            }
            throw new IllegalArgumentException("that id matches several of your bounties — use the full id");
        }
    }

    private Bounty doCancel(Bounty bounty) {
        EconomyProvider provider = economy();
        double refundAmount = refundFor(bounty.amount());
        if (!safetyDeposit(provider, bounty.placer(), refundAmount)) {
            // no money moved — keep the bounty active instead of silently eating the escrow
            Transaction refund = new Transaction(UUID.randomUUID(), TransactionType.REFUND,
                    List.of(bounty.id()), bounty.placer(), refundAmount, TransactionStatus.FAILED,
                    "refund rejected — bounty remains active, retried at next boot", Instant.now(), Instant.now());
            recordTransactionsQuietly(List.of(refund));
            throw new IllegalStateException("the refund was rejected — your bounty stays active");
        }
        Bounty cancelled = new Bounty(bounty.id(), bounty.placer(), bounty.target(), bounty.amount(),
                bounty.createdAt(), BountyStatus.CANCELLED);
        Transaction refund = new Transaction(UUID.randomUUID(), TransactionType.REFUND,
                List.of(bounty.id()), bounty.placer(), refundAmount, TransactionStatus.COMPLETED, refundNote(),
                Instant.now(), Instant.now());
        synchronized (activeById) {
            activeById.remove(bounty.id());
        }
        try {
            runNow(() -> {
                storage.updateStatuses(Map.of(bounty.id(), BountyStatus.CANCELLED));
                storage.insertTransaction(refund);
            });
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Refund succeeded but the ledger update failed for bounty " + bounty.id(), e);
        }
        logger.info("Cancelled bounty " + bounty.id() + " — refunded " + refundAmount + " to placer " + bounty.placer());
        return cancelled;
    }

    /** Cancellation refund after the configured ratio, rounded to 2 decimals (no float dust). */
    private double refundFor(double amount) {
        double refund = amount * config.refundRatio();
        return Math.round(refund * 100.0) / 100.0;
    }

    /** Ledger note documenting a non-standard cancellation refund; empty at the default ratio 1.0. */
    private String refundNote() {
        double ratio = config.refundRatio();
        return ratio < 1.0 ? "cancellation refund (ratio " + ratio + ", remainder forfeit per configuration)" : "";
    }

    /**
     * Settles a victim's bounties after an eligible player kill. Every active bounty on the
     * victim is completed and paid to the killer in one payout — except self-placed bounties
     * when {@code self-claim} is denied: those stay attached to the victim and pay out to
     * whoever kills them later.
     *
     * @return what was paid; empty whenever nothing is payable (no active bounties, self-claim
     *         withheld, deposit rejected — everything is restored in that case)
     */
    public KillPayout settleDeath(UUID killerId, UUID victimId) {
        if (economy == null) {
            return new KillPayout(List.of(), 0.0); // bounties disabled: kills pay nothing, quietly
        }
        List<Bounty> claimed;
        synchronized (activeById) {
            claimed = activeBountiesOn(victimId).stream()
                    .filter(bounty -> config.selfClaimAllowed() || !bounty.placer().equals(killerId))
                    .collect(java.util.stream.Collectors.toList());
            if (claimed.isEmpty()) {
                return new KillPayout(List.of(), 0.0);
            }
            // write-ahead: pull claims out of memory before persisting; restored below on failure
            for (Bounty bounty : claimed) {
                activeById.remove(bounty.id());
            }
        }
        claimed.sort(Comparator.comparing(Bounty::createdAt));
        double payout = 0.0;
        for (Bounty bounty : claimed) {
            payout += bounty.amount();
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Transaction payoutTx = new Transaction(UUID.randomUUID(), TransactionType.PAYOUT,
                claimed.stream().map(Bounty::id).toList(), killerId, payout,
                TransactionStatus.PENDING, "", now, now);
        try {
            runNow(() -> storage.applyCompletion(claimed, payoutTx));
        } catch (Exception e) {
            restore(claimed);
            logger.log(Level.SEVERE, "Failed to record payout transaction — bounties remain claimable.", e);
            return new KillPayout(List.of(), 0.0);
        }
        if (safetyDeposit(economy, killerId, payout)) {
            try {
                runNow(() -> storage.updateTransactionStatus(payoutTx.id(), TransactionStatus.COMPLETED, "paid"));
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Payout succeeded but its ledger update failed — the entry stays open for review.", e);
            }
            logger.info("Paid out " + payout + " for " + claimed.size() + " completed bounty(ies) to killer " + killerId + ".");
            return new KillPayout(List.copyOf(claimed), payout);
        }
        // deposit rejected — verifiably no money moved: put the bounties back in play
        restore(claimed);
        try {
            runNow(() -> storage.updateTransactionStatus(payoutTx.id(), TransactionStatus.FAILED,
                    "deposit rejected — bounties restored to active"));
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to mark payout " + payoutTx.id() + " as failed in the ledger.", e);
        }
        return new KillPayout(List.of(), 0.0);
    }

    private void restore(List<Bounty> bounties) {
        synchronized (activeById) {
            for (Bounty bounty : bounties) {
                activeById.put(bounty.id(), bounty);
            }
        }
        queueRowStatus(bounties, BountyStatus.ACTIVE);
    }

    /**
     * Boot-time reconciliation of interrupted or failed ledger entries.
     * PENDING means "outcome unknown" — such entries are never auto-acted upon (no money moves),
     * they are marked FAILED and loudly reported for manual review. FAILED refunds are safe to
     * retry (a rejected deposit verifiably moved no money); PAYOUT and ESCROW entries never are.
     */
    public void reconcile() {
        List<Transaction> open;
        try {
            open = callNow(storage::loadOpenTransactions);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to read the transaction ledger — reconciliation skipped.", e);
            return;
        }
        for (Transaction tx : open) {
            switch (tx.type()) {
                case PAYOUT -> markInterrupted(tx,
                        "payout unverified — never auto-retried to prevent double payment; pay manually if confirmed");
                case ESCROW -> {
                    if (tx.status() == TransactionStatus.PENDING) {
                        markInterrupted(tx, "escrow outcome unknown — verify manually"); // ambiguous: never auto-refund
                    }
                    // FAILED escrows are left alone: any due refund is tracked as its own REFUND entry
                    // (retrying them here could double-refund after an already-successful immediate refund)
                }
                case REFUND -> {
                    if (tx.status() == TransactionStatus.FAILED && economy != null) {
                        retryRefund(tx); // rejected deposit = no money moved = safe retry
                    } else if (tx.status() == TransactionStatus.PENDING) {
                        markInterrupted(tx, "refund outcome unknown — verify manually");
                    }
                }
            }
        }
    }

    /** All active bounties on one target, oldest first. */
    public List<Bounty> activeBountiesOn(UUID targetId) {
        return filtered(bounty -> bounty.target().equals(targetId));
    }

    /** All active bounties placed by one player, oldest first. */
    public List<Bounty> activeBountiesBy(UUID placerId) {
        return filtered(bounty -> bounty.placer().equals(placerId));
    }

    /** Copy of all active bounties, oldest first. */
    public List<Bounty> activeBounties() {
        return filtered(bounty -> true);
    }

    /** Every bounty row ever persisted (any status) — admin inspection only. */
    public List<Bounty> allBounties() {
        return List.copyOf(callNow(storage::loadAll));
    }

    /** Open (PENDING/FAILED) ledger entries for the admin review commands. */
    public List<Transaction> openTransactions() {
        return List.copyOf(callNow(storage::loadOpenTransactions));
    }

    /**
     * Admin resolution of a stuck ledger entry by explicitly paying the due amount now
     * (the entry stays open if the deposit fails so it is never silently lost).
     *
     * @return {@code false} when the entry is unknown or the deposit was rejected
     */
    public boolean adminPayTransaction(UUID transactionId, String adminName) {
        Transaction target = findOpenTransaction(transactionId);
        if (target == null || economy == null) {
            return false;
        }
        boolean paid = safetyDeposit(economy, target.playerId(), target.amount());
        String note = paid
                ? "paid to " + target.playerId() + " by admin " + adminName + " after manual review"
                : "admin refund attempt rejected — entry stays open";
        try {
            runNow(() -> storage.updateTransactionStatus(transactionId,
                    paid ? TransactionStatus.COMPLETED : TransactionStatus.FAILED, note));
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to update ledger entry " + transactionId + " after admin payment.", e);
        }
        if (paid) {
            logger.info("Admin " + adminName + " manually resolved stuck transaction " + transactionId
                    + " (" + target.type() + ", " + target.amount() + " to " + target.playerId() + ").");
        }
        return paid;
    }

    /** Admin resolution by rejection: closes a stuck entry permanently without moving any money. */
    public boolean adminRejectTransaction(UUID transactionId, String adminName) {
        Transaction target = findOpenTransaction(transactionId);
        if (target == null) {
            return false;
        }
        try {
            runNow(() -> storage.updateTransactionStatus(transactionId, TransactionStatus.REJECTED,
                    "rejected by admin " + adminName + " — no payment, reviewed manually"));
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to reject ledger entry " + transactionId + ".", e);
            return false;
        }
        logger.info("Admin " + adminName + " rejected stuck transaction " + transactionId + " (" + target.type() + ").");
        return true;
    }

    private Transaction findOpenTransaction(UUID transactionId) {
        for (Transaction transaction : openTransactions()) {
            if (transaction.id().equals(transactionId)) {
                return transaction;
            }
        }
        return null;
    }

    /**
     * Restores bounties from storage; only ACTIVE records re-enter memory, while
     * completed/cancelled records remain in the database as history.
     *
     * @return the number of active bounties restored
     */
    public int loadFromStorage() {
        int restored = 0;
        for (Bounty bounty : callNow(storage::loadAll)) {
            if (bounty.status() == BountyStatus.ACTIVE) {
                activeById.put(bounty.id(), bounty);
                restored++;
            }
        }
        return restored;
    }

    /** Waits for pending database writes so nothing is lost at server shutdown. */
    public void shutdown() {
        dbExecutor.shutdown();
        try {
            if (!dbExecutor.awaitTermination(DB_FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                logger.log(Level.SEVERE, "Database writes did not finish within " + DB_FLUSH_TIMEOUT_SECONDS
                        + "s — recent bounty changes may be lost.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<Bounty> filtered(Predicate<Bounty> filter) {
        List<Bounty> list = new ArrayList<>();
        for (Bounty bounty : activeById.values()) {
            if (filter.test(bounty)) {
                list.add(bounty);
            }
        }
        list.sort(Comparator.comparing(Bounty::createdAt));
        return List.copyOf(list);
    }

    private void validateAmount(double amount) {
        if (!Double.isFinite(amount) || amount < config.minAmount() || amount > config.maxAmount()) {
            throw new IllegalArgumentException("amount must be between " + config.minAmount() + " and " + config.maxAmount());
        }
    }

    /** The placer's oldest active bounty on the target — multiple are allowed. */
    private Bounty findActive(UUID placerId, UUID targetId) {
        Bounty oldest = null;
        for (Bounty bounty : activeById.values()) {
            if (bounty.placer().equals(placerId) && bounty.target().equals(targetId)
                    && (oldest == null || bounty.createdAt().isBefore(oldest.createdAt()))) {
                oldest = bounty;
            }
        }
        return oldest;
    }

    private EconomyProvider economy() {
        if (economy == null) {
            throw new IllegalStateException("no economy provider is installed — bounties are disabled");
        }
        return economy;
    }

    /**
     * Runs a storage call on the background thread and waits for it: keeps all SQLite access
     * single-threaded while still blocking only briefly (local single-row writes are fast).
     */
    private <T> T callNow(StorageCall<T> call) {
        Future<T> future = dbExecutor.submit(call::run);
        try {
            return future.get(DB_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the bounty database", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Bounty database operation failed", cause);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Bounty database operation timed out", e);
        }
    }

    /** Same as {@link #callNow} for operations that return nothing. */
    private void runNow(StorageAction action) {
        Future<?> future = dbExecutor.submit(() -> {
            action.run();
            return null;
        });
        try {
            future.get(DB_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the bounty database", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Bounty database operation failed", cause);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Bounty database operation timed out", e);
        }
    }

    private boolean safetyDeposit(EconomyProvider provider, UUID playerId, double amount) {
        try {
            return provider.deposit(playerId, amount);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Economy deposit failed for " + playerId + " (" + amount + ")", e);
            return false;
        }
    }

    private void recordRefund(EconomyProvider provider, UUID playerId, double amount, String reason, UUID bountyId) {
        boolean refunded = safetyDeposit(provider, playerId, amount);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Transaction refund = new Transaction(UUID.randomUUID(), TransactionType.REFUND,
                List.of(bountyId), playerId, amount,
                refunded ? TransactionStatus.COMPLETED : TransactionStatus.FAILED,
                refunded ? "refund: " + reason : "refund rejected: " + reason + " — retried at next boot",
                now, now);
        recordTransactionsQuietly(List.of(refund));
    }

    private void recordTransactionsQuietly(List<Transaction> transactions) {
        try {
            runNow(() -> {
                for (Transaction transaction : transactions) {
                    storage.insertTransaction(transaction);
                }
            });
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to write " + transactions.size() + " ledger entr(ies) — manual review may be needed.", e);
        }
    }

    private void retryRefund(Transaction tx) {
        try {
            boolean refunded = safetyDeposit(economy, tx.playerId(), tx.amount());
            try {
                runNow(() -> storage.updateTransactionStatus(tx.id(),
                        refunded ? TransactionStatus.COMPLETED : TransactionStatus.FAILED,
                        refunded ? "refund paid during boot reconciliation" : "refund retry rejected — retried at next boot"));
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to update ledger entry " + tx.id() + " after its refund retry.", e);
            }
            if (refunded) {
                logger.info("Reconciliation paid pending refund " + tx.id() + " (" + tx.amount() + " to " + tx.playerId() + ").");
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Refund retry failed for transaction " + tx.id(), e);
        }
    }

    private void markInterrupted(Transaction tx, String note) {
        logger.log(Level.SEVERE, "Transaction " + tx.id() + " (" + tx.type() + ", " + tx.amount()
                + " to " + tx.playerId() + ") needs manual review: " + note);
        try {
            runNow(() -> storage.updateTransactionStatus(tx.id(), TransactionStatus.FAILED, note));
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to mark transaction " + tx.id() + " as failed in the ledger.", e);
        }
    }

    /** Fire-and-forget row state updates (row contents, never money decisions). */
    private void queueRowStatus(List<Bounty> bounties, BountyStatus status) {
        if (bounties.isEmpty()) {
            return;
        }
        Map<UUID, BountyStatus> updates = new LinkedHashMap<>();
        for (Bounty bounty : bounties) {
            updates.put(bounty.id(), status);
        }
        try {
            dbExecutor.execute(() -> {
                try {
                    storage.updateStatuses(updates);
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Failed to persist status change for " + updates.size() + " bounty record(s).", e);
                }
            });
        } catch (Exception e) {
            // a full/terminated pool (e.g. server shutting down mid-kill) must never throw at the caller
            logger.log(Level.SEVERE, "Could not queue status change for " + updates.size() + " bounty record(s).", e);
        }
    }
}
