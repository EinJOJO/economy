package it.einjojo.economy.redis;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Gson GSON = new Gson();
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
     * Constructs a RedisTransactionObserver, which can be used to listen for transaction notifications.
     *
     * @param notifier Notifier instance for channel name and providing a single jedis instance
     */
    public RedisTransactionObserver(RedisNotifier notifier) {
        Objects.requireNonNull(notifier, "Jedis cannot be null");
        this.pubSub = new PubSub(this);
        // Create and start the listener thread
        this.listenerThread = new Thread(() -> {
            try {
                notifier.getJedisPool().subscribe(pubSub, notifier.getPubSubChannel());
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
        private final RedisTransactionObserver observer;

        /**
         * Constructs a PubSub handler.
         *
         * @param observer The outer RedisTransactionObserver to notify.
         */
        private PubSub(RedisTransactionObserver observer) {
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
                TransactionPayload payload = GSON.fromJson(message, TransactionPayload.class);
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
     */
    @Override
    public void close()  {
        log.info("Closing RedisTransactionObserver...");
        try {
            // Unsubscribe from all channels
            if (pubSub != null && pubSub.isSubscribed()) {
                pubSub.unsubscribe();
            }
        } catch (Exception e) {
            log.warn("Exception during Jedis unsubscribe", e);
            // Ignore exceptions during unsubscribing, as the connection might already be closed
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