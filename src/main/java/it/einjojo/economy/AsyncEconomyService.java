package it.einjojo.economy;

import it.einjojo.economy.db.AccountData;
import it.einjojo.economy.db.EconomyRepository;
import it.einjojo.economy.exception.EconomyException;
import it.einjojo.economy.redis.RedisNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Asynchronous implementation of the {@link EconomyService}.
 * Orchestrates calls to the {@link EconomyRepository} and {@link RedisNotifier},
 * ensuring operations are non-blocking for the caller. Handles optimistic locking retries.
 */
public class AsyncEconomyService implements EconomyService {

    private static final Logger log = LoggerFactory.getLogger(AsyncEconomyService.class);

    private final EconomyRepository repository;
    private final RedisNotifier notifier;
    private final ExecutorService dbExecutor; // Executor for blocking DB operations
    private final ExecutorService notificationExecutor; // Optional: Separate executor for notifications

    // Configuration for withdrawal retries
    private final int maxRetries;
    private final long retryDelayMillis;


    /**
     * Constructs an AsyncEconomyService.
     *
     * @param repository           The data access repository.
     * @param notifier             The Redis notification publisher.
     * @param dbExecutor           An ExecutorService dedicated to running blocking database operations.
     * @param notificationExecutor An ExecutorService for running notification tasks (can be same as dbExecutor).
     * @param maxRetries           Maximum number of retries for withdrawals on concurrency conflicts.
     * @param retryDelayMillis     Delay between retry attempts in milliseconds.
     */
    public AsyncEconomyService(EconomyRepository repository,
                               RedisNotifier notifier,
                               ExecutorService dbExecutor,
                               ExecutorService notificationExecutor,
                               int maxRetries,
                               long retryDelayMillis) {
        this.repository = Objects.requireNonNull(repository, "repository cannot be null");
        this.notifier = Objects.requireNonNull(notifier, "notifier cannot be null");
        this.dbExecutor = Objects.requireNonNull(dbExecutor, "dbExecutor cannot be null");
        this.notificationExecutor = Objects.requireNonNull(notificationExecutor, "notificationExecutor cannot be null");
        this.maxRetries = Math.max(0, maxRetries); // Ensure non-negative
        this.retryDelayMillis = Math.max(0, retryDelayMillis); // Ensure non-negative
        log.info("AsyncEconomyService initialized. Max Retries: {}, Retry Delay: {}ms", this.maxRetries, this.retryDelayMillis);

    }

    /**
     * Convenience constructor using a single executor for both DB and notifications.
     * Uses default retry settings (e.g., 3 retries, 50ms delay).
     *
     * @param repository The data access repository.
     * @param notifier   The Redis notification publisher.
     * @param executor   An ExecutorService for all background tasks.
     */
    public AsyncEconomyService(EconomyRepository repository, RedisNotifier notifier, ExecutorService executor) {
        this(repository, notifier, executor, executor, 3, 50); // Default retries/delay
    }


    // Helper to run potentially blocking suppliers asynchronously
    private <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier, Executor executor) {
        return CompletableFuture.supplyAsync(supplier, executor)
                .exceptionally(ex -> {
                    // Log and wrap repository/runtime exceptions
                    log.error("Exception during async execution: {}", ex.getMessage(), ex);
                    throw new EconomyException("Async operation failed", ex); // Wrap in a base EconomyException
                });
    }

    // Helper to run potentially blocking Runnable asynchronously
    private CompletableFuture<Void> runAsync(Runnable runnable, Executor executor) {
        return CompletableFuture.runAsync(runnable, executor)
                .exceptionally(ex -> {
                    log.error("Exception during async execution: {}", ex.getMessage(), ex);
                    throw new EconomyException("Async operation failed", ex); // Wrap in a base EconomyException
                });
    }


    /**
     * Initializes the service by creating the necessary tables and schema if necessary.
     *
     * @return A CompletableFuture that completes when the service is ready to accept requests.
     */
    public CompletableFuture<Void> initialize() {
        log.info("Initializing Economy Service asynchronously...");
        // Run ensureSchemaExists on the DB executor
        return runAsync(repository::ensureSchemaExists, dbExecutor)
                .whenComplete((res, ex) -> {
                    if (ex != null) {
                        log.error("Economy Service initialization failed!", ex);
                    } else {
                        log.info("Economy Service initialization successful.");
                    }
                });
    }

    @Override
    public CompletableFuture<Double> getBalance(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
        log.debug("Requesting balance for UUID: {}", playerUuid);
        return supplyAsync(() -> repository.findAccountData(playerUuid), dbExecutor)
                .thenApply(optionalData -> optionalData.map(AccountData::balance).orElse(0.0))
                .whenComplete((balance, ex) -> {
                    if (ex == null) log.debug("Balance retrieved for {}: {}", playerUuid, balance);
                });
    }

    @Override
    public CompletableFuture<Boolean> hasAccount(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
        log.debug("Checking account existence for UUID: {}", playerUuid);
        return supplyAsync(() -> repository.findAccountData(playerUuid), dbExecutor)
                .thenApply(Optional::isPresent)
                .whenComplete((exists, ex) -> {
                    if (ex == null) log.debug("Account existence check for {}: {}", playerUuid, exists);
                });
    }


    @Override
    public CompletableFuture<TransactionResult> deposit(UUID playerUuid, double amount) {
        Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
        log.debug("Requesting deposit for UUID: {} amount: {}", playerUuid, amount);

        if (amount <= 0) {
            log.warn("Deposit rejected for {}: Invalid amount {}", playerUuid, amount);
            return CompletableFuture.completedFuture(TransactionResult.invalidAmount());
        }

        return supplyAsync(() -> repository.upsertAndIncrementBalance(playerUuid, amount), dbExecutor)
                .thenCompose(newBalance -> {
                    log.debug("Deposit success for {}. Scheduling notification...", playerUuid);
                    runAsync(() -> {
                        log.debug("Notification task started for deposit UUID: {}", playerUuid);
                        notifier.publishUpdate(playerUuid, newBalance, amount);
                        log.debug("Notification task finished for deposit UUID: {}", playerUuid);
                    }, notificationExecutor);
                    log.info("Deposit successful for {}. Amount: {}. New Balance: {}", playerUuid, amount, newBalance);
                    return CompletableFuture.completedFuture(TransactionResult.success(newBalance, amount));
                })
// Add similar logging inside the .thenCompose blocks for setBalance and withdraw where runAsync(notifier...) is called.
                .exceptionally(ex -> {
                    // Handle exceptions from the repository call
                    log.error("Deposit failed for UUID: {}", playerUuid, ex);
                    return TransactionResult.error(); // General error
                });
    }


    @Override
    public CompletableFuture<TransactionResult> setBalance(UUID playerUuid, double amount) {
        Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
        log.debug("Requesting setBalance for UUID: {} amount: {}", playerUuid, amount);

        if (amount < 0) {
            log.warn("SetBalance rejected for {}: Invalid amount {}", playerUuid, amount);
            return CompletableFuture.completedFuture(TransactionResult.invalidAmount());
        }

        // We need the old balance to calculate the 'change' for notification
        // So, we first find the account, then set, then notify.
        return supplyAsync(() -> repository.findAccountData(playerUuid), dbExecutor)
                .thenCompose(optionalOldData -> {
                    double oldBalance = optionalOldData.map(AccountData::balance).orElse(0.0);
                    double change = amount - oldBalance; // Calculate change

                    return supplyAsync(() -> repository.upsertAndSetBalance(playerUuid, amount), dbExecutor)
                            .thenCompose(newBalance -> {
                                // Successfully set, now notify
                                runAsync(() -> notifier.publishUpdate(playerUuid, newBalance, change), notificationExecutor);
                                log.info("SetBalance successful for {}. New Balance: {}. Change: {}", playerUuid, newBalance, change);
                                return CompletableFuture.completedFuture(TransactionResult.success(newBalance, change));
                            });
                })
                .exceptionally(ex -> {
                    log.error("SetBalance failed for UUID: {}", playerUuid, ex);
                    return TransactionResult.error();
                });
    }


    @Override
    public CompletableFuture<TransactionResult> withdraw(UUID playerUuid, double amount) {
        Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
        log.debug("Requesting withdrawal for UUID: {} amount: {}", playerUuid, amount);

        if (amount <= 0) {
            log.warn("Withdrawal rejected for {}: Invalid amount {}", playerUuid, amount);
            return CompletableFuture.completedFuture(TransactionResult.invalidAmount());
        }

        // Start the withdrawal attempt process with retries
        return attemptWithdraw(playerUuid, amount, maxRetries);
    }

    /**
     * Internal recursive helper for withdrawal attempts with optimistic locking retries.
     */
    private CompletableFuture<TransactionResult> attemptWithdraw(UUID playerUuid, double amount, int retriesLeft) {
        log.debug("Attempting withdrawal for {} ({} retries left)", playerUuid, retriesLeft);

        // 1. Read the current state (balance and version)
        return supplyAsync(() -> repository.findAccountData(playerUuid), dbExecutor)
                .thenComposeAsync(optionalData -> {
                    if (optionalData.isEmpty()) {
                        log.warn("Withdrawal failed for {}: Account not found.", playerUuid);
                        return CompletableFuture.completedFuture(TransactionResult.accountNotFound());
                    }

                    AccountData currentData = optionalData.get();

                    // 2. Check business logic (sufficient funds)
                    if (currentData.balance() < amount) {
                        log.warn("Withdrawal failed for {}: Insufficient funds ({} < {}).", playerUuid, currentData.balance(), amount);
                        return CompletableFuture.completedFuture(TransactionResult.insufficientFunds());
                    }

                    // 3. Calculate new balance
                    double newBalance = currentData.balance() - amount;

                    // 4. Attempt conditional update
                    return supplyAsync(() -> repository.updateBalanceConditional(playerUuid, newBalance, currentData.version()), dbExecutor)
                            .thenComposeAsync(updateSuccess -> {
                                if (updateSuccess) {
                                    // 5a. Success: Notify and return result
                                    runAsync(() -> notifier.publishUpdate(playerUuid, newBalance, -amount), notificationExecutor);
                                    log.info("Withdrawal successful for {}. Amount: {}. New Balance: {}", playerUuid, amount, newBalance);
                                    return CompletableFuture.completedFuture(TransactionResult.success(newBalance, -amount));
                                } else {
                                    // 5b. Failure (Concurrency Conflict or record gone)
                                    log.warn("Conditional update failed for {} (version conflict or deleted). Retries left: {}", playerUuid, retriesLeft);
                                    if (retriesLeft > 0) {
                                        // Retry after delay
                                        CompletableFuture<TransactionResult> retryFuture = new CompletableFuture<>();
                                        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(); // Temporary scheduler
                                        scheduler.schedule(() -> {
                                            attemptWithdraw(playerUuid, amount, retriesLeft - 1)
                                                    .whenComplete((res, ex) -> {
                                                        if (ex != null) retryFuture.completeExceptionally(ex);
                                                        else retryFuture.complete(res);
                                                        scheduler.shutdown(); // Clean up scheduler
                                                    });
                                        }, retryDelayMillis, TimeUnit.MILLISECONDS);
                                        return retryFuture;
                                    } else {
                                        // Max retries exceeded
                                        log.error("Withdrawal failed for {}: Max retries ({}) exceeded due to concurrency.", playerUuid, maxRetries);
                                        return CompletableFuture.completedFuture(TransactionResult.concurrentModification());
                                    }
                                }
                            }, dbExecutor); // Run continuation on dbExecutor too
                }, dbExecutor) // Run check+update logic on dbExecutor
                .exceptionally(ex -> {
                    // Handle exceptions from findAccountData or updateBalanceConditional
                    log.error("Withdrawal attempt failed unexpectedly for UUID: {}", playerUuid, ex);
                    return TransactionResult.error();
                });
    }

    /**
     * Cleans up resources, like shutting down executors if they were created internally
     * or closing the Redis notifier. This should be called by the application using the library
     * during shutdown. The responsibility of managing the lifecycle of externally provided
     * executors lies with the provider.
     */
    public void shutdown() {
        log.info("AsyncEconomyService shutdown actions complete (external resources like executors/pools are NOT closed by this method).");
    }

    // Helper for shutting down executors (if they were managed internally)
    private void shutdownExecutor(ExecutorService executor) {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}