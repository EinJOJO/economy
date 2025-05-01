package it.einjojo.economy.redis;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.io.Closeable;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

/**
 * Observes transaction events published on a Redis channel.
 * This class subscribes to a Redis channel using Jedis Pub/Sub and notifies registered listeners
 * when a new transaction message is received. It manages a background thread for the subscription.
 * Implements {@link Closeable} for resource cleanup.
 */
public class RedisTransactionObserver implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(RedisTransactionObserver.class);
    /**
     * List of registered listeners to be notified of transactions.
     */
    private final List<Listener> listener = new LinkedList<>();
    /**
     * Background thread that runs the Redis subscription loop.
     */
    private final Thread listenerThread;
    /**
     * The Jedis Pub/Sub handler instance.
     */
    private final PubSub pubSub;

    /**
     * Constructs a new RedisTransactionObserver.
     * Initializes the Jedis Pub/Sub mechanism and starts a background listener thread.
     *
     * @param providedJedis The Jedis instance to use for subscribing. Must not be null.
     *                      This instance should be dedicated to the Pub/Sub operation,
     *                      as the subscribe call is blocking.
     * @param gson          The Gson instance used for deserializing transaction payloads from JSON.
     * @throws NullPointerException if providedJedis is null.
     */
    public RedisTransactionObserver(Jedis providedJedis, Gson gson) {
        Objects.requireNonNull(providedJedis, "Jedis cannot be null");
        this.pubSub = new PubSub(gson, this);
        // Create and start the listener thread
        this.listenerThread = new Thread(() -> {
            try {
                // Blocking call to subscribe
                providedJedis.subscribe(pubSub);
            } catch (Exception e) {
                // Log errors unless the thread was interrupted (expected during close)
                if (!Thread.currentThread().isInterrupted()) {
                    log.error("Listener thread error", e);
                }
            }
        });
        listenerThread.setDaemon(true); // Allow JVM exit even if this thread is running
        listenerThread.start();
        log.info("RedisTransactionObserver initialized.");
    }

    /**
     * Inner class extending JedisPubSub to handle incoming messages.
     */
    private static class PubSub extends JedisPubSub {
        private final Gson gson;
        private final RedisTransactionObserver observer;

        /**
         * Constructs a PubSub handler.
         *
         * @param gson     The Gson instance for deserialization.
         * @param observer The outer RedisTransactionObserver to notify.
         */
        private PubSub(Gson gson, RedisTransactionObserver observer) {
            this.gson = gson;
            this.observer = observer;
        }

        /**
         * Called when a message is received on a subscribed channel.
         * Deserializes the JSON message into a {@link TransactionPayload} and notifies the observer.
         *
         * @param channel The channel the message was received on.
         * @param message The message content (expected to be JSON).
         */
        @Override
        public void onMessage(String channel, String message) {
            try {
                // Deserialize JSON message to JsonObject first, then to TransactionPayload
                JsonObject jsonObject = gson.fromJson(message, TypeToken.get(JsonObject.class).getType());
                TransactionPayload payload = TransactionPayload.fromJson(jsonObject);
                observer.notifyListeners(payload);
            } catch (Exception e) {
                log.error("Failed to process incoming Redis message on channel '{}': {}", channel, message, e);
            }
        }
    }

    /**
     * Registers a listener to receive transaction notifications.
     *
     * @param listener The listener to add.
     */
    public void registerListener(Listener listener) {
        this.listener.add(listener);
    }

    /**
     * Unregisters a previously registered listener.
     *
     * @param listener The listener to remove.
     */
    public void unregisterListener(Listener listener) {
        this.listener.remove(listener);
    }


    /**
     * Notifies all registered listeners about a received transaction.
     * Catches and logs exceptions thrown by individual listeners to prevent disruption.
     *
     * @param payload The transaction payload to send to listeners.
     */
    protected void notifyListeners(TransactionPayload payload) {
        for (Listener l : listener) {
            try {
                l.onTransaction(payload);
            } catch (Exception e) {
                // Log listener exceptions but continue notifying others
                log.warn("Listener threw exception: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Closes the observer, unsubscribing from Redis and stopping the listener thread.
     * Attempts to gracefully shut down the background thread.
     *
     * @throws IOException If an I/O error occurs (though typically not expected here).
     */
    @Override
    public void close() throws IOException {
        log.info("Closing RedisTransactionObserver...");
        try {
            // Unsubscribe from all channels
            if (pubSub != null && pubSub.isSubscribed()) {
                pubSub.unsubscribe();
            }
        } catch (Exception e) {
            log.warn("Exception during Jedis unsubscribe", e);
            // Ignore exceptions during unsubscribe, as the connection might already be closed
        }

        // Interrupt and join the listener thread
        if (listenerThread != null && listenerThread.isAlive()) {
            listenerThread.interrupt(); // Signal the thread to stop
            try {
                listenerThread.join(1000); // Wait up to 1 second for the thread to terminate
                if (listenerThread.isAlive()) {
                    log.warn("Listener thread did not terminate gracefully after interrupt.");
                }
            } catch (InterruptedException e) {
                log.warn("Interrupted while waiting for listener thread to join.");
                Thread.currentThread().interrupt(); // Preserve interrupt status
            }
        }
        log.info("RedisTransactionObserver closed.");
    }

    /**
     * Functional interface for listeners that want to be notified of Redis transactions.
     */
    @FunctionalInterface
    public interface Listener {
        /**
         * Called when a transaction payload is received from Redis.
         *
         * @param payload The details of the transaction.
         */
        void onTransaction(TransactionPayload payload);
    }

}