package it.einjojo.economy.redis;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import it.einjojo.economy.exception.NotificationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.JedisException;

import java.util.Objects;
import java.util.UUID;

/**
 * Handles publishing balance update notifications to Redis Pub/Sub.
 * Uses Jedis client pool for connection management.
 */
public class RedisNotifier {

    private static final Logger log = LoggerFactory.getLogger(RedisNotifier.class);
    private static final Gson gson = new Gson();

    private final JedisPool jedisPool;
    private final String pubSubChannel;

    /**
     * Constructs a RedisNotifier.
     *
     * @param jedisPool     The pooled Jedis client instance. Must not be null.
     * @param pubSubChannel The Redis channel name to publish updates to. Must not be null or empty.
     */
    public RedisNotifier(JedisPool jedisPool, String pubSubChannel) {
        this.jedisPool = Objects.requireNonNull(jedisPool, "jedisPool cannot be null");
        this.pubSubChannel = Objects.requireNonNull(pubSubChannel, "pubSubChannel cannot be null");
        if (pubSubChannel.isBlank()) {
            throw new IllegalArgumentException("pubSubChannel cannot be empty");
        }
        log.info("RedisNotifier initialized for channel '{}'", pubSubChannel);
    }

    /**
     * Publishes a balance update notification to the configured Redis channel.
     * This operation might block briefly for network I/O. It's intended to be
     * called asynchronously by the service layer after a successful database commit.
     * Errors during publishing are logged but do not throw exceptions by default,
     * to avoid failing the primary operation due to notification issues, unless critical.
     *
     * @param playerUuid The UUID of the player whose balance changed.
     * @param newBalance The balance after the transaction.
     * @param change     The amount that was added (positive) or removed (negative).
     * @throws NotificationException If publishing fails (optional, depending on desired error handling).
     */
    public void publishUpdate(UUID playerUuid, double newBalance, double change) throws NotificationException {
        Objects.requireNonNull(playerUuid, "playerUuid cannot be null");

        JsonObject payload = new TransactionPayload(playerUuid, newBalance, change, System.currentTimeMillis()).toJson();
        String message = gson.toJson(payload);

        try (Jedis jedis = jedisPool.getResource()) {
            log.debug("Publishing to Redis channel '{}': {}", pubSubChannel, message);
            long receivers = jedis.publish(pubSubChannel, message);
            log.debug("Published update for UUID {} to {} receiver(s).", playerUuid, receivers);
        } catch (JedisException e) {
            // Log error, decide whether to rethrow or just warn
            log.error("Failed to publish balance update to Redis channel '{}' for UUID {}", pubSubChannel, playerUuid, e);
            // Depending on requirements, you might re-throw:
            // throw new NotificationException("Failed to publish notification for UUID: " + playerUuid, e);
        }
    }

    /**
     * Get the Redis channel name to publish updates to.
     *
     * @return string
     */
    public String getPubSubChannel() {
        return pubSubChannel;
    }


    /**
     * Factory method for creating a new RedisTransactionObserver instance using this instance of RedisNotifier.
     *
     * @return new instance
     */
    public RedisTransactionObserver createTransactionObserver() {
        return new RedisTransactionObserver(jedisPool.getResource(), pubSubChannel);
    }

    /**
     * Closes the underlying Jedis pool. Call this during application shutdown.
     */
    public void close() {
        if (jedisPool != null) {
            log.info("Closing JedisPool for RedisNotifier.");
            jedisPool.close();
        }
    }
}