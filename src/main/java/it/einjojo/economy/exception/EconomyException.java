package it.einjojo.economy.exception;

/**
 * Base runtime exception for economy-related errors.
 */
public class EconomyException extends RuntimeException {
    /**
     * Constructs a new EconomyException with the specified message.
     *
     * @param message The exception message. Must not be null or empty.
     */
    public EconomyException(String message) {
        super(message);
    }

    /**
     * Constructs a new EconomyException with the specified message and cause.
     *
     * @param message The exception message. Must not be null or empty.
     * @param cause   The exception cause.
     */
    public EconomyException(String message, Throwable cause) {
        super(message, cause);
    }
}