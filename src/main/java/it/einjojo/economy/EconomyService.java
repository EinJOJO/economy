package it.einjojo.economy;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Defines the asynchronous API for interacting with the player economy system.
 * All methods involving I/O (database, network) return CompletableFuture
 * to avoid blocking the calling thread (e.g., Minecraft main thread).
 */
public interface EconomyService {

    /**
     * Asynchronously initializes the economy service, ensuring database schema is ready.
     * Should be called once before using other methods.
     *
     * @return A CompletableFuture that completes when initialization is finished,
     *         or completes exceptionally if initialization fails.
     */
    CompletableFuture<Void> initialize();

    /**
     * Asynchronously retrieves the current balance for a given player.
     * If the player does not have an account, 0.0 is returned.
     *
     * @param playerUuid The UUID of the player.
     * @return A CompletableFuture holding the player's balance.
     */
    CompletableFuture<Double> getBalance(UUID playerUuid);

    /**
     * Asynchronously checks if a player has an account in the economy system.
     *
     * @param playerUuid The UUID of the player.
     * @return A CompletableFuture holding true if the account exists, false otherwise.
     */
    CompletableFuture<Boolean> hasAccount(UUID playerUuid);


    /**
     * Asynchronously deposits a specified amount into a player's account.
     * Creates the account if it doesn't exist.
     * Amount must be positive.
     *
     * @param playerUuid The UUID of the player.
     * @param amount     The amount to deposit (must be > 0).
     * @return A CompletableFuture holding the {@link TransactionResult} of the operation.
     *         On success, the result contains the new balance.
     */
    CompletableFuture<TransactionResult> deposit(UUID playerUuid, double amount);

    /**
     * Asynchronously withdraws a specified amount from a player's account.
     * The operation will fail if the account does not exist, if the amount is not positive,
     * or if the player has insufficient funds. Uses optimistic locking to handle
     * concurrent modifications, potentially retrying internally.
     *
     * @param playerUuid The UUID of the player.
     * @param amount     The amount to withdraw (must be > 0).
     * @return A CompletableFuture holding the {@link TransactionResult} of the operation.
     *         On success, the result contains the new balance. FAILED_CONCURRENCY indicates
     *         optimistic locking failure after retries.
     */
    CompletableFuture<TransactionResult> withdraw(UUID playerUuid, double amount);


    /**
     * Asynchronously sets a player's balance to a specific amount.
     * Creates the account if it doesn't exist.
     * Amount must be non-negative.
     *
     * @param playerUuid The UUID of the player.
     * @param amount     The absolute balance to set (must be >= 0).
     * @return A CompletableFuture holding the {@link TransactionResult} of the operation.
     *         On success, the result contains the new balance (equal to the amount set).
     */
    CompletableFuture<TransactionResult> setBalance(UUID playerUuid, double amount);


}