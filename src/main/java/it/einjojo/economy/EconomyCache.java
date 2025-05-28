package it.einjojo.economy;

import java.util.UUID;

/**
 * Read-only cache for economy-related data.
 */
public interface EconomyCache {

    /**
     * Will return 0.0 if the player is not in the cache.
     *
     * @param playerUuid player
     * @return the cached balance or 0.0
     */
    double getBalance(UUID playerUuid);

    /**
     * Check if a player is in cache
     *
     * @param playerUuid player uuid
     * @return true if the player is in the cache, false otherwise.
     */
    boolean isCached(UUID playerUuid);

}
