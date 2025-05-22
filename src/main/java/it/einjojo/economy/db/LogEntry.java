package it.einjojo.economy.db;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a log entry for an economy transaction. This class is used to
 * record and retrieve details about changes made to a player's account, such
 * as deposits, withdrawals, or other balance modifications.
 *
 * @param playerUuid     The UUID of the player associated with this log entry.
 * @param version        The account version at the time this log entry was created.
 * @param relativeChange The amount by which the player's balance changed. Positive
 *                       values indicate an increase (e.g., deposit), while negative
 *                       values indicate a decrease (e.g., withdrawal).
 * @param reason         The reason for the balance change (e.g., transaction type, justification).
 * @param timestamp      The timestamp at which the log entry was created.
 */
public record LogEntry(UUID playerUuid, long version, double relativeChange, String reason, Instant timestamp) {


}
