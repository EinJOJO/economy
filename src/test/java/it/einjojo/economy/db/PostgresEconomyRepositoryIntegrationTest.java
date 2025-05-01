package it.einjojo.economy.db;

import it.einjojo.economy.base.AbstractIntegrationTest;
import it.einjojo.economy.exception.RepositoryException;
import it.einjojo.economy.util.UUIDUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

public class PostgresEconomyRepositoryIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PostgresEconomyRepositoryIntegrationTest.class);
    private PostgresEconomyRepository repository;

    @BeforeEach
    void setUpRepository() {
        repository = new PostgresEconomyRepository(testConnectionProvider);
        // Ensure schema exists (it also clears/resets defaults if implemented well)
        // Or, explicitly clear the table:
        clearPlayerBalancesTable();
        // The ensureSchemaExists needs to be idempotent and safe to call multiple times.
        assertThatCode(() -> repository.ensureSchemaExists())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ensureSchemaExists creates table and trigger")
    void ensureSchemaExists_createsTableAndTrigger() {
        // Verification logic (check table metadata, check trigger existence)
        try (Connection conn = testConnectionProvider.getConnection()) {
            // Check table
            DatabaseMetaData metaData = conn.getMetaData();
            try (ResultSet tables = metaData.getTables(null, null, "player_balances", new String[]{"TABLE"})) {
                assertThat(tables.next()).as("player_balances table should exist").isTrue();
            }

            // Check trigger (PostgreSQL specific query)
            String checkTriggerSql = "SELECT 1 FROM pg_trigger WHERE tgname = 'trigger_player_balances_updated_at';";
            try (PreparedStatement ps = conn.prepareStatement(checkTriggerSql); ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("Trigger 'trigger_player_balances_updated_at' should exist").isTrue();
            }
        } catch (SQLException e) {
            fail("Database metadata check failed", e);
        }
    }

    @Test
    @DisplayName("findAccountData returns empty for non-existent UUID")
    void findAccountData_nonExistentUuid_returnsEmpty() {
        Optional<AccountData> result = repository.findAccountData(UUID.randomUUID());
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("upsertAndIncrementBalance creates new account correctly")
    void upsertAndIncrementBalance_newAccount_createsCorrectly() {
        UUID playerUuid = UUID.randomUUID();
        double amount = 100.50;

        double newBalance = repository.upsertAndIncrementBalance(playerUuid, amount);
        assertThat(newBalance).isEqualTo(amount);

        Optional<AccountData> accountData = repository.findAccountData(playerUuid);
        assertThat(accountData).isPresent().hasValueSatisfying(data -> {
            assertThat(data.uuid()).isEqualTo(playerUuid);
            assertThat(data.balance()).isEqualTo(amount);
            assertThat(data.version()).isEqualTo(0L); // Initial version
        });
    }

    @Test
    @DisplayName("upsertAndIncrementBalance updates existing account and version")
    void upsertAndIncrementBalance_existingAccount_updatesCorrectly() {
        UUID playerUuid = UUID.randomUUID();
        repository.upsertAndIncrementBalance(playerUuid, 50.0); // version 0

        double increment = 25.25;
        double newBalance = repository.upsertAndIncrementBalance(playerUuid, increment);
        assertThat(newBalance).isEqualTo(50.0 + 25.25);

        Optional<AccountData> accountData = repository.findAccountData(playerUuid);
        assertThat(accountData).isPresent().hasValueSatisfying(data -> {
            assertThat(data.balance()).isEqualTo(75.25);
            assertThat(data.version()).isEqualTo(1L); // Version incremented
        });
    }

    @Test
    @DisplayName("upsertAndIncrementBalance throws for non-positive amount")
    void upsertAndIncrementBalance_invalidAmount_throwsException() {
        UUID playerUuid = UUID.randomUUID();
        assertThatThrownBy(() -> repository.upsertAndIncrementBalance(playerUuid, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.upsertAndIncrementBalance(playerUuid, -10.0))
                .isInstanceOf(IllegalArgumentException.class);
    }


    @Test
    @DisplayName("updateBalanceConditional succeeds with matching version")
    void updateBalanceConditional_matchingVersion_succeeds() {
        UUID playerUuid = UUID.randomUUID();
        repository.upsertAndIncrementBalance(playerUuid, 100.0); // version 0
        AccountData initialData = repository.findAccountData(playerUuid).orElseThrow();

        boolean success = repository.updateBalanceConditional(playerUuid, 50.0, initialData.version());
        assertThat(success).isTrue();

        Optional<AccountData> updatedData = repository.findAccountData(playerUuid);
        assertThat(updatedData).isPresent().hasValueSatisfying(data -> {
            assertThat(data.balance()).isEqualTo(50.0);
            assertThat(data.version()).isEqualTo(initialData.version() + 1);
        });
    }

    @Test
    @DisplayName("updateBalanceConditional fails with version mismatch")
    void updateBalanceConditional_versionMismatch_fails() {
        UUID playerUuid = UUID.randomUUID();
        repository.upsertAndIncrementBalance(playerUuid, 100.0); // version 0
        AccountData initialData = repository.findAccountData(playerUuid).orElseThrow();

        // Simulate concurrent update
        repository.upsertAndIncrementBalance(playerUuid, 10.0); // version becomes 1

        boolean success = repository.updateBalanceConditional(playerUuid, 50.0, initialData.version()); // Try with old version 0
        assertThat(success).isFalse();

        Optional<AccountData> currentData = repository.findAccountData(playerUuid);
        assertThat(currentData).isPresent().hasValueSatisfying(data -> {
            assertThat(data.balance()).isEqualTo(110.0); // Unchanged by the failed conditional update
            assertThat(data.version()).isEqualTo(1L);
        });
    }

    @Test
    @DisplayName("updateBalanceConditional throws for negative new balance")
    void updateBalanceConditional_negativeBalance_throwsException() {
        UUID playerUuid = UUID.randomUUID();
        repository.upsertAndIncrementBalance(playerUuid, 100.0);
        assertThatThrownBy(() -> repository.updateBalanceConditional(playerUuid, -1.0, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("upsertAndSetBalance creates account with specified balance")
    void upsertAndSetBalance_newAccount_createsCorrectly() {
        UUID playerUuid = UUID.randomUUID();
        double amount = 77.7;
        double newBalance = repository.upsertAndSetBalance(playerUuid, amount);
        assertThat(newBalance).isEqualTo(amount);

        Optional<AccountData> accountData = repository.findAccountData(playerUuid);
        assertThat(accountData).isPresent().hasValueSatisfying(data -> {
            assertThat(data.balance()).isEqualTo(amount);
            assertThat(data.version()).isEqualTo(0L);
        });
    }

    @Test
    @DisplayName("upsertAndSetBalance updates existing account and increments version")
    void upsertAndSetBalance_existingAccount_updatesCorrectly() {
        UUID playerUuid = UUID.randomUUID();
        repository.upsertAndIncrementBalance(playerUuid, 50.0); // version 0

        double newAmount = 88.8;
        double returnedBalance = repository.upsertAndSetBalance(playerUuid, newAmount);
        assertThat(returnedBalance).isEqualTo(newAmount);

        Optional<AccountData> accountData = repository.findAccountData(playerUuid);
        assertThat(accountData).isPresent().hasValueSatisfying(data -> {
            assertThat(data.balance()).isEqualTo(newAmount);
            assertThat(data.version()).isEqualTo(1L);
        });
    }

    @Test
    @DisplayName("upsertAndSetBalance throws for negative amount")
    void upsertAndSetBalance_invalidAmount_throwsException() {
         UUID playerUuid = UUID.randomUUID();
        assertThatThrownBy(() -> repository.upsertAndSetBalance(playerUuid, -0.01))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Trigger updates 'updated_at' timestamp on balance change")
    void trigger_updatesTimestamp() throws InterruptedException, SQLException {
        UUID playerUuid = UUID.randomUUID();
        repository.upsertAndIncrementBalance(playerUuid, 10.0); // Initial insert

        Instant firstUpdatedAt = getDbTimestamp(playerUuid, "updated_at");
        Instant firstCreatedAt = getDbTimestamp(playerUuid, "created_at");
        assertThat(firstUpdatedAt).isEqualTo(firstCreatedAt);

        Thread.sleep(50); // Ensure time advances enough for timestamp difference

        repository.upsertAndIncrementBalance(playerUuid, 5.0); // This update should trigger

        Instant secondUpdatedAt = getDbTimestamp(playerUuid, "updated_at");
        Instant secondCreatedAt = getDbTimestamp(playerUuid, "created_at"); // Should remain the same

        assertThat(secondUpdatedAt).isAfter(firstUpdatedAt);
        assertThat(secondCreatedAt).isEqualTo(firstCreatedAt); // Created_at should not change on update
    }

    private Instant getDbTimestamp(UUID uuid, String columnName) throws SQLException {
        String sql = String.format("SELECT %s FROM player_balances WHERE uuid = ?", columnName);
        try (Connection conn = testConnectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBytes(1, UUIDUtil.uuidToBytes(uuid));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getTimestamp(1).toInstant();
                }
            }
        }
        fail("Could not retrieve timestamp for UUID " + uuid);
        return null; // Should not reach here
    }
}