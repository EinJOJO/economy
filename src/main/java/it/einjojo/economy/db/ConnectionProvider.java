package it.einjojo.economy.db;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Provides a sql database connection.
 */
public interface ConnectionProvider {

    /**
     * Gets a new database connection.
     *
     * @return a new database connection, which will be closed by the caller
     * @throws SQLException if an error occurs while creating the connection
     */
    Connection getConnection() throws SQLException;

}
