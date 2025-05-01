package it.einjojo.economy.base;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import it.einjojo.economy.db.ConnectionProvider;
import it.einjojo.economy.db.PostgresEconomyRepository; // Import the repository
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.JedisPooled;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

@Testcontainers // Enables Testcontainers JUnit 5 support
public abstract class AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger("TestContainers");
    private static final Logger pgLog = LoggerFactory.getLogger("PostgreSQLContainer");
    private static final Logger redisLog = LoggerFactory.getLogger("RedisContainer");


    // PostgreSQL Container Configuration
    @Container
    public static final PostgreSQLContainer<?> postgreSQLContainer =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:13-alpine"))
                    .withDatabaseName("test_economy_db")
                    .withUsername("testuser")
                    .withPassword("testpass")
                    .withLogConsumer(new Slf4jLogConsumer(pgLog).withPrefix("PGSQL"));


    // Redis Container Configuration
    @Container
    public static final GenericContainer<?> redisContainer =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379) // Default Redis port
                    .withLogConsumer(new Slf4jLogConsumer(redisLog).withPrefix("REDIS"));


    protected static ConnectionProvider testConnectionProvider;
    private static HikariDataSource dataSource; // Keep HikariDataSource to close it
    protected static JedisPooled testJedisPool;


    @BeforeAll
    static void initializeSharedResources() {
        // --- PostgreSQL Setup ---
        log.info("Setting up PostgreSQL connection pool...");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgreSQLContainer.getJdbcUrl());
        config.setUsername(postgreSQLContainer.getUsername());
        config.setPassword(postgreSQLContainer.getPassword());
        config.setDriverClassName(org.postgresql.Driver.class.getName()); // Explicitly set driver
        config.setMaximumPoolSize(5); // Small pool for tests
        config.setConnectionTimeout(5000); // 5 seconds
        dataSource = new HikariDataSource(config);

        testConnectionProvider = dataSource::getConnection;
        log.info("PostgreSQL connection pool initialized. URL: {}", postgreSQLContainer.getJdbcUrl());

        // ** FIX: Ensure schema exists once globally after connection provider is ready **
        try {
            log.info("Ensuring database schema exists globally for test class...");
            // Create a temporary repository instance just for schema creation
            PostgresEconomyRepository initialRepo = new PostgresEconomyRepository(testConnectionProvider);
            initialRepo.ensureSchemaExists();
            log.info("Global database schema ensured.");
        } catch (Exception e) {
            log.error("FATAL: Failed to ensure global database schema during @BeforeAll", e);
            // Fail fast if initial schema setup fails, as tests depend on it.
            throw new RuntimeException("Failed initial schema setup", e);
        }


        // --- Redis Setup ---
        log.info("Setting up Redis connection pool...");
        String redisHost = redisContainer.getHost();
        Integer redisPort = redisContainer.getMappedPort(6379);
        testJedisPool = new JedisPooled(redisHost, redisPort);
        // Test connection
        try {
            String pingResponse = testJedisPool.ping();
            log.info("Redis connection successful. PING response: {}. Host: {}:{}", pingResponse, redisHost, redisPort);
        } catch (Exception e) {
            log.error("Failed to connect to Testcontainers Redis instance", e);
            throw new RuntimeException("Redis connection failed", e);
        }
    }

    @AfterAll
    static void cleanupSharedResources() {
        log.info("Cleaning up shared test resources...");
        if (testJedisPool != null) {
            try {
                testJedisPool.close();
                log.info("Redis pool closed.");
            } catch (Exception e) {
                log.error("Error closing Redis pool", e);
            }
        }
        if (dataSource != null) {
            try {
                dataSource.close();
                log.info("HikariDataSource closed.");
            } catch (Exception e) {
                log.error("Error closing HikariDataSource", e);
            }
        }
        // Testcontainers JUnit extension automatically stops the containers.
        log.info("Shared test resources cleanup finished.");
    }

    /**
     * Helper method to clear the player_balances table before each test method if needed.
     * Can be called in @BeforeEach of specific test classes.
     * Assumes the table has already been created (e.g., in @BeforeAll).
     */
    protected void clearPlayerBalancesTable() {
        log.debug("Attempting to clear 'player_balances' table..."); // Add log
        try (Connection conn = testConnectionProvider.getConnection();
             Statement stmt = conn.createStatement()) {
            // Using TRUNCATE is fast. RESTART IDENTITY resets sequences if any are used implicitly/explicitly.
            stmt.execute("TRUNCATE TABLE player_balances RESTART IDENTITY CASCADE;");
            log.debug("Table 'player_balances' truncated successfully."); // Add success log
        } catch (SQLException e) {
            // Fail fast if table cleanup fails, as it can affect test isolation
            log.error("Failed to clear player_balances table. Does it exist?", e); // Add error log
            throw new RuntimeException("Failed to clear player_balances table", e);
        }
    }
}