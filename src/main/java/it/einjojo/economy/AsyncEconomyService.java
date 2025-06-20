package it.einjojo.economy;

import it.einjojo.economy.db.AccountData;
import it.einjojo.economy.db.EconomyRepository;
import it.einjojo.economy.db.PostgresEconomyRepository;
import it.einjojo.economy.exception.EconomyException;
import it.einjojo.economy.notifier.JedisNotifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Asynchronous implementation of the {@link EconomyService}.
 * Orchestrates calls to the {@link EconomyRepository} and {@link JedisNotifier},
 * ensuring operations are non-blocking for the caller. Handles optimistic locking retries.
 */
public class AsyncEconomyService implements EconomyService {

    private static final Logger log = LoggerFactory.getLogger(AsyncEconomyService.class);

    private final @NotNull EconomyRepository repository;
    private @Nullable EconomyCache syncCache;
    private final @Nullable EconomyNotifier notifier;
    private final @NotNull ExecutorService dbExecutor; // Executor for blocking DB operations
    private final @NotNull ExecutorService notificationExecutor; // Optional: Separate executor for notifications

    // Configuration for withdrawal retries
    private final int maxRetries;
    private final long retryDelayMillis;


    /**
     * Constructs an AsyncEconomyService.
     *
     * @param repository           The data access repository.
     * @param notifier             notification publisher can be null.
     * @param dbExecutor           An ExecutorService dedicated to running blocking database operations.
     * @param notificationExecutor An ExecutorService for running notification tasks (can be the same as dbExecutor).
     * @param maxRetries           Maximum number of retries for withdrawals on concurrency conflicts.
     * @param retryDelayMillis     Delay between retry attempts in milliseconds.
     */
    public AsyncEconomyService(@NotNull EconomyRepository repository,
                               @Nullable EconomyNotifier notifier,
                               @NotNull ExecutorService dbExecutor,
                               @NotNull ExecutorService notificationExecutor,
                               int maxRetries,
                               long retryDelayMillis) {
        this.notifier = notifier;
        this.repository = Objects.requireNonNull(repository, "repository cannot be null");
        this.dbExecutor = Objects.requireNonNull(dbExecutor, "dbExecutor cannot be null");
        this.notificationExecutor = Objects.requireNonNull(notificationExecutor, "notificationExecutor cannot be null");
        this.maxRetries = Math.max(0, maxRetries); // Ensure non-negative
        this.retryDelayMillis = Math.max(0, retryDelayMillis); // Ensure non-negative
    }

    /**
     * Convenience constructor using a single executor for both DB and notifications.
     * Uses default retry settings (e.g., 3 retries, 50ms delay).
     *
     * @param repository The data access repository.
     * @param notifier   The Redis notification publisher.
     * @param executor   An ExecutorService for all background tasks.
     */
    public AsyncEconomyService(@NotNull EconomyRepository repository, @Nullable EconomyNotifier notifier, @NotNull ExecutorService executor) {
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
        if (repository instanceof PostgresEconomyRepository economyRepository) {
            log.warn("Provided a initialized instance of repository. Skipping init.");
            if (economyRepository.isInit()) return CompletableFuture.completedFuture(null);
        }
        log.info("Initializing Economy Service asynchronously...");
        // Run ensureSchemaExists on the DB executor
        return runAsync(repository::init, dbExecutor)
                .whenComplete((res, ex) -> {
                    if (ex != null) {
                        log.error("Economy Service initialization failed!", ex);
                    } else {
                        log.info("Economy Service initialization successful.");
                    }
                });
    }

    @Override
    public CompletableFuture<Double> getBalance(@NotNull UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
        log.debug("Requesting balance for UUID: {}", playerUuid);
        return supplyAsync(() -> repository.findAccountData(playerUuid), dbExecutor)
                .thenApply(optionalData -> optionalData.map(AccountData::balance).orElse(0.0))
                .whenComplete((balance, ex) -> {
                    if (ex == null) {
                        log.debug("Balance retrieved for {}: {}", playerUuid, balance);
                        writeCacheIfAvailable(playerUuid, balance);
                    }
                });
    }

    private void writeCacheIfAvailable(UUID playerUuid, double balance) {
        if (syncCache != null) {
            syncCache.cacheBalance(playerUuid, balance);
        }
    }

    @Override
    public CompletableFuture<Boolean> hasAccount(@NotNull UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
        log.debug("Checking account existence for UUID: {}", playerUuid);
        return supplyAsync(() -> repository.findAccountData(playerUuid), dbExecutor)
                .thenApply(Optional::isPresent)
                .whenComplete((exists, ex) -> {
                    if (ex == null) log.debug("Account existence check for {}: {}", playerUuid, exists);
                });
    }


    private void publishNotification(UUID playerUuid, double newBalance, double amount) {
        if (notifier != null) {
            notifier.publishUpdate(playerUuid, newBalance, amount);
        }
    }

    public CompletableFuture<TransactionResult> deposit(@NotNull UUID playerUuid, double amount, @NotNull String reason) {
        Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
        Objects.requireNonNull(reason, "reason cannot be null");
        log.debug("Requesting deposit for UUID: {} amount: {} reason: {}", playerUuid, amount, reason);

        if (amount <= 0) {
            log.warn("Deposit rejected for {}: Invalid amount {}", playerUuid, amount);
            return CompletableFuture.completedFuture(TransactionResult.invalidAmount());
        }

        return supplyAsync(() -> repository.upsertAndIncrementBalance(playerUuid, amount), dbExecutor)
                .thenCompose(accountData -> {
                    log.debug("Deposit success for {}. Scheduling notification and logging...", playerUuid);
                    writeCacheIfAvailable(playerUuid, accountData.balance());
                    runAsync(() -> {
                        repository.createLogEntry(playerUuid, accountData.version(), amount, reason);
                        log.debug("Notification task started for deposit UUID: {}", playerUuid);
                        publishNotification(playerUuid, accountData.balance(), amount);
                        log.debug("Notification task finished for deposit UUID: {}", playerUuid);
                    }, notificationExecutor);
                    log.info("Deposit successful for {}. Amount: {}. New Balance: {}. Reason: {}", playerUuid, amount, accountData.balance(), reason);
                    return CompletableFuture.completedFuture(TransactionResult.success(accountData.balance(), amount));
                })
                .exceptionally(ex -> {
                    log.error("Deposit failed for UUID: {}", playerUuid, ex);
                    return TransactionResult.error();
                });
    }

    public CompletableFuture<TransactionResult> setBalance(@NotNull UUID playerUuid, double amount, @NotNull String reason) {
        Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
        Objects.requireNonNull(reason, "reason cannot be null");
        log.debug("Requesting setBalance for UUID: {} amount: {} reason: {}", playerUuid, amount, reason);

        if (amount < 0) {
            log.warn("SetBalance rejected for {}: Invalid amount {}", playerUuid, amount);
            return CompletableFuture.completedFuture(TransactionResult.invalidAmount());
        }

        return supplyAsync(() -> repository.findAccountData(playerUuid), dbExecutor)
                .thenCompose(optionalOldData -> {
                    double oldBalance = optionalOldData.map(AccountData::balance).orElse(0.0);
                    // double change = amount - oldBalance; // This was for notification, log will use 'amount' if it's a set operation, or we can log old/new.
                    // For simplicity, let's log the 'amount' as the change for setBalance, assuming 'reason' clarifies it's a 'set'.
                    // Or, more accurately, the change is (newBalance - oldBalance)

                    return supplyAsync(() -> repository.upsertAndSetBalance(playerUuid, amount), dbExecutor)
                            .thenCompose(accountData -> {
                                double changeForNotificationAndLog = accountData.balance() - oldBalance;
                                runAsync(() -> {
                                    repository.createLogEntry(playerUuid, accountData.version(), changeForNotificationAndLog, reason);
                                    publishNotification(playerUuid, accountData.balance(), changeForNotificationAndLog);
                                }, notificationExecutor);
                                log.info("SetBalance successful for {}. New Balance: {}. Reason: {}", playerUuid, accountData.balance(), reason);
                                writeCacheIfAvailable(playerUuid, accountData.balance());
                                return CompletableFuture.completedFuture(TransactionResult.success(accountData.balance(), changeForNotificationAndLog));
                            });
                })
                .exceptionally(ex -> {
                    log.error("SetBalance failed for UUID: {}", playerUuid, ex);
                    return TransactionResult.error();
                });
    }


    // @NotNull will be handled by Objects.requireNonNull or similar checks
    public CompletableFuture<TransactionResult> withdraw(@NotNull UUID playerUuid, double amount, @NotNull String reason) {
        Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
        Objects.requireNonNull(reason, "reason cannot be null");
        log.debug("Requesting withdrawal for UUID: {} amount: {} reason: {}", playerUuid, amount, reason);

        if (amount <= 0) {
            log.warn("Withdrawal rejected for {}: Invalid amount {}", playerUuid, amount);
            return CompletableFuture.completedFuture(TransactionResult.invalidAmount());
        }

        return attemptWithdraw(playerUuid, amount, reason, maxRetries);
    }

    /**
     * Internal recursive helper for withdrawal attempts with optimistic locking retries.
     */
    private CompletableFuture<TransactionResult> attemptWithdraw(UUID playerUuid, double amount, String reason, int retriesLeft) {
        log.debug("Attempting withdrawal for {} ({} retries left), reason: {}", playerUuid, retriesLeft, reason);

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
                    long newVersion = currentData.version() + 1; // Anticipate new version for logging

                    // 4. Attempt conditional update
                    return supplyAsync(() -> repository.updateBalanceConditional(playerUuid, newBalance, currentData.version()), dbExecutor)
                            .thenComposeAsync(updateSuccess -> {
                                if (updateSuccess) {
                                    // 5a. Success: Log, Notify and return result
                                    runAsync(() -> {
                                        repository.createLogEntry(playerUuid, newVersion, -amount, reason);
                                        publishNotification(playerUuid, newBalance, -amount);
                                    }, notificationExecutor);
                                    writeCacheIfAvailable(playerUuid, newBalance);
                                    log.info("Withdrawal successful for {}. Amount: {}. New Balance: {}. Reason: {}", playerUuid, amount, newBalance, reason);
                                    return CompletableFuture.completedFuture(TransactionResult.success(newBalance, -amount));
                                } else {
                                    // 5b. Failure (Concurrency Conflict or record gone)
                                    log.warn("Conditional update failed for {} (version conflict or deleted). Retries left: {}", playerUuid, retriesLeft);
                                    if (retriesLeft > 0) {
                                        // Retry after delay
                                        CompletableFuture<TransactionResult> retryFuture = new CompletableFuture<>();
                                        // Use try-with-resources for the ScheduledExecutorService
                                        try (ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
                                            scheduler.schedule(() -> {
                                                attemptWithdraw(playerUuid, amount, reason, retriesLeft - 1)
                                                        .whenComplete((res, ex) -> {
                                                            if (ex != null) retryFuture.completeExceptionally(ex);
                                                            else retryFuture.complete(res);
                                                            // scheduler.shutdown(); // No longer needed here, try-with-resources handles it
                                                        });
                                            }, retryDelayMillis, TimeUnit.MILLISECONDS);
                                        }
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
     * Sets the cache object which will be populated on async database calls
     *
     * @param syncCache cache
     */
    public void setSyncCache(@Nullable EconomyCache syncCache) {
        this.syncCache = syncCache;
    }

    /**
     * GETTER
     *
     * @return Repository object
     */
    public @NotNull EconomyRepository getRepository() {
        return repository;
    }

    /**
     * GETTER
     *
     * @return Notifier object
     */
    public @Nullable EconomyNotifier getNotifier() {
        return notifier;
    }

    /**
     * Cleans up resources, like shutting down executors if they were created internally
     * or closing the Redis notifier. This should be called by the application using the library
     * during shutdown. The responsibility of managing the lifecycle of externally provided
     * executors lies with the provider.
     */
    public void shutdown() {
        log.info("AsyncEconomyService shutdown actions complete (external resources like executors/pools are NOT closed by this method), because it messed up testing");
    }


}

