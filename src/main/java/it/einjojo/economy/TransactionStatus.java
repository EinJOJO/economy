package it.einjojo.economy;

/**
 * Represents the outcome of an economy transaction.
 */
public enum TransactionStatus {
    /**
     * The transaction was successful.
     */
    SUCCESS,
    /**
     * The target account does not have sufficient funds.
     */
    INSUFFICIENT_FUNDS,
    /**
     * The target account could not be found (relevant for withdrawals).
     */
    ACCOUNT_NOT_FOUND,
    /**
     * The amount specified for the transaction was invalid (e.g., negative).
     */
    INVALID_AMOUNT,
    /**
     * A concurrency conflict occurred (optimistic lock failure), the operation might be retried.
     */
    FAILED_CONCURRENCY,
    /**
     * An unexpected error occurred during the transaction (e.g., database or network issue).
     */
    ERROR
}