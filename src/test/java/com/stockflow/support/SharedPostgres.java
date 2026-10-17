package com.stockflow.support;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The one PostgreSQL server every Spring test context in this JVM shares, and the template databases
 * the contexts' own databases are copied from.
 *
 * <p><b>Why.</b> A container per context meant ~28 Postgres servers per build, each migrated and
 * seeded from scratch, and — because Spring keeps cached contexts open until the JVM exits — most of
 * them running at the same time. On a 16 GB laptop that pushed Windows into paging and froze the
 * machine. Here the server starts once, each distinct Flyway configuration is migrated once into a
 * template, and every context gets {@code CREATE DATABASE ... TEMPLATE}: a file copy, not a replay
 * of a hundred migrations.</p>
 *
 * <p><b>Isolation is unchanged.</b> Each context still owns a database nobody else has written to,
 * exactly as with its own container; only the server process is shared. A context's database is
 * dropped when the context closes (cache eviction or JVM exit), so evicted contexts give their
 * memory back.</p>
 *
 * <p><b>The template follows the application's Flyway configuration</b> rather than restating it:
 * locations and schemas are bound from {@code spring.flyway.*} of the context asking, so a
 * {@code test}-profile context gets the demo seed and a {@code @DataJpaTest} without the profile does
 * not — each with its own template. Spring's own Flyway still runs on every copy; it finds the
 * history complete and validates it, so a setting this class does not mirror shows up as a migration
 * applied at context start or a validation failure, never as a silently different schema.</p>
 */
final class SharedPostgres {

    private static final String USER = "stockflow";
    private static final String PASSWORD = "stockflow";

    private static PostgreSQLContainer<?> server;
    private static final Map<Migrations, String> TEMPLATES = new HashMap<>();
    private static int databaseCount;

    private SharedPostgres() {
    }

    /** A freshly migrated database for one Spring context, copied from the template for its Flyway settings. */
    static synchronized TestDatabase freshDatabase(Environment environment) {
        startServerIfNeeded();
        String template = TEMPLATES.computeIfAbsent(Migrations.of(environment), SharedPostgres::migrateTemplate);
        String name = "test_" + (++databaseCount);
        execute("CREATE DATABASE " + name + " TEMPLATE " + template);
        return new TestDatabase(name);
    }

    // Lazy rather than a static initialiser: without Docker, a static initialiser fails once with the real cause and
    // then as NoClassDefFoundError for every later context, which hides why.
    @SuppressWarnings("resource") // Testcontainers' Ryuk stops the container when the JVM exits.
    private static void startServerIfNeeded() {
        if (server != null) return;
        var container = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("stockflow")
                .withUsername(USER)
                .withPassword(PASSWORD)
                // fsync=off is Testcontainers' own default, restated because withCommand replaces it. Room for the
                // capped context cache (see spring.properties) times the test pool size, plus the DataJpaTest pools.
                .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=200");
        container.start();
        server = container;
    }

    private static String migrateTemplate(Migrations migrations) {
        String name = "template_" + TEMPLATES.size();
        execute("CREATE DATABASE " + name);
        Flyway.configure()
                .dataSource(url(name), USER, PASSWORD)
                .locations(migrations.locations().toArray(String[]::new))
                .defaultSchema(migrations.defaultSchema())
                .schemas(migrations.schemas().toArray(String[]::new))
                .load()
                .migrate();
        // CREATE DATABASE ... TEMPLATE fails while anyone is connected to the source; make that impossible.
        execute("ALTER DATABASE " + name + " WITH ALLOW_CONNECTIONS false");
        return name;
    }

    private static void drop(String database) {
        if (server == null || !server.isRunning()) return; // already gone with the container at JVM exit
        execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }

    private static void execute(String statement) {
        try (var connection = DriverManager.getConnection(url("postgres"), USER, PASSWORD);
                var sql = connection.createStatement()) {
            sql.execute(statement);
        } catch (SQLException e) {
            throw new IllegalStateException("Shared test Postgres: " + statement, e);
        }
    }

    private static String url(String database) {
        return "jdbc:postgresql://" + server.getHost() + ":"
                + server.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + database;
    }

    /** The Flyway settings that decide what a template holds; one template per distinct value. */
    private record Migrations(List<String> locations, String defaultSchema, List<String> schemas) {
        static Migrations of(Environment environment) {
            var flyway = Binder.get(environment).bind("spring.flyway", FlywayProperties.class)
                    .orElseGet(FlywayProperties::new);
            return new Migrations(List.copyOf(flyway.getLocations()), flyway.getDefaultSchema(),
                    List.copyOf(flyway.getSchemas()));
        }
    }

    /**
     * One context's database. Spring calls {@link #close()} when the context closes, which drops it.
     */
    static final class TestDatabase implements JdbcConnectionDetails, AutoCloseable {

        private final String name;

        private TestDatabase(String name) {
            this.name = name;
        }

        @Override
        public String getJdbcUrl() {
            return url(name);
        }

        @Override
        public String getUsername() {
            return USER;
        }

        @Override
        public String getPassword() {
            return PASSWORD;
        }

        @Override
        public void close() {
            synchronized (SharedPostgres.class) {
                drop(name);
            }
        }
    }
}
