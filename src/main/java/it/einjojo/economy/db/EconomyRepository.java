package it.einjojo.economy.db;

import it.einjojo.economy.exception.RepositoryException;

import java.util.Optional;
import java.util.UUID;

/**
 * Interface for data access operations related to a player economy.
 * Implementations are expected to handle database interactions, including
 * connection management and potentially optimistic locking.
 * Methods in this interface are blocking and should be called asynchronously.
 */
public interface EconomyRepository {

    /**
     * Ensures that the necessary database schema (e.g., tables) exists.
     * This method is typically called during application initialization.
     * This is a blocking operation.
     *
     * @throws RepositoryException if schema creation fails.
     */
    void ensureSchemaExists() throws RepositoryException;

    /**
     * Retrieves the account data (balance and version) for a given player.
     * This is a blocking operation.
     *
     * @param playerUuid The UUID of the player.
     * @return An Optional containing the {@link AccountData} if the account exists, otherwise empty.
     * @throws RepositoryException if a database error occurs.
     */
    Optional<AccountData> findAccountData(UUID playerUuid) throws RepositoryException;

    /**
     * Atomically increments the balance for a player. If the player account
     * does not exist, it will be created with the given amount as the initial balance.
     * This is a blocking operation.
     *
     * @param playerUuid The UUID of the player.
     * @param amount     The positive amount to add to the balance.
     * @return The new balance after the increment.
     * @throws RepositoryException      if a database error occurs.
     * @throws IllegalArgumentException if the amount is not positive.
     */
    double upsertAndIncrementBalance(UUID playerUuid, double amount) throws RepositoryException;


    /**
     * Attempts to update the balance for a player, but only if the provided
     * `expectedVersion` matches the current version in the database (Optimistic Lock).
     * This method DOES NOT check for sufficient funds itself, only performs the conditional update.
     * This is a blocking operation.
     *
     * @param playerUuid      The UUID of the player.
     * @param newBalance      The target balance to set.
     * @param expectedVersion The version read prior to attempting the update.
     * @return true if the update was successful (a version matched and row was updated), false otherwise (version mismatch).
     * @throws RepositoryException if a database error occurs.
     */
    boolean updateBalanceConditional(UUID playerUuid, double newBalance, long expectedVersion) throws RepositoryException;

    /**
     * Atomically sets the balance for a player. If the player account
     * does not exist, it will be created with the given balance.
     * This is a blocking operation.
     *
     * @param playerUuid The UUID of the player.
     * @param amount     The absolute balance to set.
     * @return The new balance after the operation (which is equal to the amount).
     * @throws RepositoryException      if a database error occurs.
     * @throws IllegalArgumentException if the amount is negative.
     */
    double upsertAndSetBalance(UUID playerUuid, double amount) throws RepositoryException;


}