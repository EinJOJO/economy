package it.einjojo.economy.notifier;

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



}
