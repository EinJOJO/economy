package it.einjojo.economy;

import java.util.UUID;

/**
 * Read-only cache for economy-related data.
 */
public interface EconomyCache {

    /**
     * Den gecachten Kontostand abfragen.
     *
     * @param playerUuid Spieler
     * @return the cached balance oder 0.0
     */
    double getBalance(UUID playerUuid);

    /**
     * Prüfe, ob ein Spieler im Cache ist
     *
     * @param playerUuid player uuid
     * @return true, wenn der Spieler im Cache ist
     */
    boolean isCached(UUID playerUuid);

    /**
     * Wenn die Instanz an {@link AsyncEconomyService} übergeben wird,
     * wird bei sämtlichen Operationen in diesen Cache geschrieben.
     *
     * @param playerUuid uuid
     * @param balance    der neue Kontostand
     */
    void cacheBalance(UUID playerUuid, double balance);

}
