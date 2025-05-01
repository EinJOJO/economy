package it.einjojo.economy;

import it.einjojo.economy.base.AbstractIntegrationTest;
import it.einjojo.economy.db.AccountData;
import it.einjojo.economy.db.PostgresEconomyRepository;
import it.einjojo.economy.redis.RedisNotifier;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;


public class AsyncEconomyServiceIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AsyncEconomyServiceIntegrationTest.class);
    private static final String TEST_REDIS_CHANNEL = "test:service:economy:updates";

    private AsyncEconomyService economyService;
    private PostgresEconomyRepository repository;
    private RedisNotifier notifier;
    private ExecutorService serviceExecutor; // For AsyncEconomyService
    private TestRedisSubscriber testSubscriber;
    private Thread subscriberThread;


    // Helper to await CompletableFuture results in tests
    private <T> T awaitResult(CompletableFuture<T> future) {
        try {
            return future.get(5, SECONDS); // 5-second timeout for test operations
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Test interrupted while waiting for future", e);
        } catch (ExecutionException e) {
            fail("Future completed exceptionally", e.getCause());
        } catch (TimeoutException e) {
            fail("Future timed out", e);
        }
        return null; // Should not be reached
    }


    private static class TestRedisSubscriber extends JedisPubSub {
        private final ConcurrentLinkedQueue<String> messages = new ConcurrentLinkedQueue<>();
        private final CountDownLatch connectionLatch = new CountDownLatch(1);
        // Keep the flag internally if you need it, but don't override the method
        private final AtomicBoolean subscriptionActive = new AtomicBoolean(false);


        @Override
        public void onSubscribe(String channel, int subscribedChannels) {
            log.info("Test subscriber: Subscribed to channel '{}'. Total subscribed: {}", channel, subscribedChannels);
            subscriptionActive.set(true); // Set our internal flag
            connectionLatch.countDown();
        }

        @Override
        public void onUnsubscribe(String channel, int subscribedChannels) {
            log.info("Test subscriber: Unsubscribed from channel '{}'. Total subscribed: {}", channel, subscribedChannels);
            subscriptionActive.set(false); // Reset internal flag if needed
            // Note: The superclass isSubscribed() will also become false AFTER this.
        }


        @Override
        public void onMessage(String channel, String message) {
            log.info("Test subscriber: Received message on [{}]: {}", channel, message);
            messages.offer(message);
        }

        public boolean awaitSubscription(long timeout, TimeUnit unit) throws InterruptedException {
            return connectionLatch.await(timeout, unit);
        }

        public String popMessage(long timeout, TimeUnit unit) throws InterruptedException {
            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < unit.toMillis(timeout)) {
                String msg = messages.poll();
                if (msg != null) return msg;
                Thread.sleep(50); // Poll interval
            }
            return null; // Timeout
        }

        public void clearMessages() {
            messages.clear();
        }

    }


    @BeforeEach
    void setUpServiceAndComponents() throws InterruptedException {
        clearPlayerBalancesTable(); // From AbstractIntegrationTest

        repository = new PostgresEconomyRepository(testConnectionProvider);
        // For tests, a cached thread pool is often fine, or a small fixed pool.
        // Ensure it's different from the main test thread.
        serviceExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r);
            t.setName("ServiceExecutor-" + t.threadId());
            t.setDaemon(true); // Allow JVM to exit if only daemon threads are running
            return t;
        });

        notifier = new RedisNotifier(testJedisPool, TEST_REDIS_CHANNEL);

        // Max 2 retries, 20ms delay for faster concurrency tests
        economyService = new AsyncEconomyService(repository, notifier, serviceExecutor, serviceExecutor, 2, 20L);

        // Initialize service (e.g., create schema)
        awaitResult(economyService.initialize());

        // Set up Redis subscriber for notification tests
        testSubscriber = new TestRedisSubscriber();
        subscriberThread = new Thread(() -> {
            try (Jedis jedis = new Jedis(redisContainer.getHost(), redisContainer.getMappedPort(6379))) {
                jedis.subscribe(testSubscriber, TEST_REDIS_CHANNEL);
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted() && !testSubscriber.isSubscribed()) {
                    log.error("Test Redis subscriber thread error", e);
                }
            }
        }, "TestRedisSubscriberThread");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
        if (!testSubscriber.awaitSubscription(3, SECONDS)) {
            fail("Test Redis subscriber failed to connect and subscribe in time.");
        }
        testSubscriber.clearMessages(); // Clear any connection messages
    }

    @AfterEach
    void tearDownServiceAndComponents() {
        if (testSubscriber != null && testSubscriber.isSubscribed()) {
            try {
                testSubscriber.unsubscribe();
            } catch (Exception e) { /* ignore */ }
        }
        if (subscriberThread != null && subscriberThread.isAlive()) {
            subscriberThread.interrupt();
            try {
                subscriberThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (economyService != null) {
            economyService.shutdown(); // Shuts down notifier's pool
        }
        if (serviceExecutor != null) {
            serviceExecutor.shutdown();
            try {
                if (!serviceExecutor.awaitTermination(5, SECONDS)) {
                    serviceExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                serviceExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }


    @Test
    @DisplayName("getBalance returns 0.0 for a new player")
    void getBalance_newPlayer_returnsZero() {
        UUID playerUuid = UUID.randomUUID();
        Double balance = awaitResult(economyService.getBalance(playerUuid));
        assertThat(balance).isEqualTo(0.0);
    }

    @Test
    @DisplayName("hasAccount returns false for new player, true after deposit")
    void hasAccount_lifecycle() {
        UUID playerUuid = UUID.randomUUID();
        assertThat(awaitResult(economyService.hasAccount(playerUuid))).isFalse();

        awaitResult(economyService.deposit(playerUuid, 10.0));
        assertThat(awaitResult(economyService.hasAccount(playerUuid))).isTrue();
    }

    @Test
    @DisplayName("deposit adds funds and publishes notification")
    void deposit_updatesBalanceAndNotifies() throws InterruptedException {
        UUID playerUuid = UUID.randomUUID();
        double amount = 123.45;

        TransactionResult result = awaitResult(economyService.deposit(playerUuid, amount));

        assertThat(result.status()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(result.newBalance()).hasValue(amount);
        assertThat(result.change()).isEqualTo(amount);
        assertThat(awaitResult(economyService.getBalance(playerUuid))).isEqualTo(amount);

        String notification = testSubscriber.popMessage(2, SECONDS);
        assertThat(notification).isNotNull();
        assertThat(notification)
                .contains("\"uuid\":\"" + playerUuid.toString() + "\"")
                .contains("\"newBalance\":" + amount)
                .contains("\"change\":" + amount);
    }

    @Test
    @DisplayName("deposit with invalid (zero or negative) amount fails")
    void deposit_invalidAmount_fails() {
        UUID playerUuid = UUID.randomUUID();
        TransactionResult resultZero = awaitResult(economyService.deposit(playerUuid, 0.0));
        assertThat(resultZero.status()).isEqualTo(TransactionStatus.INVALID_AMOUNT);

        TransactionResult resultNegative = awaitResult(economyService.deposit(playerUuid, -10.0));
        assertThat(resultNegative.status()).isEqualTo(TransactionStatus.INVALID_AMOUNT);

        assertThat(awaitResult(economyService.getBalance(playerUuid))).isEqualTo(0.0); // Balance unchanged
    }


    @Test
    @DisplayName("withdraw succeeds with sufficient funds and notifies")
    void withdraw_sufficientFunds_succeedsAndNotifies() throws InterruptedException {
        UUID playerUuid = UUID.randomUUID();
        double initialBalance = 100.0;
        double withdrawAmount = 30.0;
        awaitResult(economyService.deposit(playerUuid, initialBalance));
        testSubscriber.clearMessages(); // Clear deposit notification

        TransactionResult result = awaitResult(economyService.withdraw(playerUuid, withdrawAmount));

        assertThat(result.status()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(result.newBalance()).hasValue(initialBalance - withdrawAmount);
        assertThat(result.change()).isEqualTo(-withdrawAmount);
        assertThat(awaitResult(economyService.getBalance(playerUuid))).isEqualTo(initialBalance - withdrawAmount);

        String notification = testSubscriber.popMessage(2, SECONDS);
        assertThat(notification).isNotNull();
        assertThat(notification).contains("\"change\":" + (-withdrawAmount));
    }

    @Test
    @DisplayName("withdraw fails for insufficient funds")
    void withdraw_insufficientFunds_fails() throws InterruptedException {
        UUID playerUuid = UUID.randomUUID();
        awaitResult(economyService.deposit(playerUuid, 10.0));
        testSubscriber.clearMessages();

        TransactionResult result = awaitResult(economyService.withdraw(playerUuid, 20.0));
        assertThat(result.status()).isEqualTo(TransactionStatus.INSUFFICIENT_FUNDS);
        assertThat(awaitResult(economyService.getBalance(playerUuid))).isEqualTo(10.0);
        assertThat(testSubscriber.popMessage(200, TimeUnit.MILLISECONDS)).isNull(); // No notification on failure
    }

    @Test
    @DisplayName("withdraw fails for non-existent account")
    void withdraw_nonExistentAccount_fails() {
        UUID playerUuid = UUID.randomUUID();
        TransactionResult result = awaitResult(economyService.withdraw(playerUuid, 10.0));
        assertThat(result.status()).isEqualTo(TransactionStatus.ACCOUNT_NOT_FOUND);
    }


    @Test
    @DisplayName("setBalance updates existing balance and notifies")
    void setBalance_existingAccount_updatesAndNotifies() throws InterruptedException {
        UUID playerUuid = UUID.randomUUID();
        double initialBalance = 50.0;
        double newBalanceSet = 200.0;
        awaitResult(economyService.deposit(playerUuid, initialBalance));
        testSubscriber.clearMessages();

        TransactionResult result = awaitResult(economyService.setBalance(playerUuid, newBalanceSet));
        assertThat(result.status()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(result.newBalance()).hasValue(newBalanceSet);
        assertThat(result.change()).isEqualTo(newBalanceSet - initialBalance);
        assertThat(awaitResult(economyService.getBalance(playerUuid))).isEqualTo(newBalanceSet);

        String notification = testSubscriber.popMessage(2, SECONDS);
        assertThat(notification).isNotNull();
        assertThat(notification).contains("\"newBalance\":" + newBalanceSet);
    }

    @Test
    @DisplayName("setBalance creates new account and notifies")
    void setBalance_newAccount_createsAndNotifies() throws InterruptedException {
        UUID playerUuid = UUID.randomUUID();
        double newBalanceSet = 75.0;

        TransactionResult result = awaitResult(economyService.setBalance(playerUuid, newBalanceSet));
        assertThat(result.status()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(result.newBalance()).hasValue(newBalanceSet);
        assertThat(result.change()).isEqualTo(newBalanceSet); // Change is new balance as old was 0
        assertThat(awaitResult(economyService.getBalance(playerUuid))).isEqualTo(newBalanceSet);

        String notification = testSubscriber.popMessage(2, SECONDS);
        assertThat(notification).isNotNull();
        assertThat(notification).contains("\"newBalance\":" + newBalanceSet);
    }

    @Test
    @DisplayName("setBalance with negative amount fails")
    void setBalance_negativeAmount_fails() {
        UUID playerUuid = UUID.randomUUID();
        TransactionResult result = awaitResult(economyService.setBalance(playerUuid, -10.0));
        assertThat(result.status()).isEqualTo(TransactionStatus.INVALID_AMOUNT);
    }

    @Test
    @DisplayName("Optimistic locking: concurrent withdrawals lead to one success and one FAILED_CONCURRENCY")
    @Timeout(10)
        // Prevent test hanging
    void concurrentWithdrawals_optimisticLocking() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        double initialBalance = 200.0;
        awaitResult(economyService.deposit(playerUuid, initialBalance));
        testSubscriber.clearMessages();

        // Manually reduce retries for this specific service instance for faster test
        AsyncEconomyService lowRetryService = new AsyncEconomyService(
                repository, notifier, serviceExecutor, serviceExecutor, 0, 10L // 0 retries
        );
        lowRetryService.initialize().get();


        // Simulate two withdraw calls that might contend
        // This relies on thread scheduling, so it's not 100% guaranteed to hit the conflict,
        // but with enough load or direct DB manipulation it would.
        // A more reliable way to test the mechanism is via repository.updateBalanceConditional directly.

        // Get initial version
        long initialVersion = repository.findAccountData(playerUuid).map(AccountData::version).orElse(-1L);
        assertThat(initialVersion).isNotEqualTo(-1L);


        // First withdrawal attempt that we expect to succeed (or be one of the contenders)
        CompletableFuture<TransactionResult> withdraw1 = lowRetryService.withdraw(playerUuid, 50.0);

        // Manually update the version in the database to simulate a concurrent modification
        // that withdraw1's conditional update will race against or see as stale.
        // This is a more direct way to force a version mismatch for the *second* conceptual operation.
        // Let's make withdraw2 see the version changed by withdraw1.
        // More complex: make both read, then one writes, then second tries to write.

        // To make it more likely withdraw1's read happens before manual update:
        // We need to ensure withdraw1's findAccountData has run. This is tricky without internal hooks.

        // Let's try a different approach: two real withdrawal attempts.
        // One will update the version. The other, if it read an older version, should fail.
        // With 0 retries, one should fail with FAILED_CONCURRENCY if they genuinely conflict.

        CompletableFuture<TransactionResult> withdraw2 = lowRetryService.withdraw(playerUuid, 30.0);

        // Wait for both futures to complete
        CompletableFuture.allOf(withdraw1, withdraw2).join(); // join() waits but propagates exceptions wrapped

        TransactionResult result1 = withdraw1.get();
        TransactionResult result2 = withdraw2.get();

        log.info("Result 1: {}", result1);
        log.info("Result 2: {}", result2);

        // Assertions:
        // One transaction succeeded, one failed due to concurrency.
        // The order is not guaranteed.
        boolean r1Success = result1.status() == TransactionStatus.SUCCESS;
        boolean r2Success = result2.status() == TransactionStatus.SUCCESS;
        boolean r1ConcurrencyFail = result1.status() == TransactionStatus.FAILED_CONCURRENCY;
        boolean r2ConcurrencyFail = result2.status() == TransactionStatus.FAILED_CONCURRENCY;

        assertThat((r1Success && r2ConcurrencyFail) || (r1ConcurrencyFail && r2Success))
                .as("Expected one success and one FAILED_CONCURRENCY, but got result1=%s, result2=%s", result1.status(), result2.status())
                .isTrue();


        double finalBalance = awaitResult(economyService.getBalance(playerUuid));
        if (r1Success) {
            assertThat(finalBalance).isEqualTo(initialBalance - 50.0);
        } else {
            assertThat(finalBalance).isEqualTo(initialBalance - 30.0);
        }

        // Check notifications: only one successful withdrawal should notify
        int successNotifications = 0;
        String msg;
        while ((msg = testSubscriber.popMessage(200, TimeUnit.MILLISECONDS)) != null) {
            if (msg.contains("\"change\":-50.0") || msg.contains("\"change\":-30.0")) {
                successNotifications++;
            }
        }
        assertThat(successNotifications).as("Only one successful withdrawal should send a notification").isEqualTo(1);

        lowRetryService.shutdown();
    }


    @Test
    @DisplayName("High contention deposits should all succeed eventually")
    @Timeout(20)
        // Generous timeout for many operations
    void highContentionDeposits_allSucceed() throws InterruptedException, ExecutionException {
        UUID playerUuid = UUID.randomUUID();
        int numOperations = 200; // Number of concurrent deposits
        double amountPerOp = 10.0;
        double expectedTotal = numOperations * amountPerOp;

        ExecutorService opExecutor = Executors.newFixedThreadPool(20); // Many threads to create contention
        List<CompletableFuture<TransactionResult>> futures = IntStream.range(0, numOperations)
                .mapToObj(i ->
                        CompletableFuture.supplyAsync(() ->
                                economyService.deposit(playerUuid, amountPerOp), opExecutor
                        ).thenCompose(f -> f) // Flatten because service methods return CompletableFuture
                )
                .collect(Collectors.toList());

        CompletableFuture<Void> allOps = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));

        try {
            allOps.get(15, SECONDS); // Wait for all operations
        } catch (TimeoutException e) {
            fail("High contention deposit operations timed out", e);
        }

        opExecutor.shutdown();
        if (!opExecutor.awaitTermination(5, SECONDS)) opExecutor.shutdownNow();

        long successCount = futures.stream().map(cf -> {
            try {
                return cf.getNow(TransactionResult.error());
            } // Get result, default to error if not done
            catch (Exception e) {
                return TransactionResult.error();
            }
        }).filter(TransactionResult::isSuccess).count();

        assertThat(successCount).isEqualTo(numOperations);
        assertThat(awaitResult(economyService.getBalance(playerUuid))).isEqualTo(expectedTotal);

        // Check notifications - this might be too many to check individually / quickly
        // For high contention, just verifying the final balance is usually key.
        // We could count them if performance allows.
        AtomicInteger notificationCount = new AtomicInteger(0);
        await().atMost(5, SECONDS).pollInterval(100, TimeUnit.MILLISECONDS).until(() -> {
            while (testSubscriber.popMessage(50, TimeUnit.MILLISECONDS) != null) {
                notificationCount.incrementAndGet();
            }
            return notificationCount.get() >= numOperations;
        });
        assertThat(notificationCount.get()).isEqualTo(numOperations);

    }
}