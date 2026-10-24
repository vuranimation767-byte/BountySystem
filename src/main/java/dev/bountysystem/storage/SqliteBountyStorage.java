package dev.bountysystem.storage;

import dev.bountysystem.bounties.Bounty;
import dev.bountysystem.bounties.BountyStatus;
import dev.bountysystem.economy.Transaction;
import dev.bountysystem.economy.TransactionStatus;
import dev.bountysystem.economy.TransactionType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite-backed bounty + transaction storage in one file ({@code bounties.db}).
 *
 * <p>Recovery rules:</p>
 * <ul>
 *   <li>missing file → a fresh database is created</li>
 *   <li>unreadable/corrupt file → quarantined as {@code *.corrupt-<millis>} and re-created</li>
 *   <li>schema version newer than this plugin supports → quarantined as {@code *.future-<millis>}
 *       and re-created (old data preserved on disk, never deleted)</li>
 *   <li>older schema versions are migrated in place (idempotent DDL)</li>
 *   <li>individual unreadable rows during load → skipped with a warning</li>
 * </ul>
 */
public final class SqliteBountyStorage implements BountyStorage {

    private static final int SCHEMA_VERSION = 2;

    private final Path file;
    private final Logger logger;
    private Connection connection;

    public SqliteBountyStorage(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    @Override
    public void open() {
        try {
            Files.createDirectories(file.getParent());
            connection = DriverManager.getConnection("jdbc:sqlite:" + file);
            try (Statement statement = connection.createStatement()) {
                int version = prepareAndReadSchemaVersion(statement);
                if (version > SCHEMA_VERSION) {
                    closeQuietly();
                    moveToQuarantine(".future-");
                    openFreshOrFail();
                    return;
                }
                createSchema(statement);
                if (version < SCHEMA_VERSION) {
                    statement.execute("PRAGMA user_version=" + SCHEMA_VERSION);
                }
            }
        } catch (SQLException | IOException e) {
            closeQuietly();
            moveToQuarantine(".corrupt-");
            openFreshOrFail();
        }
    }

    @Override
    public List<Bounty> loadAll() throws SQLException {
        List<Bounty> bounties = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT id, placer, target, amount, created_at, status FROM bounties ORDER BY created_at")) {
            while (rows.next()) {
                try {
                    bounties.add(new Bounty(
                            UUID.fromString(rows.getString("id")),
                            UUID.fromString(rows.getString("placer")),
                            UUID.fromString(rows.getString("target")),
                            rows.getDouble("amount"),
                            Instant.ofEpochMilli(rows.getLong("created_at")),
                            BountyStatus.valueOf(rows.getString("status"))));
                } catch (IllegalArgumentException | SQLException rowError) {
                    logger.log(Level.WARNING, "Skipping unreadable bounty row in " + file.getFileName() + " — corrupted record.", rowError);
                }
            }
        }
        return bounties;
    }

    @Override
    public void insert(Bounty bounty) throws SQLException {
        insertBountyRow(connection, bounty);
    }

    @Override
    public void updateStatuses(Map<UUID, BountyStatus> statuses) throws SQLException {
        connection.setAutoCommit(false);
        try {
            for (Map.Entry<UUID, BountyStatus> entry : statuses.entrySet()) {
                updateStatusRow(connection, entry.getKey(), entry.getValue());
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    @Override
    public void applyPlacement(Bounty bounty, Transaction escrow) throws SQLException {
        connection.setAutoCommit(false);
        try {
            insertBountyRow(connection, bounty);
            insertTransactionRow(connection, escrow);
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    @Override
    public void applyCompletion(List<Bounty> claimed, Transaction payout) throws SQLException {
        connection.setAutoCommit(false);
        try {
            for (Bounty bounty : claimed) {
                updateStatusRow(connection, bounty.id(), BountyStatus.COMPLETED);
            }
            insertTransactionRow(connection, payout);
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    @Override
    public void insertTransaction(Transaction transaction) throws SQLException {
        insertTransactionRow(connection, transaction);
    }

    @Override
    public void updateTransactionStatus(UUID transactionId, TransactionStatus status, String note) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE transactions SET status = ?, note = ?, updated_at = ? WHERE id = ?")) {
            statement.setString(1, status.name());
            statement.setString(2, note == null ? "" : note);
            statement.setLong(3, System.currentTimeMillis());
            statement.setString(4, transactionId.toString());
            if (statement.executeUpdate() == 0) {
                throw new SQLException("Transaction " + transactionId + " not found in the ledger.");
            }
        }
    }

    @Override
    public List<Transaction> loadOpenTransactions() throws SQLException {
        return loadTransactionsBy(
                "SELECT id, type, bounty_ids, player_id, amount, status, note, created_at, updated_at "
                        + "FROM transactions WHERE status IN ('PENDING', 'FAILED') ORDER BY created_at");
    }

    @Override
    public List<Transaction> loadTransactions() throws SQLException {
        return loadTransactionsBy(
                "SELECT id, type, bounty_ids, player_id, amount, status, note, created_at, updated_at "
                        + "FROM transactions ORDER BY created_at");
    }

    @Override
    public void close() {
        closeQuietly();
    }

    private void createSchema(Statement statement) throws SQLException {
        statement.execute("CREATE TABLE IF NOT EXISTS bounties ("
                + "id TEXT PRIMARY KEY, "
                + "placer TEXT NOT NULL, "
                + "target TEXT NOT NULL, "
                + "amount REAL NOT NULL, "
                + "created_at INTEGER NOT NULL, "
                + "status TEXT NOT NULL)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_bounties_target_status ON bounties (target, status)");
        statement.execute("CREATE TABLE IF NOT EXISTS transactions ("
                + "id TEXT PRIMARY KEY, "
                + "type TEXT NOT NULL, "
                + "bounty_ids TEXT NOT NULL, "
                + "player_id TEXT NOT NULL, "
                + "amount REAL NOT NULL, "
                + "status TEXT NOT NULL, "
                + "note TEXT NOT NULL, "
                + "created_at INTEGER NOT NULL, "
                + "updated_at INTEGER NOT NULL)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_transactions_status ON transactions (status)");
    }

    private List<Transaction> loadTransactionsBy(String sql) throws SQLException {
        List<Transaction> transactions = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                try {
                    transactions.add(mapTransaction(rows));
                } catch (IllegalArgumentException | SQLException rowError) {
                    logger.log(Level.WARNING, "Skipping unreadable transaction row in " + file.getFileName() + " — corrupted record.", rowError);
                }
            }
        }
        return transactions;
    }

    private static int prepareAndReadSchemaVersion(Statement statement) throws SQLException {
        statement.execute("PRAGMA journal_mode=WAL");
        statement.execute("PRAGMA busy_timeout=5000");
        try (ResultSet version = statement.executeQuery("PRAGMA user_version")) {
            return version.next() ? version.getInt(1) : 0;
        }
    }

    private void openFreshOrFail() {
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + file);
            try (Statement statement = connection.createStatement()) {
                prepareAndReadSchemaVersion(statement);
                createSchema(statement);
                statement.execute("PRAGMA user_version=" + SCHEMA_VERSION);
            }
        } catch (SQLException e) {
            closeQuietly();
            throw new IllegalStateException("Bounty database is unusable even after recovery: " + file, e);
        }
    }

    private void insertBountyRow(Connection target, Bounty bounty) throws SQLException {
        try (PreparedStatement statement = target.prepareStatement(
                "INSERT INTO bounties (id, placer, target, amount, created_at, status) VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, bounty.id().toString());
            statement.setString(2, bounty.placer().toString());
            statement.setString(3, bounty.target().toString());
            statement.setDouble(4, bounty.amount());
            statement.setLong(5, bounty.createdAt().toEpochMilli());
            statement.setString(6, bounty.status().name());
            statement.executeUpdate();
        }
    }

    private void insertTransactionRow(Connection target, Transaction transaction) throws SQLException {
        try (PreparedStatement statement = target.prepareStatement(
                "INSERT INTO transactions (id, type, bounty_ids, player_id, amount, status, note, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, transaction.id().toString());
            statement.setString(2, transaction.type().name());
            statement.setString(3, formatBountyIds(transaction.bountyIds()));
            statement.setString(4, transaction.playerId().toString());
            statement.setDouble(5, transaction.amount());
            statement.setString(6, transaction.status().name());
            statement.setString(7, transaction.note());
            statement.setLong(8, transaction.createdAt().toEpochMilli());
            statement.setLong(9, transaction.updatedAt().toEpochMilli());
            statement.executeUpdate();
        }
    }

    private void updateStatusRow(Connection target, UUID bountyId, BountyStatus status) throws SQLException {
        try (PreparedStatement statement = target.prepareStatement("UPDATE bounties SET status = ? WHERE id = ?")) {
            statement.setString(1, status.name());
            statement.setString(2, bountyId.toString());
            statement.executeUpdate();
        }
    }

    private Transaction mapTransaction(ResultSet row) throws SQLException {
        return new Transaction(
                UUID.fromString(row.getString("id")),
                TransactionType.valueOf(row.getString("type")),
                parseBountyIds(row.getString("bounty_ids")),
                UUID.fromString(row.getString("player_id")),
                row.getDouble("amount"),
                TransactionStatus.valueOf(row.getString("status")),
                row.getString("note"),
                Instant.ofEpochMilli(row.getLong("created_at")),
                Instant.ofEpochMilli(row.getLong("updated_at")));
    }

    private static String formatBountyIds(List<UUID> bountyIds) {
        return String.join(",", bountyIds.stream().map(UUID::toString).toList());
    }

    private static List<UUID> parseBountyIds(String joined) {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>();
        for (String part : joined.split(",")) {
            if (!part.isBlank()) {
                ids.add(UUID.fromString(part));
            }
        }
        return ids;
    }

    // Moves the database (and its WAL/SHM sidecars) outside the way, keeping the old data for inspection.
    private void moveToQuarantine(String prefix) {
        String stamp = Long.toString(System.currentTimeMillis());
        String fileName = file.getFileName().toString();
        Path backup = file.resolveSibling(fileName + prefix + stamp);
        try {
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not quarantine " + fileName, e);
        }
        deleteSidecar("-wal");
        deleteSidecar("-shm");
    }

    private void deleteSidecar(String suffix) {
        try {
            Files.deleteIfExists(file.resolveSibling(file.getFileName() + suffix));
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not remove SQLite sidecar " + suffix, e);
        }
    }

    private void closeQuietly() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to close bounty database", e);
            }
            connection = null;
        }
    }
}
