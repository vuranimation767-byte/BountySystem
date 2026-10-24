package dev.bountysystem.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bountysystem.bounties.Bounty;
import dev.bountysystem.bounties.BountyStatus;
import dev.bountysystem.economy.Transaction;
import dev.bountysystem.economy.TransactionStatus;
import dev.bountysystem.economy.TransactionType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Storage tests: bounty + ledger persistence round-trips and recovery from
 * missing, corrupt and future-versioned database files.
 */
class SqliteBountyStorageTest {

    private static final Logger LOGGER = Logger.getLogger("sqlite-test");

    @TempDir
    Path folder;

    private Bounty bounty(long createdAtEpochMilli, double amount, BountyStatus status) {
        return new Bounty(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                amount, Instant.ofEpochMilli(createdAtEpochMilli), status);
    }

    private Transaction transaction(UUID bountyId, TransactionType type, double amount,
                                    TransactionStatus status, String note, long createdAt) {
        return new Transaction(UUID.randomUUID(), type, List.of(bountyId), UUID.randomUUID(),
                amount, status, note, Instant.ofEpochMilli(createdAt), Instant.ofEpochMilli(createdAt));
    }

    @Test
    void roundTripsRecordsOfEveryStatus() throws Exception {
        Path file = folder.resolve("bounties.db");
        Bounty active = bounty(1_700_000_000_000L, 12.5, BountyStatus.ACTIVE);
        Bounty completed = bounty(1_700_000_001_000L, 250.0, BountyStatus.COMPLETED);
        Bounty cancelled = bounty(1_700_000_002_000L, 5.0, BountyStatus.CANCELLED);

        SqliteBountyStorage writer = new SqliteBountyStorage(file, LOGGER);
        writer.open();
        writer.insert(active);
        writer.insert(completed);
        writer.insert(cancelled);
        writer.close();

        SqliteBountyStorage reader = new SqliteBountyStorage(file, LOGGER);
        reader.open();
        List<Bounty> loaded = reader.loadAll();
        reader.close();

        assertEquals(List.of(active, completed, cancelled), loaded);
    }

    @Test
    void roundTripsLedgerEntriesOfEveryStatus() throws Exception {
        Path file = folder.resolve("bounties.db");
        UUID bountyA = UUID.randomUUID();
        UUID bountyB = UUID.randomUUID();
        Transaction escrow = transaction(bountyA, TransactionType.ESCROW, 100.0,
                TransactionStatus.COMPLETED, "", 1_700_000_000_000L);
        Transaction payout = new Transaction(UUID.randomUUID(), TransactionType.PAYOUT,
                List.of(bountyA, bountyB), UUID.randomUUID(), 350.0, TransactionStatus.PENDING,
                "seed", Instant.ofEpochMilli(1_700_000_001_000L), Instant.ofEpochMilli(1_700_000_001_000L));
        Transaction refund = transaction(bountyB, TransactionType.REFUND, 50.0,
                TransactionStatus.FAILED, "refund rejected", 1_700_000_002_000L);

        SqliteBountyStorage writer = new SqliteBountyStorage(file, LOGGER);
        writer.open();
        writer.insertTransaction(escrow);
        writer.insertTransaction(payout);
        writer.insertTransaction(refund);
        assertEquals(List.of(escrow, payout, refund), writer.loadTransactions());
        assertEquals(List.of(payout, refund), writer.loadOpenTransactions());
        writer.close();

        SqliteBountyStorage reader = new SqliteBountyStorage(file, LOGGER);
        reader.open();
        assertEquals(List.of(escrow, payout, refund), reader.loadTransactions());
        reader.close();
    }

    @Test
    void applyCompletionPersistsRowsAndPendingPayoutAtomically() throws Exception {
        Path file = folder.resolve("bounties.db");
        Bounty bounty = bounty(1_700_000_000_000L, 12.5, BountyStatus.ACTIVE);
        Transaction payout = transaction(bounty.id(), TransactionType.PAYOUT, 12.5,
                TransactionStatus.PENDING, "", 1_700_000_001_000L);

        SqliteBountyStorage storage = new SqliteBountyStorage(file, LOGGER);
        storage.open();
        storage.insert(bounty);
        storage.applyCompletion(List.of(bounty), payout);

        assertTrue(storage.loadAll().stream().allMatch(b -> b.status() == BountyStatus.COMPLETED));
        assertTrue(storage.loadOpenTransactions().stream().anyMatch(tx -> tx.id().equals(payout.id())));
        storage.close();
    }

    @Test
    void updateTransactionStatusFailsForUnknownEntries() throws Exception {
        SqliteBountyStorage storage = new SqliteBountyStorage(folder.resolve("bounties.db"), LOGGER);
        storage.open();
        org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class,
                () -> storage.updateTransactionStatus(UUID.randomUUID(), TransactionStatus.FAILED, "nope"));
        storage.close();
    }

    @Test
    void recoversFromCorruptedDatabaseFile() throws Exception {
        Path file = folder.resolve("bounties.db");
        Files.writeString(file, "this is not a database, just text bytes pretending to be one");

        SqliteBountyStorage storage = new SqliteBountyStorage(file, LOGGER);
        storage.open(); // must not throw: quarantines the corrupt file and starts fresh
        assertTrue(storage.loadAll().isEmpty());
        storage.close();

        assertTrue(quarantinedFileExists(folder, "bounties.db.corrupt-"));
    }

    @Test
    void quarantinesDatabaseWrittenByNewerPluginVersion() throws Exception {
        Path file = folder.resolve("bounties.db");

        SqliteBountyStorage writer = new SqliteBountyStorage(file, LOGGER);
        writer.open();
        writer.insert(bounty(1_700_000_000_000L, 12.5, BountyStatus.ACTIVE));
        writer.close();

        // simulate a database written by a NEWER plugin version
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version=99");
        }

        SqliteBountyStorage reopened = new SqliteBountyStorage(file, LOGGER);
        reopened.open(); // must not throw: quarantines and starts fresh
        assertTrue(reopened.loadAll().isEmpty());
        reopened.close();

        assertTrue(quarantinedFileExists(folder, "bounties.db.future-"));
    }

    private static boolean quarantinedFileExists(Path folder, String prefix) throws Exception {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.map(path -> path.getFileName().toString())
                    .anyMatch(name -> name.startsWith(prefix));
        }
    }
}
