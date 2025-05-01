package it.einjojo.economy.util;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * Utility methods for handling UUIDs and byte arrays.
 */
public class UUIDUtil {

    private UUIDUtil() {
        // Private constructor for utility class
    }

    /**
     * Converts a UUID to a byte array (16 bytes).
     *
     * @param uuid The UUID to convert.
     * @return A 16-byte array representation.
     */
    public static byte[] uuidToBytes(UUID uuid) {
        ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return bb.array();
    }

    /**
     * Converts a 16-byte array back to a UUID.
     *
     * @param bytes The byte array (must be 16 bytes).
     * @return The corresponding UUID.
     * @throws IllegalArgumentException if the byte array is null or not 16 bytes long.
     */
    public static UUID bytesToUuid(byte[] bytes) {
        if (bytes == null || bytes.length != 16) {
            throw new IllegalArgumentException("Invalid byte array length for UUID conversion: " + (bytes == null ? "null" : bytes.length));
        }
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        long firstLong = bb.getLong();
        long secondLong = bb.getLong();
        return new UUID(firstLong, secondLong);
    }
}