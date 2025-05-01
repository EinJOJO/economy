package it.einjojo.economy.exception;

/**
 * Exception thrown when publishing a notification fails.
 */
public class NotificationException extends EconomyException {
    /**
     * Constructs a new NotificationException with the specified message and cause.
     *
     * @param message The exception message. Must not be null or empty.
     * @param cause   The exception cause.
     */
    public NotificationException(String message, Throwable cause) {
        super(message, cause);
    }
}