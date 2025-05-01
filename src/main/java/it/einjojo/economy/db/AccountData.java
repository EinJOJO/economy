package it.einjojo.economy.db;

import java.util.UUID;

/**
 * Data Transfer Object-holding account information retrieved from the database.
 *
 * @param uuid    Player UUID
 * @param balance Current balance
 * @param version Optimistic locking version
 */
public record AccountData(UUID uuid, double balance, long version) {
}