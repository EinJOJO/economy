package it.einjojo.economy;

import java.util.Optional;

/**
 * Enthält Details über das Ergebnis einer Wirtschaftstransaktion.
 *
 * @param status     Der Statuscode, der das Ergebnis angibt.
 * @param newBalance Ein Optional, das den Kontostand nach der Transaktion enthält, falls zutreffend und erfolgreich.
 * @param change     Der tatsächlich geänderte Betrag (positiv für Einzahlung/Setzen, negativ für Abhebung).
 */
public record TransactionResult(TransactionStatus status, Optional<Double> newBalance, double change) {

    // Convenience factory methods

    /**
     * Erstellt ein erfolgreiches TransactionResult.
     *
     * @param newBalance Der neue Kontostand nach der Transaktion.
     * @param change     Der Betrag, der hinzugefügt (positiv) oder entfernt (negativ) wurde.
     * @return Ein TransactionResult mit dem Status SUCCESS und dem angegebenen Kontostand.
     */
    public static TransactionResult success(double newBalance, double change) {
        return new TransactionResult(TransactionStatus.SUCCESS, Optional.of(newBalance), change);
    }

    /**
     * Erstellt ein TransactionResult, das auf unzureichende Mittel hinweist.
     *
     * @return Ein TransactionResult mit dem Status INSUFFICIENT_FUNDS und keinem Kontostand.
     */
    public static TransactionResult insufficientFunds() {
        return new TransactionResult(TransactionStatus.INSUFFICIENT_FUNDS, Optional.empty(), 0);
    }

    /**
     * Erstellt ein TransactionResult, das darauf hinweist, dass das Konto nicht gefunden wurde.
     *
     * @return Ein TransactionResult mit dem Status ACCOUNT_NOT_FOUND und keinem Kontostand.
     */
    public static TransactionResult accountNotFound() {
        return new TransactionResult(TransactionStatus.ACCOUNT_NOT_FOUND, Optional.empty(), 0);
    }

    /**
     * Erstellt ein TransactionResult, das auf einen ungültigen Betrag hinweist.
     *
     * @return Ein TransactionResult mit dem Status INVALID_AMOUNT und keinem Kontostand.
     */
    public static TransactionResult invalidAmount() {
        return new TransactionResult(TransactionStatus.INVALID_AMOUNT, Optional.empty(), 0);
    }

    /**
     * Erstellt ein TransactionResult, das auf einen Gleichzeitigkeitsfehler hinweist.
     *
     * @return Ein TransactionResult mit dem Status FAILED_CONCURRENCY und keinem Kontostand.
     */
    public static TransactionResult concurrentModification() {
        return new TransactionResult(TransactionStatus.FAILED_CONCURRENCY, Optional.empty(), 0);
    }

    /**
     * Erstellt ein TransactionResult, das auf einen allgemeinen Fehler hinweist.
     *
     * @return Ein TransactionResult mit dem Status ERROR und keinem Kontostand.
     */
    public static TransactionResult error() {
        return new TransactionResult(TransactionStatus.ERROR, Optional.empty(), 0);
    }

    /**
     * Überprüft, ob die Transaktion erfolgreich war.
     *
     * @return {@code true}, wenn der Status {@link TransactionStatus#SUCCESS} ist, andernfalls {@code false}.
     */
    public boolean isSuccess() {
        return status == TransactionStatus.SUCCESS;
    }
}