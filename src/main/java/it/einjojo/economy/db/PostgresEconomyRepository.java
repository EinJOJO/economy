package it.einjojo.economy.db;

import it.einjojo.economy.exception.RepositoryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL implementation of the {@link EconomyRepository}.
 * Handles database interactions using a {@link ConnectionProvider}.
 * All public methods execute blocking JDBC calls and are intended to be
 * invoked asynchronously by the service layer.
 */
public class PostgresEconomyRepository implements EconomyRepository {

    private static final Logger log = LoggerFactory.getLogger(PostgresEconomyRepository.class);
    private static final String CREATE_TRIGGER_FN_SQL = """
            CREATE OR REPLACE FUNCTION update_updated_at_column()
            RETURNS TRIGGER AS $$
            BEGIN
               NEW.updated_at = NOW();
               RETURN NEW;
            END;
            $$ language 'plpgsql';
            """;
    private static final String DEFAULT_TABLE_NAME = "player_balances";
    private final String tableName;
    private final ConnectionProvider connectionProvider;
    private final String createTableSql;
    private final String createTriggerSql;
    private final String findAccountSql;
    private final String updateConditionalSql;
    private final String upsertSetSql;
    private final String upsertIncrementSql;

    /**
     * Constructs a new PostgresEconomyRepository.
     *
     * @param connectionProvider Provides database connections. Must not be null.
     */
    public PostgresEconomyRepository(ConnectionProvider connectionProvider) {
        this(connectionProvider, DEFAULT_TABLE_NAME);
    }

    /**
     * Constructs a new PostgresEconomyRepository.
     *
     * @param connectionProvider Provides database connections. Must not be null.
     * @param tableName          The name of the table to use for storing account data. Must not be null or empty.
     */
    public PostgresEconomyRepository(ConnectionProvider connectionProvider, String tableName) {
        this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider cannot be null");
        Objects.requireNonNull(tableName, "tableName cannot be null");
        if (tableName.isBlank()) {
            throw new IllegalArgumentException("tableName cannot be empty");
        }
        this.tableName = tableName;
        createTableSql = """
                CREATE TABLE IF NOT EXISTS %s (
                    uuid UUID PRIMARY KEY,
                    balance DOUBLE PRECISION NOT NULL DEFAULT 0.0,
                    version BIGINT NOT NULL DEFAULT 0,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                );
                """.formatted(tableName);

        createTriggerSql = """
                DROP TRIGGER IF EXISTS trigger_%s_updated_at ON %s; -- Drop existing trigger first
                CREATE TRIGGER trigger_%s_updated_at
                BEFORE UPDATE ON %s
                FOR EACH ROW
                EXECUTE FUNCTION update_updated_at_column();
                """.formatted(tableName, tableName, tableName, tableName);

        findAccountSql = "SELECT uuid, balance, version FROM %s WHERE uuid = ?;".formatted(tableName);

        upsertIncrementSql = """
                INSERT INTO %s (uuid, balance, version) VALUES (?, ?, 0)
                ON CONFLICT (uuid) DO UPDATE SET
                  balance = %s.balance + EXCLUDED.balance,
                  version = %s.version + 1
                RETURNING balance;
                """.formatted(tableName, tableName, tableName);

        upsertSetSql = """
                INSERT INTO %s (uuid, balance, version) VALUES (?, ?, 0)
                ON CONFLICT (uuid) DO UPDATE SET
                  balance = EXCLUDED.balance,
                  version = %s.version + 1
                RETURNING balance;
                """.formatted(tableName, tableName);

        updateConditionalSql = """
                UPDATE %s SET balance = ?, version = version + 1
                WHERE uuid = ? AND version = ?;
                """.formatted(tableName);


    }


    @Override
    public void ensureSchemaExists() throws RepositoryException {
        log.info("Ensuring database schema for '{}' exists...", tableName);
        try (Connection conn = connectionProvider.getConnection();
             Statement stmt = conn.createStatement()) {
            log.debug("Executing: {}", createTableSql);
            stmt.execute(createTableSql);
            log.info("Table '{}' ensured.", tableName);
            try {
                log.debug("Ensuring updated_at trigger function exists...");
                stmt.execute(CREATE_TRIGGER_FN_SQL);
                log.debug("Ensuring updated_at trigger exists...");
                stmt.execute(createTriggerSql);
                log.info("Trigger 'trigger_{}_updated_at' ensured.", tableName);
            } catch (SQLException e) {
                log.warn("Could not ensure trigger setup (might be permissions or syntax issue, or already exists): {}", e.getMessage());
            }
        } catch (SQLException e) {
            log.error("Failed to ensure database schema", e);
            throw new RepositoryException("Failed to ensure database schema", e);
        }
    }

    @Override
    public Optional<AccountData> findAccountData(UUID playerUuid) throws RepositoryException {
        Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
        log.debug("Finding account data for UUID: {}", playerUuid);
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(findAccountSql)) {
            ps.setObject(1, playerUuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    AccountData data = new AccountData(
                            rs.getObject("uuid", UUID.class),
                            rs.getDouble("balance"),
                            rs.getLong("version")
                    );
                    log.debug("Found account data: {}", data);
                    return Optional.of(data);
                } else {
                    log.debug("Account data not found for UUID: {}", playerUuid);
                    return Optional.empty();
                }
            }
        } catch (SQLException e) {
            log.error("Failed to find account data for UUID: {}", playerUuid, e);
            throw new RepositoryException("Failed to find account data for UUID: " + playerUuid, e);
        }
    }

    @Override
    public double upsertAndIncrementBalance(UUID playerUuid, double amount) throws RepositoryException {
        Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount to increment must be positive: " + amount);
        }
        log.debug("Upserting and incrementing balance for UUID: {} by amount: {}", playerUuid, amount);
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(upsertIncrementSql)) {
            ps.setObject(1, playerUuid);
            ps.setDouble(2, amount);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    double newBalance = rs.getDouble(1);
                    log.debug("Upsert increment successful for UUID: {}. New balance: {}", playerUuid, newBalance);
                    return newBalance;
                } else {
                    // Should not happen with RETURNING clause if the upsert works
                    log.error("Upsert increment failed unexpectedly for UUID: {} (no balance returned)", playerUuid);
                    throw new RepositoryException("Upsert increment failed unexpectedly for UUID: " + playerUuid + " (no balance returned)", null);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to upsert/increment balance for UUID: {}", playerUuid, e);
            throw new RepositoryException("Failed to upsert/increment balance for UUID: " + playerUuid, e);
        }
    }


    @Override
    public boolean updateBalanceConditional(UUID playerUuid, double newBalance, long expectedVersion) throws RepositoryException {
        Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
        // Basic validation
        if (newBalance < 0) {
            throw new IllegalArgumentException("New balance cannot be negative: " + newBalance);
        }
        log.debug("Attempting conditional update for UUID: {} to balance: {} with expected version: {}", playerUuid, newBalance, expectedVersion);

        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(updateConditionalSql)) {

            ps.setDouble(1, newBalance);
            ps.setObject(2, playerUuid);
            ps.setLong(3, expectedVersion);
            int rowsAffected = ps.executeUpdate();
            if (rowsAffected == 1) {
                log.debug("Conditional update successful for UUID: {}", playerUuid);
                return true;
            } else {
                log.debug("Conditional update failed for UUID: {} (rows affected: {}). Expected version: {}", playerUuid, rowsAffected, expectedVersion);
                return false;
            }
        } catch (SQLException e) {
            log.error("Failed conditional update for UUID: {}", playerUuid, e);
            throw new RepositoryException("Failed conditional update for UUID: " + playerUuid, e);
        }
    }


    @Override
    public double upsertAndSetBalance(UUID playerUuid, double amount) throws RepositoryException {
        Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
        if (amount < 0) {
            throw new IllegalArgumentException("Amount to set must be non-negative: " + amount);
        }
        log.debug("Upserting and setting balance for UUID: {} to amount: {}", playerUuid, amount);

        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(upsertSetSql)) {
            ps.setObject(1, playerUuid);
            ps.setDouble(2, amount);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    double newBalance = rs.getDouble(1);
                    // Sanity check: newBalance should equal amount unless float precision issues occur
                    log.debug("Upsert set successful for UUID: {}. New balance: {}", playerUuid, newBalance);
                    return newBalance;
                } else {
                    // Should not happen with RETURNING clause
                    log.error("Upsert set failed unexpectedly for UUID: {} (no balance returned)", playerUuid);
                    throw new RepositoryException("Upsert set failed unexpectedly for UUID: " + playerUuid + " (no balance returned)", null);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to upsert/set balance for UUID: {}", playerUuid, e);
            throw new RepositoryException("Failed to upsert/set balance for UUID: " + playerUuid, e);
        }
    }

    /**
     * Getter
     *
     * @return The name of the table to use for storing account data.
     */
    public String getTableName() {
        return tableName;
    }

    /**
     * Getter
     *
     * @return The {@link ConnectionProvider} used by this repository.
     */
    public ConnectionProvider getConnectionProvider() {
        return connectionProvider;
    }
}