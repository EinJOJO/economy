package it.einjojo.economy.db;

import java.time.Instant;
import java.util.UUID;

public record LogEntry(UUID playerUuid, long version, double relativeChange, String reason, Instant timestamp) {


}
