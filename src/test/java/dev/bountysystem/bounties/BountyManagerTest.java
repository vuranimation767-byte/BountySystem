package dev.bountysystem.bounties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bountysystem.configuration.BountyConfig;
import dev.bountysystem.economy.EconomyProvider;
import dev.bountysystem.economy.InMemoryEconomyProvider;
import dev.bountysystem.economy.Transaction;
import dev.bountysystem.economy.TransactionStatus;
import dev.bountysystem.economy.TransactionType;
import dev.bountysystem.economy.ToggleableEconomyProvider;
import dev.bountysystem.storage.SqliteBountyStorage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Business logic and payment tests against the real SQLite storage and an
 * in-memory economy that can simulate outages and rejections.
 */
class BountyManagerTest {

    private static final UUID PLACER = UUID.randomUUID();
    private static final UUID PLACER_2 = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final Logger LOGGER = Logger.getLogger("bounty-test");

    @TempDir
    Path folder;

    private ToggleableEconomyProvider economy;
    private SqliteBountyStorage storage;
    private BountyManager manager;

    @BeforeEach
    void setUp() {
        storage = new SqliteBountyStorage(folder.resolve("bounties.db"), LOGGER);
        storage.open();
        economy = new ToggleableEconomyProvider();
        economy.setBalance(PLACER, 1_000.0);
        economy.setBalance(PLACER_2, 1_000.0);
        manager = newManager(false);
    }

    private BountyManager newManager(boolean selfClaimAllowed) {
        return new BountyManager(LOGGER, BountyConfig.forTesting(selfClaimAllowed), storage, economy);
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.shutdown();
        }
        storage.close();
    }

    @Test
    void createEscrowsRewardAndSurvivesRestart() throws Exception {
        Bounty bounty = manager.create(PLACER, TARGET, 100.0);
        assertEquals(900.0, economy.balance(PLACER));
        assertEquals(BountyStatus.ACTIVE, bounty.status());
        assertEquals(1, manager.activeBountiesOn(TARGET).size());

        // flush writes, close the database and load it back as after a server restart
        manager.shutdown();
        storage.close();

        SqliteBountyStorage reloadedStorage = new SqliteBountyStorage(folder.resolve("bounties.db"), LOGGER);
        reloadedStorage.open();
        BountyManager reloadedManager = new BountyManager(LOGGER, BountyConfig.forTesting(false), reloadedStorage, economy);
        assertEquals(1, reloadedManager.loadFromStorage());
        List<Bounty> onTarget = reloadedManager.activeBountiesOn(TARGET);
        assertEquals(1, onTarget.size());
        Bounty restored = onTarget.getFirst();
        assertEquals(bounty.id(), restored.id());
        assertEquals(bounty.placer(), restored.placer());
        assertEquals(bounty.target(), restored.target());
        assertEquals(bounty.amount(), restored.amount());
        assertEquals(bounty.createdAt(), restored.createdAt());
        assertEquals(bounty.status(), restored.status());

        // the placement escrow is tracked in the ledger as COMPLETED
        List<Transaction> ledger = reloadedStorage.loadTransactions();
        assertEquals(1, ledger.size());
        assertEquals(TransactionType.ESCROW, ledger.getFirst().type());
        assertEquals(TransactionStatus.COMPLETED, ledger.getFirst().status());
        assertEquals(bounty.id(), ledger.getFirst().bountyIds().getFirst());

        reloadedManager.shutdown();
        reloadedStorage.close();
    }

    @Test
    void severalPlacersCanBountyTheSameTarget() {
        manager.create(PLACER, TARGET, 100.0);
        manager.create(PLACER_2, TARGET, 250.0);
        assertEquals(2, manager.activeBountiesOn(TARGET).size());
    }

    @Test
    void rejectsSelfBounty() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> manager.create(PLACER, PLACER, 50.0));
        assertTrue(error.getMessage().contains("yourself"));
        assertEquals(1_000.0, economy.balance(PLACER)); // failed validation touched no funds
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, -5.0, Double.NaN, Double.POSITIVE_INFINITY, 100_000_000.01})
    void rejectsInvalidAmounts(double amount) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> manager.create(PLACER, TARGET, amount));
        assertTrue(error.getMessage().contains("amount"));
        assertEquals(1_000.0, economy.balance(PLACER));
    }

    @Test
    void rejectsBountyThatCannotBeAfforded() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> manager.create(PLACER, TARGET, 1_500.0));
        assertTrue(error.getMessage().contains("enough money"));
        assertEquals(1_000.0, economy.balance(PLACER));
    }

    @Test
    void allowsMultipleActiveBountiesOnTheSamePair() {
        Bounty first = manager.create(PLACER, TARGET, 100.0);
        Bounty second = manager.create(PLACER, TARGET, 250.0);
        assertEquals(2, manager.activeBountiesOn(TARGET).size());
        assertEquals(650.0, economy.balance(PLACER)); // both escrows were charged

        // the 8-char id shown by /bounty list removes exactly that one
        manager.cancelById(PLACER, first.id().toString().substring(0, 8));
        List<Bounty> remaining = manager.activeBountiesOn(TARGET);
        assertEquals(1, remaining.size());
        assertEquals(second.id(), remaining.getFirst().id());

        // pair-based cancel removes the oldest remaining bounty and refunds it
        manager.cancel(PLACER, TARGET);
        assertEquals(0, manager.activeBountiesOn(TARGET).size());
        assertEquals(1_000.0, economy.balance(PLACER));
    }

    @Test
    void cancelRefundsAndRecordsTheRefund() throws Exception {
        manager.create(PLACER, TARGET, 300.0);
        Bounty cancelled = manager.cancel(PLACER, TARGET);
        assertEquals(BountyStatus.CANCELLED, cancelled.status());
        assertEquals(1_000.0, economy.balance(PLACER));
        assertEquals(0, manager.activeBountiesOn(TARGET).size());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> manager.cancel(PLACER, TARGET));
        assertTrue(error.getMessage().contains("no active bounty"));

        manager.shutdown();
        List<Transaction> ledger = storage.loadTransactions();
        assertTrue(ledger.stream().anyMatch(tx -> tx.type() == TransactionType.REFUND
                && tx.status() == TransactionStatus.COMPLETED && tx.amount() == 300.0));
        assertTrue(storage.loadAll().stream().anyMatch(b -> b.id().equals(cancelled.id())
                && b.status() == BountyStatus.CANCELLED));
    }

    @Test
    void failedRefundKeepsBountyActive() throws Exception {
        manager.create(PLACER, TARGET, 300.0);
        economy.setDepositFailure(ToggleableEconomyProvider.DepositFailure.RETURN_FALSE);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> manager.cancel(PLACER, TARGET));
        assertTrue(error.getMessage().contains("refund was rejected"));
        assertEquals(700.0, economy.balance(PLACER)); // escrow unchanged
        assertEquals(1, manager.activeBountiesOn(TARGET).size()); // bounty still in play

        manager.shutdown();
        assertTrue(storage.loadTransactions().stream().anyMatch(tx -> tx.type() == TransactionType.REFUND
                && tx.status() == TransactionStatus.FAILED));
    }

    @Test
    void settleDeathPaysSumOfAllActiveBounties() throws Exception {
        manager.create(PLACER, TARGET, 100.0);
        manager.create(PLACER_2, TARGET, 250.0);
        UUID killer = UUID.randomUUID();

        assertEquals(350.0, manager.settleDeath(killer, TARGET).payout());
        assertEquals(350.0, economy.balance(killer));
        assertEquals(0, manager.activeBountiesOn(TARGET).size());

        // repeated kill events never pay twice
        assertEquals(0.0, manager.settleDeath(killer, TARGET).payout());
        assertEquals(350.0, economy.balance(killer));

        // payout recorded in the ledger, bounty rows completed
        manager.shutdown();
        List<Transaction> ledger = storage.loadTransactions();
        assertTrue(ledger.stream().anyMatch(tx -> tx.type() == TransactionType.PAYOUT
                && tx.status() == TransactionStatus.COMPLETED && tx.amount() == 350.0));
        assertTrue(storage.loadAll().stream().allMatch(b -> b.status() == BountyStatus.COMPLETED));

        // after a restart nothing is active, so another kill pays nothing
        BountyManager afterRestart = new BountyManager(LOGGER, BountyConfig.forTesting(false), storage, economy);
        afterRestart.loadFromStorage();
        assertEquals(0, afterRestart.activeBountiesOn(TARGET).size());
        afterRestart.shutdown();
    }

    @Test
    void deniedSelfClaimKeepsOwnBountyForLaterKills() {
        // default config: self-claim denied; PLACER bounty placed on TARGET
        manager.create(PLACER, TARGET, 100.0);

        // PLACER kills TARGET: own bounty is withheld, nobody pays on this kill
        KillPayout settlement = manager.settleDeath(PLACER, TARGET);
        assertEquals(0.0, settlement.payout());
        assertEquals(900.0, economy.balance(PLACER)); // escrow untouched
        assertEquals(1, manager.activeBountiesOn(TARGET).size()); // bounty stays attached

        // a different player does the kill → the withheld bounty pays out
        UUID otherKiller = UUID.randomUUID();
        assertEquals(100.0, manager.settleDeath(otherKiller, TARGET).payout());
        assertEquals(100.0, economy.balance(otherKiller));
        assertEquals(0, manager.activeBountiesOn(TARGET).size());
    }

    @Test
    void allowedSelfClaimPaysKillerForOwnBounty() {
        BountyManager selfClaimAllowed = newManager(true);
        selfClaimAllowed.create(PLACER, TARGET, 100.0);
        assertEquals(100.0, selfClaimAllowed.settleDeath(PLACER, TARGET).payout());
        assertEquals(1_000.0, economy.balance(PLACER)); // escrow returned through the payout, net zero
        assertEquals(0, selfClaimAllowed.activeBountiesOn(TARGET).size());
        selfClaimAllowed.shutdown();
    }

    @Test
    void payoutFailureRestoresBountiesForAnotherKill() throws Exception {
        manager.create(PLACER, TARGET, 100.0);
        economy.setDepositFailure(ToggleableEconomyProvider.DepositFailure.THROW_EXCEPTION);

        UUID killer = UUID.randomUUID();
        assertEquals(0.0, manager.settleDeath(killer, TARGET).payout());
        assertEquals(0.0, economy.balance(killer)); // provider failed → no money moved

        // everything restored: bounty is claimable again
        assertEquals(1, manager.activeBountiesOn(TARGET).size());
        assertEquals(BountyStatus.ACTIVE, manager.activeBountiesOn(TARGET).getFirst().status());

        economy.setDepositFailure(ToggleableEconomyProvider.DepositFailure.NONE);
        assertEquals(100.0, manager.settleDeath(killer, TARGET).payout());
        assertEquals(100.0, economy.balance(killer));

        manager.shutdown();
        List<Transaction> ledger = storage.loadTransactions();
        assertTrue(ledger.stream().anyMatch(tx -> tx.type() == TransactionType.PAYOUT
                && tx.status() == TransactionStatus.FAILED));
        assertTrue(ledger.stream().anyMatch(tx -> tx.type() == TransactionType.PAYOUT
                && tx.status() == TransactionStatus.COMPLETED && tx.amount() == 100.0));
    }

    @Test
    void generatedIdsAreUnique() {
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            ids.add(manager.create(PLACER, UUID.randomUUID(), 1.0).id());
        }
        assertEquals(50, ids.size());
    }

    @Test
    void createFailsWhenNoEconomyIsInstalled() {
        BountyManager withoutEconomy = new BountyManager(LOGGER, BountyConfig.forTesting(false), storage, null);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> withoutEconomy.create(PLACER, TARGET, 100.0));
        assertTrue(error.getMessage().contains("economy"));
        assertEquals(0, withoutEconomy.activeBountiesOn(TARGET).size());
        withoutEconomy.shutdown();
    }

    @Test
    void reconcilesInterruptedAndFailedLedgerEntries() throws Exception {
        // --- failed refund whose deposit was rejected: safe to retry at boot
        manager.create(PLACER, TARGET, 300.0);
        economy.setDepositFailure(ToggleableEconomyProvider.DepositFailure.RETURN_FALSE);
        assertThrows(IllegalStateException.class, () -> manager.cancel(PLACER, TARGET));
        economy.setDepositFailure(ToggleableEconomyProvider.DepositFailure.NONE);

        manager.reconcile();
        assertEquals(1_000.0, economy.balance(PLACER)); // retry paid the refund
        assertTrue(storage.loadOpenTransactions().isEmpty()); // retry marked it completed

        // --- pending payout: outcome unknown → never auto-paid, flagged for manual review
        Bounty seededBounty = new Bounty(UUID.randomUUID(), PLACER_2, UUID.randomUUID(), 50.0,
                java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS), BountyStatus.COMPLETED);
        storage.applyCompletion(List.of(seededBounty), new Transaction(UUID.randomUUID(),
                TransactionType.PAYOUT, List.of(seededBounty.id()), PLACER_2, 50.0,
                TransactionStatus.PENDING, "seed: crash after persist, before deposit",
                java.time.Instant.now(), java.time.Instant.now()));
        double before = economy.balance(PLACER_2);
        manager.reconcile();
        assertEquals(before, economy.balance(PLACER_2)); // no auto payment
        List<Transaction> ledger = storage.loadTransactions();
        assertTrue(ledger.stream().anyMatch(tx -> tx.type() == TransactionType.PAYOUT
                && tx.status() == TransactionStatus.FAILED));

        // --- pending escrow: ambiguous → flagged, never auto-refunded (no money printing)
        Bounty pending = new Bounty(UUID.randomUUID(), PLACER, UUID.randomUUID(), 10.0,
                java.time.Instant.now(), BountyStatus.ACTIVE);
        storage.insertTransaction(new Transaction(UUID.randomUUID(), TransactionType.ESCROW,
                List.of(pending.id()), PLACER, 10.0, TransactionStatus.PENDING, "seed: interrupted placement",
                java.time.Instant.now(), java.time.Instant.now()));
        manager.reconcile();
        assertEquals(1_000.0, economy.balance(PLACER)); // no automatic refund of an ambiguous escrow
        assertTrue(storage.loadTransactions().stream().anyMatch(tx -> tx.type() == TransactionType.ESCROW
                && tx.status() == TransactionStatus.FAILED));

        // --- failed escrow with no bounty row: left alone — its refund is already tracked
        // as a REFUND entry by the failed-placement path (retrying here could double-refund)
        Transaction orphanEscrow = new Transaction(UUID.randomUUID(), TransactionType.ESCROW,
                List.of(UUID.randomUUID()), PLACER, 20.0, TransactionStatus.FAILED,
                "seed: storage failed after withdraw", java.time.Instant.now(), java.time.Instant.now());
        storage.insertTransaction(orphanEscrow);
        manager.reconcile();
        assertEquals(1_000.0, economy.balance(PLACER)); // no boot refund of an escrow entry
        assertTrue(storage.loadTransactions().stream().anyMatch(tx -> tx.id().equals(orphanEscrow.id())
                && tx.status() == TransactionStatus.FAILED)); // untouched
    }

    // ------------------------------------------------------------------ stage 4: limits, cooldown, cancel-by-id, refund ratio, admin ledger

    @Test
    void cooldownBlocksRapidPlacementsPerPlacer() {
        BountyManager cooldownManager = new BountyManager(LOGGER, BountyConfig.forTesting(false, 60), storage, economy);
        cooldownManager.create(PLACER, TARGET, 50.0);
        assertEquals(950.0, economy.balance(PLACER));

        IllegalArgumentException blocked = assertThrows(IllegalArgumentException.class,
                () -> cooldownManager.create(PLACER, UUID.randomUUID(), 10.0));
        assertTrue(blocked.getMessage().contains("too quickly"));
        assertEquals(950.0, economy.balance(PLACER)); // the blocked attempt never charged

        // cooldown is per player: a different placer is unaffected
        cooldownManager.create(PLACER_2, TARGET, 50.0);
        assertEquals(950.0, economy.balance(PLACER_2));
        cooldownManager.shutdown();
    }

    @Test
    void cancelByIdAcceptsFullAndUniquePrefixIds() throws Exception {
        Bounty first = manager.create(PLACER, UUID.randomUUID(), 100.0);
        Bounty second = manager.create(PLACER, UUID.randomUUID(), 200.0);

        Bounty byFullId = manager.cancelById(PLACER, second.id().toString());
        assertEquals(second.id(), byFullId.id());

        Bounty byPrefix = manager.cancelById(PLACER, first.id().toString().substring(0, 8));
        assertEquals(first.id(), byPrefix.id());

        assertEquals(1_000.0, economy.balance(PLACER)); // both escrows refunded in full
        assertTrue(manager.activeBountiesBy(PLACER).isEmpty());
        assertEquals(2, manager.allBounties().size()); // cancelled rows remain in the admin view

        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> manager.cancelById(PLACER, "deadbeef00"));
        assertTrue(unknown.getMessage().contains("none of your active bounties"));
    }

    @Test
    void refundRatioReducesCancellationRefunds() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("bounties.cancellation.refund-ratio", 0.5);
        BountyManager partial = new BountyManager(LOGGER, BountyConfig.load(yaml, LOGGER), storage, economy);

        partial.create(PLACER, TARGET, 400.0);
        assertEquals(600.0, economy.balance(PLACER));
        partial.cancel(PLACER, TARGET); // 50% refund → 200
        assertEquals(800.0, economy.balance(PLACER));

        partial.shutdown();
        assertTrue(storage.loadTransactions().stream().anyMatch(tx -> tx.type() == TransactionType.REFUND
                && tx.status() == TransactionStatus.COMPLETED && tx.amount() == 200.0));
    }

    @Test
    void configOverridesChangeLimitsLiveOnReload() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("limits.min-amount", 50.0);
        yaml.set("limits.max-amount", 500.0);
        yaml.set("limits.placement-cooldown-seconds", 0);
        yaml.set("gui.presets", List.of(100.0, 500.0)); // presets must fit inside the test limits
        BountyManager limited = new BountyManager(LOGGER, BountyConfig.load(yaml, LOGGER), storage, economy);

        IllegalArgumentException below = assertThrows(IllegalArgumentException.class,
                () -> limited.create(PLACER, TARGET, 10.0));
        assertTrue(below.getMessage().contains("amount"));
        IllegalArgumentException above = assertThrows(IllegalArgumentException.class,
                () -> limited.create(PLACER, UUID.randomUUID(), 600.0));
        assertTrue(above.getMessage().contains("amount"));
        assertEquals(BountyStatus.ACTIVE, limited.create(PLACER, UUID.randomUUID(), 100.0).status());

        // /bounty reload swaps the validated config in (presets re-validated against the new limits)
        yaml.set("limits.min-amount", 200.0);
        yaml.set("gui.presets", List.of(200.0, 500.0));
        limited.applyReloadedConfig(BountyConfig.load(yaml, LOGGER));
        IllegalArgumentException afterReload = assertThrows(IllegalArgumentException.class,
                () -> limited.create(PLACER, UUID.randomUUID(), 100.0));
        assertTrue(afterReload.getMessage().contains("200.0"));
        limited.shutdown();
    }

    @Test
    void adminCanPayAndRejectStuckLedgerEntries() throws Exception {
        UUID seedPlayer = UUID.randomUUID(); // fresh funds-free player for predictable balances

        Transaction stuck = new Transaction(UUID.randomUUID(), TransactionType.PAYOUT,
                List.of(UUID.randomUUID()), seedPlayer, 33.0, TransactionStatus.PENDING,
                "seed: crash between persist and deposit",
                java.time.Instant.now(), java.time.Instant.now());
        storage.insertTransaction(stuck);

        // unknown ids resolve to false and move no money
        assertFalse(manager.adminPayTransaction(UUID.randomUUID(), "admin"));
        assertFalse(manager.adminRejectTransaction(UUID.randomUUID(), "admin"));
        assertEquals(0.0, economy.balance(seedPlayer)); // no money moved so far

        // pay path: deposit executes, entry closes
        assertTrue(manager.adminPayTransaction(stuck.id(), "admin"));
        assertEquals(33.0, economy.balance(seedPlayer));
        assertTrue(storage.loadOpenTransactions().isEmpty());

        // reject path: terminal, no money moves, excluded from open views forever
        Transaction doomed = new Transaction(UUID.randomUUID(), TransactionType.REFUND,
                List.of(UUID.randomUUID()), seedPlayer, 55.0, TransactionStatus.PENDING,
                "seed: orphaned refund",
                java.time.Instant.now(), java.time.Instant.now());
        storage.insertTransaction(doomed);
        assertTrue(manager.adminRejectTransaction(doomed.id(), "admin"));
        assertEquals(33.0, economy.balance(seedPlayer)); // untouched
        assertTrue(storage.loadOpenTransactions().isEmpty());
        assertTrue(storage.loadTransactions().stream().anyMatch(tx -> tx.id().equals(doomed.id())
                && tx.status() == TransactionStatus.REJECTED));
    }
}
