package it.einjojo.economy.exception;

/**
 * Exception thrown when a database operation fails within the repository.
 */
public class RepositoryException extends EconomyException {
    /**
     * Constructs a new RepositoryException with the specified message and cause.
     * @param message The exception message. Must not be null or empty.
     * @param cause The exception cause.
     */
    public RepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}