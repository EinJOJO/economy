package it.einjojo.economy.redis;

import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * A simple data transfer object for publishing balance updates to Redis Pub/Sub.
 *
 * @param uuid       UUID of the player whose balance changed. Must not be null.
 * @param newBalance The new balance after the transaction. Must be non-negative.
 * @param change     The amount that was added (positive) or removed (negative). Must be non-negative.
 * @param timestamp  The timestamp of the transaction. Must be non-negative.
 */
public record TransactionPayload(UUID uuid, double newBalance, double change, long timestamp) {

    /**
     * Serializes this payload to a JSON object.
     *
     * @return A JSON object containing the payload data.
     */
    public JsonObject toJson() {
        JsonObject payload = new JsonObject();
        payload.addProperty("uuid", uuid.toString());
        payload.addProperty("newBalance", newBalance);
        payload.addProperty("change", change);
        payload.addProperty("timestamp", timestamp);
        return payload;
    }

    /**
     * Deserializes a JSON object into a RedisTransactionPayload.
     *
     * @param payload The JSON object to deserialize. Must not be null
     * @return A RedisTransactionPayload instance.
     * @throws IllegalArgumentException if the payload is invalid or missing required properties.
     */
    public static TransactionPayload fromJson(JsonObject payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Payload cannot be null.");
        }
        if (!payload.has("uuid")) {
            throw new IllegalArgumentException("Payload must contain a 'uuid' property.");
        }
        if (!payload.has("newBalance")) {
            throw new IllegalArgumentException("Payload must contain a 'newBalance' property.");
        }
        if (!payload.has("change")) {
            throw new IllegalArgumentException("Payload must contain a 'change' property.");
        }
        if (!payload.has("timestamp")) {
            throw new IllegalArgumentException("Payload must contain a 'timestamp' property.");
        }
        try {
            UUID uuid = UUID.fromString(payload.get("uuid").getAsString());
            double newBalance = payload.get("newBalance").getAsDouble();
            double change = payload.get("change").getAsDouble();
            long timestamp = payload.get("timestamp").getAsLong();
            return new TransactionPayload(uuid, newBalance, change, timestamp);

        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid payload format.", e);
        }
    }

}
