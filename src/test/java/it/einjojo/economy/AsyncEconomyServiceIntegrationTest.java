package it.einjojo.economy;

import it.einjojo.economy.base.AbstractIntegrationTest;
import it.einjojo.economy.db.PostgresEconomyRepository;
import it.einjojo.economy.notifier.JedisNotifier;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
    private JedisNotifier notifier;
    private ExecutorService dbExecutor;
    private ExecutorService notificationExecutor;
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
            subscriptionActive.set(false);
        }


        @Override
        public void onMessage(String channel, String message) {
            log.info("Test subscriber: Received message on [{}]: {}", channel, message);
            messages.offer(message);
        }

        public boolean awaitSubscription(long timeout, TimeUnit unit) throws InterruptedException {
            return connectionLatch.await(timeout, unit);
        }

        /**
         * Pops a message from the queue if available. Non-blocking.
         *
         * @return The message string, or null if the queue is empty.
         */
        public String popMessage() {
            return messages.poll();
        }

        /**
         * Pops a message, waiting up to the specified timeout if necessary.
         *
         * @param timeout the maximum time to wait
         * @param unit    the time unit of the timeout argument
         * @return the message, or null if the timeout expires before a message is available
         * @throws InterruptedException if interrupted while waiting
         */
        public String popMessage(long timeout, TimeUnit unit) throws InterruptedException {
            long startTime = System.currentTimeMillis();
            long waitMillis = unit.toMillis(timeout);
            while (System.currentTimeMillis() - startTime < waitMillis) {
                String msg = messages.poll();
                if (msg != null) return msg;
                // Avoid busy-waiting, sleep for a short interval
                Thread.sleep(Math.min(50, waitMillis - (System.currentTimeMillis() - startTime) > 0 ? waitMillis - (System.currentTimeMillis() - startTime) : 50));
            }
            return messages.poll(); // Final check after timeout
        }


        public void clearMessages() {
            messages.clear();
        }

    }


    @BeforeEach
    void setUpServiceAndComponents() throws InterruptedException {
        clearPlayerBalancesTable(); // From AbstractIntegrationTest

        repository = new PostgresEconomyRepository(testConnectionProvider);
        dbExecutor = Executors.newFixedThreadPool(4, r -> { // Pool for DB tasks
            Thread t = new Thread(r);
            t.setName("DBExecutor-" + t.getId());
            t.setDaemon(true);
            return t;
        });
        notificationExecutor = Executors.newFixedThreadPool(2, r -> { // Smaller pool for notifications
            Thread t = new Thread(r);
            t.setName("NotificationExecutor-" + t.getId());
            t.setDaemon(true);
            return t;
        });

        notifier = new JedisNotifier(testJedisPool, TEST_REDIS_CHANNEL);

        // Use standard retries for most tests
        economyService = new AsyncEconomyService(repository, notifier, dbExecutor, notificationExecutor, 3, 50L); // Standard retries

        awaitResult(economyService.initialize());

        testSubscriber = new TestRedisSubscriber();
        subscriberThread = new Thread(() -> {
            try (Jedis jedis = new Jedis(redisContainer.getHost(), redisContainer.getMappedPort(6379))) {
                // Ensure connection before subscribing
                if (!"PONG".equalsIgnoreCase(jedis.ping())) {
                    log.error("Test subscriber failed to PING redis");
                    return;
                }
                log.info("Test subscriber connected, subscribing to {}", TEST_REDIS_CHANNEL);
                jedis.subscribe(testSubscriber, TEST_REDIS_CHANNEL);
                log.info("Test subscriber finished subscribing or was interrupted.");

            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted() && (testSubscriber == null || !testSubscriber.isSubscribed())) {
                    log.error("Test Redis subscriber thread error during connection/subscription", e);
                } else {
                    log.warn("Test Redis subscriber thread interrupted or exception during unsubscribe.");
                }
            }
        }, "TestRedisSubscriberThread");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
        if (!testSubscriber.awaitSubscription(5, SECONDS)) { // Increased timeout slightly
            fail("Test Redis subscriber failed to connect and subscribe in time.");
        }
        log.info("Test subscriber subscription confirmed by latch.");
        testSubscriber.clearMessages();
    }

    @AfterEach
    void tearDownServiceAndComponents() {
        log.info("Starting @AfterEach cleanup...");
        if (testSubscriber != null && testSubscriber.isSubscribed()) {
            log.info("Unsubscribing test subscriber...");
            try {
                testSubscriber.unsubscribe();
                log.info("Test subscriber unsubscribed.");
            } catch (Exception e) {
                log.warn("Exception during test subscriber unsubscribe", e);
            }
        } else {
            log.info("Test subscriber was null or not subscribed.");
        }

        if (subscriberThread != null && subscriberThread.isAlive()) {
            log.info("Interrupting subscriber thread...");
            subscriberThread.interrupt();
            try {
                subscriberThread.join(2000); // Wait a bit longer
                log.info("Subscriber thread joined.");
                if (subscriberThread.isAlive()) {
                    log.warn("Subscriber thread did not terminate after join.");
                }
            } catch (InterruptedException e) {
                log.warn("Interrupted while joining subscriber thread.");
                Thread.currentThread().interrupt();
            }
        } else {
            log.info("Subscriber thread was null or not alive.");
        }

        if (economyService != null) {
            log.info("Shutting down economy service...");
            economyService.shutdown();
        }
        if (dbExecutor != null) {
            log.info("Shutting down service executor...");
            dbExecutor.shutdown();
            try {
                if (!dbExecutor.awaitTermination(5, SECONDS)) {
                    log.warn("Service executor did not terminate gracefully, forcing shutdown.");
                    dbExecutor.shutdownNow();
                } else {
                    log.info("Service executor shut down gracefully.");
                }
            } catch (InterruptedException e) {
                log.warn("Interrupted while shutting down service executor.");
                dbExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("@AfterEach cleanup finished.");
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

        awaitResult(economyService.deposit(playerUuid, 10.0, "hasAccount-false"));
        assertThat(awaitResult(economyService.hasAccount(playerUuid))).isTrue();
    }

    @Test
    @DisplayName("deposit adds funds and publishes notification")
    void deposit_updatesBalanceAndNotifies() { // Removed InterruptedException
        UUID playerUuid = UUID.randomUUID();
        double amount = 123.45;

        TransactionResult result = awaitResult(economyService.deposit(playerUuid, amount, "deposit-adds-and-publish"));

        assertThat(result.status()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(result.newBalance()).hasValue(amount);
        assertThat(result.change()).isEqualTo(amount);
        assertThat(awaitResult(economyService.getBalance(playerUuid))).isEqualTo(amount);

        AtomicReference<String> receivedNotification = new AtomicReference<>();
        await().atMost(5, SECONDS).pollInterval(50, TimeUnit.MILLISECONDS).until(() -> {
            String msg = testSubscriber.popMessage(); // Use non-blocking poll
            if (msg != null) {
                log.info("Received notification in test: {}", msg); // Log received message
                receivedNotification.set(msg);
                return true;
            }
            return false;
        });

        String notification = receivedNotification.get();
        assertThat(notification).as("Notification message should not be null").isNotNull();
        assertThat(notification)
                .contains("\"uuid\":\"" + playerUuid.toString() + "\"")
                .contains("\"newBalance\":" + amount)
                .contains("\"change\":" + amount);
    }

    @Test
    @DisplayName("deposit with invalid (zero or negative) amount fails")
    void deposit_invalidAmount_fails() {
        UUID playerUuid = UUID.randomUUID();
        TransactionResult resultZero = awaitResult(economyService.deposit(playerUuid, 0.0, "invalid amount"));
        assertThat(resultZero.status()).isEqualTo(TransactionStatus.INVALID_AMOUNT);

        TransactionResult resultNegative = awaitResult(economyService.deposit(playerUuid, -10.0, "invalid amount"));
        assertThat(resultNegative.status()).isEqualTo(TransactionStatus.INVALID_AMOUNT);

        assertThat(awaitResult(economyService.getBalance(playerUuid))).isEqualTo(0.0); // Balance unchanged
    }


    @Test
    @DisplayName("withdraw succeeds with sufficient funds and notifies")
    void withdraw_sufficientFunds_succeedsAndNotifies() { // Removed InterruptedException
        UUID playerUuid = UUID.randomUUID();
        double initialBalance = 100.0;
        double withdrawAmount = 30.0;
        awaitResult(economyService.deposit(playerUuid, initialBalance, "withdraw-sufficient-funds-succeeds"));
        waitUntilReceivedMessageAndPopIt();

        TransactionResult result = awaitResult(economyService.withdraw(playerUuid, withdrawAmount, "withdraw-sufficient-funds-succeeds-notifies"));

        assertThat(result.status()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(result.newBalance()).hasValue(initialBalance - withdrawAmount);
        assertThat(result.change()).isEqualTo(-withdrawAmount);
        assertThat(awaitResult(economyService.getBalance(playerUuid))).isEqualTo(initialBalance - withdrawAmount);

        AtomicReference<String> receivedNotification = new AtomicReference<>();
        await().atMost(5, SECONDS).pollInterval(50, TimeUnit.MILLISECONDS).until(() -> {
            String msg = testSubscriber.popMessage(); // Use non-blocking poll
            if (msg != null) {
                log.info("Received notification in test: {}", msg); // Log received message
                receivedNotification.set(msg);
                return true;
            }
            return false;
        });

        String notification = receivedNotification.get();
        assertThat(notification).as("Notification message should not be null").isNotNull();
        assertThat(notification).contains("\"uuid\":\"" + playerUuid.toString() + "\"");
        assertThat(notification).contains("\"newBalance\":" + (initialBalance - withdrawAmount));
        assertThat(notification).contains("\"change\":" + (-withdrawAmount));
    }

    @Test
    @DisplayName("withdraw fails for insufficient funds")
    void withdraw_insufficientFunds_fails() throws InterruptedException {
        UUID playerUuid = UUID.randomUUID();
        awaitResult(economyService.deposit(playerUuid, 10.0, "withdraw-insufficient-funds-succeeds"));
        waitUntilReceivedMessageAndPopIt();
        TransactionResult result = awaitResult(economyService.withdraw(playerUuid, 20.0, "withdraw-insufficient-funds-fails"));
        assertThat(result.status()).isEqualTo(TransactionStatus.INSUFFICIENT_FUNDS);
        assertThat(awaitResult(economyService.getBalance(playerUuid))).isEqualTo(10.0);
        assertThat(testSubscriber.popMessage(200, TimeUnit.MILLISECONDS)).isNull(); // No notification on failure
    }

    @Test
    @DisplayName("withdraw fails for non-existent account")
    void withdraw_nonExistentAccount_fails() {
        UUID playerUuid = UUID.randomUUID();
        TransactionResult result = awaitResult(economyService.withdraw(playerUuid, 10.0, "fails"));
        assertThat(result.status()).isEqualTo(TransactionStatus.ACCOUNT_NOT_FOUND);
    }


    @Test
    @DisplayName("setBalance updates existing balance and notifies")
    void setBalance_existingAccount_updatesAndNotifies() { // Removed InterruptedException
        UUID playerUuid = UUID.randomUUID();
        double initialBalance = 50.0;
        double newBalanceSet = 200.0;
        awaitResult(economyService.deposit(playerUuid, initialBalance, "setBalance-existing-account-updates"));
        waitUntilReceivedMessageAndPopIt();

        TransactionResult result = awaitResult(economyService.setBalance(playerUuid, newBalanceSet, "setBalance-existing-account-updates-notifies"));
        assertThat(result.status()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(result.newBalance()).hasValue(newBalanceSet);
        assertThat(result.change()).isEqualTo(newBalanceSet - initialBalance);
        assertThat(awaitResult(economyService.getBalance(playerUuid))).isEqualTo(newBalanceSet);

        AtomicReference<String> receivedNotification = new AtomicReference<>();
        await().atMost(5, SECONDS).pollInterval(50, TimeUnit.MILLISECONDS).until(() -> {
            String msg = testSubscriber.popMessage(); // Use non-blocking poll
            if (msg != null) {
                log.info("Received notification in test: {}", msg); // Log received message
                receivedNotification.set(msg);
                return true;
            }
            return false;
        });

        String notification = receivedNotification.get();
        assertThat(notification).as("Notification message should not be null").isNotNull();
        assertThat(notification).contains("\"uuid\":\"" + playerUuid.toString() + "\"");
        assertThat(notification).contains("\"newBalance\":" + newBalanceSet);
        assertThat(notification).contains("\"change\":" + (newBalanceSet - initialBalance));
    }

    private void waitUntilReceivedMessageAndPopIt() {
        await().atMost(5, SECONDS).pollInterval(50, TimeUnit.MILLISECONDS).until(() -> {
            String msg = testSubscriber.popMessage(); // wait for notification to arrive
            return msg != null;
        });
    }

    @Test
    @DisplayName("setBalance creates new account and notifies")
    void setBalance_newAccount_createsAndNotifies() { // Removed InterruptedException
        UUID playerUuid = UUID.randomUUID();
        double newBalanceSet = 75.0;

        TransactionResult result = awaitResult(economyService.setBalance(playerUuid, newBalanceSet, "setBalance-new-account-creates-notifies"));
        assertThat(result.status()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(result.newBalance()).hasValue(newBalanceSet);
        assertThat(result.change()).isEqualTo(newBalanceSet); // Change is new balance as old was 0
        assertThat(awaitResult(economyService.getBalance(playerUuid))).isEqualTo(newBalanceSet);

        AtomicReference<String> receivedNotification = new AtomicReference<>();
        await().atMost(5, SECONDS).pollInterval(50, TimeUnit.MILLISECONDS).until(() -> {
            String msg = testSubscriber.popMessage(); // Use non-blocking poll
            if (msg != null) {
                log.info("Received notification in test: {}", msg); // Log received message
                receivedNotification.set(msg);
                return true;
            }
            return false;
        });

        String notification = receivedNotification.get();
        assertThat(notification).as("Notification message should not be null").isNotNull();
        assertThat(notification).contains("\"uuid\":\"" + playerUuid + "\"");
        assertThat(notification).contains("\"newBalance\":" + newBalanceSet);
        assertThat(notification).contains("\"change\":" + newBalanceSet);
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
        // Filter out null futures if any occurred (shouldn't with direct call)
        List<CompletableFuture<TransactionResult>> futures = IntStream.range(0, numOperations)
                .mapToObj(i ->
                        // Directly use economyService.deposit which returns CompletableFuture
                        economyService.deposit(playerUuid, amountPerOp, "high-contention-deposit-" + i)
                                // Add logging on completion for debugging contention issues
                                .whenComplete((res, ex) -> {
                                    if (ex != null) {
                                        log.error("Deposit future {} completed exceptionally", i, ex);
                                    } else if (res != null && !res.isSuccess()) {
                                        log.warn("Deposit future {} completed with status {}", i, res.status());
                                    }
                                })
                )
                .toList();

        CompletableFuture<Void> allOps = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));

        try {
            allOps.get(15, SECONDS); // Wait for all operations
        } catch (TimeoutException e) {
            opExecutor.shutdownNow(); // Force shutdown on timeout
            fail("High contention deposit operations timed out", e);
        } catch (ExecutionException e) {
            opExecutor.shutdownNow();
            log.error("Error waiting for high contention deposits", e.getCause());
            fail("High contention deposit operations failed", e.getCause());
        }


        opExecutor.shutdown();
        if (!opExecutor.awaitTermination(5, SECONDS)) opExecutor.shutdownNow();

        long successCount = futures.stream().map(cf -> {
            try {
                // Use getNow with a default error result if future isn't complete (should be complete after allOf.get())
                return cf.getNow(TransactionResult.error());
            } catch (Exception e) {
                // Handle potential CompletionException or CancellationException
                log.error("Exception getting future result in count", e);
                return TransactionResult.error();
            }
        }).filter(TransactionResult::isSuccess).count();

        assertThat(successCount)
                .as("All deposit operations should succeed")
                .isEqualTo(numOperations);


        assertThat(awaitResult(economyService.getBalance(playerUuid)))
                .isEqualTo(expectedTotal);


    }


}
