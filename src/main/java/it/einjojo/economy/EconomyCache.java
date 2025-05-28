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

    boolean isCached(UUID playerUuid);

}
