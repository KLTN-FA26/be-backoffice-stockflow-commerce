package com.stockflow.assistant.eval;

import com.stockflow.common.id.Identifiers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seeds a developer's own running database for the assistant playground ({@code stockflow-ai-playground}, next to
 * the repositories): everything the golden set asks about ({@link GoldenFixtures}), plus what a hand-run session
 * needs on top — a month of sales, a pick task for the playground's
 * picker, and one sign-in account per role of the rescope (ADR-0010).
 *
 * <p><b>Not part of {@code mvn test}:</b> it writes into a real database, so it runs only when asked for, and every
 * step is idempotent (a second run changes nothing but the accounts' password). The playground's
 * {@code scripts\seed.ps1} runs it with the database settings of {@code scripts\env.ps1}:</p>
 * <pre>
 * mvn test -Dtest=PlaygroundSeed -Dplayground.seed=true -Dplayground.password=...
 * </pre>
 * <p>The password may come from the environment variable {@code PLAYGROUND_PASSWORD} instead.</p>
 * <p>It goes through the same services and SQL the golden set's fixtures use, so the rows pass every trigger and
 * check a hand-written SQL file would have to imitate.</p>
 *
 * <p><b>Only the playground's database.</b> It creates SYSTEM_ADMIN and six more accounts that share one password,
 * and reactivates them on every run, so it must never reach the database the other branches develop against,
 * application.yml's default {@code localhost:5432/stockflow}. The datasource defaults to the playground's own
 * ({@code 127.0.0.1:55432/stockflow_ai}), and {@link #refuseAnyOtherDatabase} stops a run whose {@code DB_NAME} names
 * another one before the context starts — starting it already runs Flyway, demo seed included, against its database.
 * {@code -Dplayground.database=<name>} allows one more, by name.</p>
 */
@Tag("playground")
@EnabledIfSystemProperty(named = "playground.seed", matches = "true")
@SpringBootTest(properties = {"stockflow.assistant.enabled=false", "stockflow.upload-inspection.enabled=false",
        "spring.datasource.url=jdbc:postgresql://${DB_HOST:127.0.0.1}:${DB_PORT:55432}/${DB_NAME:"
                + PlaygroundSeed.PLAYGROUND_DATABASE + "}"})
@ActiveProfiles("local")
class PlaygroundSeed {

    /** The database of the playground's docker-compose.yml. */
    static final String PLAYGROUND_DATABASE = "stockflow_ai";

    /** One account per role a staff member signs in with; the warehouse roles are limited to HCM. */
    record Account(String username, String fullName, String role, List<String> warehouses) {
    }

    static final List<Account> ACCOUNTS = List.of(
            new Account("play.admin", "Quản trị hệ thống (playground)", "SYSTEM_ADMIN", List.of()),
            new Account("play.kho.hcm", "Quản lý kho HCM (playground)", "WAREHOUSE_MANAGER", List.of("HCM")),
            new Account("play.nvkho.hcm", "Nhân viên kho HCM (playground)", "WAREHOUSE_STAFF", List.of("HCM")),
            new Account("play.sales", "Nhân viên bán hàng (playground)", "SALES_STAFF", List.of()),
            new Account("play.qlsales", "Quản lý bán hàng (playground)", "SALES_MANAGER", List.of()),
            new Account("play.dieuphoi", "Điều phối sản xuất (playground)", "PRODUCTION_COORDINATOR", List.of()),
            new Account("play.ketoan", "Kế toán (playground)", "ACCOUNTANT", List.of()));

    /** Units sold per day, for questions about sales over a month; the golden fixtures put one day only. */
    private static final List<Object[]> DAILY_SALES = List.of(
            new Object[] {"EVAL-CUP-12OZ", 45, 1_350_000},
            new Object[] {"EVAL-CUP-16OZ", 25, 875_000},
            new Object[] {"EVAL-LID-90", 15, 150_000},
            new Object[] {"EVAL-BOX-A4", 8, 320_000});

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired PasswordEncoder passwords;

    /**
     * Static, so it runs before the test instance is created, and with the instance the Spring context, whose start
     * runs Flyway against the database. A check in {@link #seed()} alone would stop the accounts and rows but not the
     * migration.
     */
    @BeforeAll
    static void refuseAnyOtherDatabase() {
        String database = targetDatabase(System.getProperties(), System.getenv());
        if (!allowed(database, System.getProperty("playground.database"))) {
            throw new IllegalStateException("Refusing to seed database '" + database + "': PlaygroundSeed writes "
                    + "only into the playground's " + PLAYGROUND_DATABASE + ". Run it through the playground's "
                    + "scripts\\seed.ps1, or pass -Dplayground.database=" + database + " if that database is "
                    + "really meant to get the playground accounts.");
        }
        System.out.println("Playground seed: target database " + database);
    }

    /**
     * The database {@code spring.datasource.url} above resolves to, read as Spring reads {@code DB_NAME}: a system
     * property before an environment variable, the playground's when neither is set. A blank value is kept: Spring
     * would put it in the URL, and the driver would then connect to the database named after the user.
     */
    static String targetDatabase(Properties system, Map<String, String> environment) {
        String name = system.getProperty("DB_NAME", environment.get("DB_NAME"));
        return name == null ? PLAYGROUND_DATABASE : name;
    }

    /** The playground's database, or the one {@code -Dplayground.database} names. */
    static boolean allowed(String database, String override) {
        return PLAYGROUND_DATABASE.equals(database) || override != null && !override.isBlank()
                && override.equals(database);
    }

    @Test
    void seed() {
        // The same check against the connection itself: what the URL resolved to, whatever set it.
        String database = jdbc.queryForObject("select current_database()", String.class);
        System.out.println("Playground seed: writing into database " + database);
        assertThat(allowed(database, System.getProperty("playground.database")))
                .as("the connected database %s is not the playground's", database).isTrue();
        // The environment as well: the playground's seed.ps1 keeps the password off the command line.
        String password = System.getProperty("playground.password",
                System.getenv().getOrDefault("PLAYGROUND_PASSWORD", "").strip());
        assertThat(password).as("pass -Dplayground.password=..., or set PLAYGROUND_PASSWORD (12+ characters)")
                .hasSizeGreaterThanOrEqualTo(12);
        LocalDate today = LocalDate.now(GoldenFixtures.ZONE);

        // Accounts first: the held order's pick is written already assigned to the playground's picker.
        ACCOUNTS.forEach(account -> account(account, password));
        UUID picker = jdbc.queryForObject("select id from identity.app_user where username = 'play.nvkho.hcm'",
                UUID.class);
        new GoldenFixtures(context).pickedBy(picker).ensure();
        sales(today);

        System.out.println("Playground seed: " + jdbc.queryForObject(
                "select count(*) from product.customer_skus where sku like 'EVAL-%'", Long.class) + " EVAL SKUs, "
                + ACCOUNTS.size() + " accounts");
    }

    /** Days 2..30 back: with the fixtures' yesterday, a full month for every SKU of {@link #DAILY_SALES}. */
    private void sales(LocalDate today) {
        tx.executeWithoutResult(s -> {
            for (int back = 1; back <= 30; back++) {
                LocalDate day = today.minusDays(back);
                long revenue = 0;
                for (Object[] row : DAILY_SALES) {
                    // A little week-to-week variation, so a trend question has something to see.
                    int units = (int) row[1] + (back % 7 == 0 ? 10 : back % 3);
                    revenue += (long) (int) row[2] / (int) row[1] * units;
                    jdbc.update("""
                            INSERT INTO reporting.product_sales_summary (id, sku, period, quantity_sold, revenue, version,
                                created_at) VALUES (?, ?, ?, ?, ?, 0, NOW()) ON CONFLICT (sku, period) DO NOTHING""",
                            Identifiers.newId(), row[0], day.toString(), units, (long) (int) row[2] / (int) row[1] * units);
                }
                jdbc.update("""
                        INSERT INTO reporting.sales_daily_summary (id, day, orders_count, revenue, currency, version,
                            created_at) VALUES (?, ?, ?, ?, 'VND', 0, NOW()) ON CONFLICT DO NOTHING""",
                        Identifiers.newId(), Date.valueOf(day), 3 + back % 4, revenue);
            }
        });
    }

    /** Creates the account, or resets its password; roles and warehouses are only ever added. */
    private void account(Account account, String password) {
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO identity.app_user (id, username, email, password_hash, full_name, status, version,
                        created_at, created_by)
                    VALUES (?, ?, ?, ?, ?, 'ACTIVE', 0, NOW(), 'playground')
                    ON CONFLICT (username) DO UPDATE SET password_hash = EXCLUDED.password_hash, status = 'ACTIVE',
                        version = identity.app_user.version + 1, last_modified_at = NOW(),
                        last_modified_by = 'playground'""",
                    Identifiers.newId(), account.username(), account.username() + "@playground.local",
                    passwords.encode(password), account.fullName());
            jdbc.update("""
                    INSERT INTO identity.user_role (id, user_id, role_id, version, created_at, created_by)
                    SELECT gen_random_uuid(), u.id, r.id, 0, NOW(), 'playground'
                      FROM identity.app_user u JOIN identity.app_role r ON r.code = ?
                     WHERE u.username = ?
                    ON CONFLICT (user_id, role_id) DO NOTHING""", account.role(), account.username());
            for (String prefix : account.warehouses()) {
                jdbc.update("""
                        INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at, created_by)
                        SELECT gen_random_uuid(), u.id, w.id, 0, NOW(), 'playground'
                          FROM identity.app_user u JOIN warehouse.warehouse w ON w.prefix = ?
                         WHERE u.username = ?
                        ON CONFLICT (user_id, warehouse_id) DO NOTHING""", prefix, account.username());
            }
        });
    }
}
