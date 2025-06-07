package it.einjojo.economy;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Veröffentlicht eine Nachricht, nachdem ein Kontostand geändert wurde
 */
public interface EconomyNotifier {
    /**
     * @param playerUuid Betroffener Spieler
     * @param balance    der neue Kontostand
     * @param amount     relative Änderung
     */
    void publishUpdate(@NotNull UUID playerUuid, double balance, double amount);
}
