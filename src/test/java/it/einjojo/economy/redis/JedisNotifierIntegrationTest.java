package it.einjojo.economy.redis;

import it.einjojo.economy.base.AbstractIntegrationTest;
import it.einjojo.economy.notifier.JedisNotifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

public class JedisNotifierIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(JedisNotifierIntegrationTest.class);
    private static final String TEST_CHANNEL = "test:economy:updates";
    private JedisNotifier notifier;
    private Thread subscriberThread;
    private TestSubscriber testSubscriber;

    private static class TestSubscriber extends JedisPubSub {
        private final CountDownLatch messageLatch = new CountDownLatch(1);
        private final AtomicReference<String> receivedMessage = new AtomicReference<>();
        private final AtomicReference<String> receivedChannel = new AtomicReference<>();

        @Override
        public void onMessage(String channel, String message) {
            log.info("TestSubscriber received: [{}] {}", channel, message);
            receivedChannel.set(channel);
            receivedMessage.set(message);
            messageLatch.countDown();
        }

        public boolean awaitMessage(long timeout, TimeUnit unit) throws InterruptedException {
            return messageLatch.await(timeout, unit);
        }

        public String getMessage() {
            return receivedMessage.get();
        }

        public String getChannel() {
            return receivedChannel.get();
        }
    }

    @BeforeEach
    void setUpNotifierAndSubscriber() {
        // testJedisPool is from AbstractIntegrationTest
        notifier = new JedisNotifier(testJedisPool, TEST_CHANNEL);

        testSubscriber = new TestSubscriber();
        subscriberThread = new Thread(() -> {
            // Use a new Jedis instance for subscription, not from the pool meant for publishing
            try (Jedis jedisSub = new Jedis(redisContainer.getHost(), redisContainer.getMappedPort(6379))) {
                jedisSub.subscribe(testSubscriber, TEST_CHANNEL);
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted()) {
                    log.error("Subscriber thread error", e);
                }
            }
        });
        subscriberThread.setDaemon(true);
        subscriberThread.start();
        // Give subscriber a moment to connect
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @AfterEach
    void tearDownSubscriber() {
        if (testSubscriber != null) {
            try {
                testSubscriber.unsubscribe();
            } catch (Exception e) {
                // ignore
            }
        }
        if (subscriberThread != null && subscriberThread.isAlive()) {
            subscriberThread.interrupt();
            try {
                subscriberThread.join(1000); // Wait for thread to die
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // Clean up any messages on the channel if needed (though usually not for pub/sub tests)
    }

    @Test
    @DisplayName("publishUpdate sends message to correct Redis channel")
    void publishUpdate_sendsMessageToChannel() throws InterruptedException {
        UUID playerUuid = UUID.randomUUID();
        double newBalance = 123.45;
        double change = 10.0;

        notifier.publishUpdate(playerUuid, newBalance, change);

        boolean messageReceived = testSubscriber.awaitMessage(3, TimeUnit.SECONDS);
        assertThat(messageReceived).as("Should receive a message on Redis").isTrue();
        assertThat(testSubscriber.getChannel()).isEqualTo(TEST_CHANNEL);

        String jsonMessage = testSubscriber.getMessage();
        assertThat(jsonMessage)
                .contains("\"uuid\":\"" + playerUuid.toString() + "\"")
                .contains("\"newBalance\":" + newBalance)
                .contains("\"change\":" + change);
    }

    @Test
    @DisplayName("publishUpdate handles JedisException gracefully (logs error)")
    void publishUpdate_handlesJedisException() throws InterruptedException {
        // Simulate Redis being down by closing the pool temporarily (for this test only)
        JedisPool originalPool = testJedisPool; // Keep original
        JedisPool mockDownPool = new JedisPool("localhost", 12345); // Invalid port
        JedisNotifier faultyNotifier = new JedisNotifier(mockDownPool, TEST_CHANNEL);

        // We expect it to log an error but not throw an exception from publishUpdate itself by default
        // This requires checking logs or using a spy/mock logger, which can be complex.
        // A simpler check is that it doesn't throw.
        // And that our original subscriber does NOT receive a message.
        assertThatCode(() -> faultyNotifier.publishUpdate(UUID.randomUUID(), 100, 10))
                .doesNotThrowAnyException();

        // Verify no message was received on the valid channel
        boolean messageReceived = testSubscriber.awaitMessage(1, TimeUnit.SECONDS);
        assertThat(messageReceived).as("Should NOT receive a message if Redis publish failed").isFalse();

        mockDownPool.close(); // Clean up mock pool
        // Restore original pool for other tests if necessary (though usually tests are isolated)
    }

}