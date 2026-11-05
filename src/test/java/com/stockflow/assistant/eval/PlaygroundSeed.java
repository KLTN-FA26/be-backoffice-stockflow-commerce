package com.stockflow.assistant.eval;

import com.stockflow.catalog.internal.service.CatalogProjectionService;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.consumption.PickConsumption;
import com.stockflow.procurement.api.SaveSupplierItemCommand;
import com.stockflow.procurement.api.SupplierItemService;
import com.stockflow.procurement.internal.service.GoodsReceipts;
import com.stockflow.support.DemoData;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
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

import java.math.BigDecimal;
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
 * needs on top — a month of sales for the reorder proposals, supplier terms, open purchase orders, the demo customer's
 * price, credit terms and receivables, a pick task for the playground's picker, and one sign-in account per role.
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
 * <p><b>Only the playground's database.</b> It creates SYSTEM_ADMIN and nine more accounts that share one password,
 * and reactivates them on every run, so it must never reach the database the other branches develop against,
 * application.yml's default {@code localhost:5432/stockflow}. The datasource defaults to the playground's own
 * ({@code 127.0.0.1:55432/stockflow_ai}), and {@link #refuseAnyOtherDatabase} stops a run whose {@code DB_NAME} names
 * another one before the context starts — starting it already runs Flyway, demo seed included, against its database.
 * {@code -Dplayground.database=<name>} allows one more, by name.</p>
 */
@Tag("playground")
@EnabledIfSystemProperty(named = "playground.seed", matches = "true")
@SpringBootTest(properties = {"stockflow.assistant.enabled=false",
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
            new Account("play.planner", "Kế hoạch tồn kho (playground)", "INVENTORY_PLANNER", List.of()),
            new Account("play.kho.hcm", "Quản lý kho HCM (playground)", "WAREHOUSE_MANAGER", List.of("HCM")),
            new Account("play.sales", "Nhân viên bán hàng (playground)", "SALES_STAFF", List.of()),
            new Account("play.muahang", "Nhân viên mua hàng (playground)", "PROCUREMENT_STAFF", List.of()),
            new Account("play.dieuphoi", "Điều phối đơn hàng (playground)", "ORDER_COORDINATOR", List.of()),
            new Account("play.ketoan", "Kế toán (playground)", "ACCOUNTANT", List.of()),
            new Account("play.qc", "Kiểm tra chất lượng HCM (playground)", "QC_STAFF", List.of("HCM")),
            // The floor roles may open the assistant since V20261105000500.
            new Account("play.nvkho.hcm", "Nhân viên kho HCM (playground)", "WAREHOUSE_STAFF", List.of("HCM")),
            new Account("play.xuong.hcm", "Nhân viên xưởng in HCM (playground)", "PRODUCTION_STAFF", List.of("HCM")));

    /** Units sold per day for the reorder proposals; the golden fixtures put one day only. */
    private static final List<Object[]> DAILY_SALES = List.of(
            new Object[] {"EVAL-CUP-12OZ", 45, 1_350_000},
            new Object[] {"EVAL-CUP-16OZ", 25, 875_000},
            new Object[] {"EVAL-LID-90", 15, 150_000},
            new Object[] {"EVAL-BOX-A4", 8, 320_000});

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired GoodsReceipts receipts;
    @Autowired InventoryService inventory;
    @Autowired PickConsumption consumption;
    @Autowired CatalogProjectionService catalog;
    @Autowired SupplierItemService supplierItems;
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

        new GoldenFixtures(jdbc, tx, entityManager, receipts, inventory, consumption, catalog).ensure();
        sales(today);
        supplierTerms();
        purchaseOrders(today);
        ACCOUNTS.forEach(account -> account(account, password));
        customerTerms();
        pickerTask();

        System.out.println("Playground seed: " + jdbc.queryForObject(
                "select count(*) from product.variants where sku like 'EVAL-%'", Long.class) + " EVAL SKUs, "
                + jdbc.queryForObject("select count(*) from procurement.purchase_orders where po_number like "
                + "'PO-PLAY-%'", Long.class) + " playground POs, " + ACCOUNTS.size() + " accounts");
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

    /** The demo supplier Xưởng gỗ Việt (GOVIET) sells three EVAL SKUs, preferred, with MOQ, pack and lead time. */
    private void supplierTerms() {
        UUID supplier = jdbc.queryForObject("select id from procurement.suppliers where code = 'GOVIET'", UUID.class);
        List<SaveSupplierItemCommand> terms = List.of(
                new SaveSupplierItemCommand("EVAL-CUP-12OZ", "GV-C12", new BigDecimal("1800"), 500, 50, 14, true, null),
                new SaveSupplierItemCommand("EVAL-CUP-16OZ", "GV-C16", new BigDecimal("2100"), 300, 50, 10, true, null),
                new SaveSupplierItemCommand("EVAL-LID-90", "GV-L90", new BigDecimal("400"), 1000, 100, 21, true, null));
        for (SaveSupplierItemCommand term : terms) {
            if (supplierItems.suppliersOf(term.sku()).isEmpty()) {
                supplierItems.create(supplier, term);
            }
        }
    }

    /** One of each kind the reorder proposal counts differently: confirmed (on order), pending (reported), draft. */
    private void purchaseOrders(LocalDate today) {
        order("PO-PLAY-0001", "CONFIRMED", today.plusDays(7), "EVAL-CUP-12OZ", 500);
        order("PO-PLAY-0002", "PENDING_APPROVAL", today.plusDays(14), "EVAL-LID-90", 1000);
        order("PO-PLAY-0003", "DRAFT", today.plusDays(10), "EVAL-CUP-16OZ", 300);
    }

    private void order(String number, String status, LocalDate expected, String sku, int quantity) {
        if (jdbc.queryForObject("select count(*) from procurement.purchase_orders where po_number = ?", Long.class,
                number) > 0) {
            return;
        }
        UUID po = Identifiers.newId();
        UUID revision = Identifiers.newId();
        boolean submitted = !status.equals("DRAFT");
        boolean approved = status.equals("CONFIRMED");
        tx.executeWithoutResult(s -> {
            UUID submitter = ReferenceRows.user(entityManager);
            UUID approver = ReferenceRows.user(entityManager);
            jdbc.update("""
                    INSERT INTO procurement.purchase_orders (id, po_number, status, supplier_id, warehouse_id, currency,
                        order_date, expected_date, submitted_at, submitted_by, approved_at, approved_by, confirmed_at,
                        confirmed_by, active_revision_id, version, created_at)
                    SELECT ?, ?, ?, s.id, w.id, 'VND', CURRENT_DATE, ?, CASE WHEN ? THEN NOW() END, ?,
                        CASE WHEN ? THEN NOW() END, ?, CASE WHEN ? THEN NOW() END, ?, ?, 0, NOW()
                      FROM procurement.suppliers s, warehouse.warehouse w WHERE s.code = 'GOVIET' AND w.prefix = 'HCM'""",
                    po, number, status, Date.valueOf(expected), submitted, submitted ? submitter : null,
                    approved, approved ? approver : null, approved, approved ? approver : null,
                    status.equals("DRAFT") ? null : revision);
            if (!status.equals("DRAFT")) {
                jdbc.update("""
                        INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header,
                            changed_by, version, created_at) VALUES (?, ?, 0, 'INITIAL', '{}'::jsonb, ?, 0, NOW())""",
                        revision, po, submitter);
            }
            // The same columns as OnOrderQueriesIntegrationTest's orders: the amounts keep their defaults, which every
            // line check accepts; the playground asks about quantities, not money.
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_lines (id, po_id, po_revision_id, line_no, inventory_item_id,
                        ordered_qty, unit_price, version, created_at)
                    SELECT ?, ?, ?, 1, id, ?, 1800, 0, NOW() FROM inventory.inventory_items WHERE sku = ?""",
                    Identifiers.newId(), po, status.equals("DRAFT") ? null : revision, quantity, sku);
        });
    }

    /**
     * What the customer tools answer about the demo customer: its own price for EVAL-CUP-12OZ in three tiers, its
     * terms (prepaid, a 30% deposit paid off before delivery, or credit up to 200 million for 30 days), approved by the
     * playground's admin, and two receivables it pays itself, one of them overdue.
     */
    private void customerTerms() {
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO catalog.customer_price (id, customer_id, sku, currency, version, created_at)
                    VALUES (?, ?, 'EVAL-CUP-12OZ', 'VND', 0, NOW()) ON CONFLICT (customer_id, sku) DO NOTHING""",
                    Identifiers.newId(), DemoData.CUSTOMER_ID);
            UUID price = jdbc.queryForObject("select id from catalog.customer_price where customer_id = ? and sku = "
                    + "'EVAL-CUP-12OZ'", UUID.class, DemoData.CUSTOMER_ID);
            for (int[] tier : new int[][] {{1_000, 1_500}, {5_000, 1_400}, {10_000, 1_300}}) {
                jdbc.update("""
                        INSERT INTO catalog.customer_price_tier (id, customer_price_id, min_quantity, unit_price,
                            version, created_at) VALUES (?, ?, ?, ?, 0, NOW())
                        ON CONFLICT (customer_price_id, min_quantity) DO NOTHING""",
                        Identifiers.newId(), price, tier[0], tier[1]);
            }
            jdbc.update("""
                    INSERT INTO customer.credit_profile (id, customer_id, allow_prepaid, allow_deposit, allow_credit,
                        default_payment_term, deposit_percent, deposit_balance_policy, credit_limit, credit_term_days,
                        currency, approved_by, approved_at, version, created_at)
                    SELECT ?, ?, TRUE, TRUE, TRUE, 'CREDIT', 30, 'BEFORE_DELIVERY', 200000000, 30, 'VND', u.id, NOW(),
                        0, NOW()
                      FROM identity.app_user u WHERE u.username = 'play.admin'
                    ON CONFLICT (customer_id) DO NOTHING""", Identifiers.newId(), DemoData.CUSTOMER_ID);
        });
        if (jdbc.queryForObject("select count(*) from payment.receivable where customer_id = ?", Long.class,
                DemoData.CUSTOMER_ID) == 0) {
            receivable("SO-20991231-900101", "OVERDUE", 45_000_000, 15_000_000, -10);
            receivable("SO-20991231-900102", "OPEN", 30_000_000, 0, 20);
        }
    }

    /**
     * A receivable on a completed credit order of its own, due {@code dueInDays} from today. The order gets a number
     * and an address the order tools can read: the reference row has neither, and one unreadable order would fail
     * every list it appears in.
     */
    private void receivable(String orderNumber, String status, long amount, long paid, int dueInDays) {
        tx.executeWithoutResult(s -> {
            UUID order = ReferenceRows.orderWithLine(entityManager, "EVAL-CUP-12OZ", "COMPLETED",
                    DemoData.CUSTOMER_ID, null, null);
            entityManager.flush();
            jdbc.update("""
                    UPDATE ordering.customer_order SET order_number = ?, shipping_recipient_name = 'Khách hàng demo',
                        shipping_phone = '0903123456', shipping_line1 = '1 Lê Lợi', shipping_ward_code = '26734',
                        shipping_ward_name = 'Phường Bến Nghé', shipping_province_code = '79',
                        shipping_province_name = 'Thành phố Hồ Chí Minh', shipping_country_code = 'VN'
                     WHERE id = ?""", orderNumber, order);
            jdbc.update("""
                    INSERT INTO payment.receivable (id, order_id, customer_id, kind, amount, paid_amount, currency,
                        issued_at, due_date, status, collector)
                    VALUES (?, ?, ?, 'CREDIT_ORDER', ?, ?, 'VND', NOW() - INTERVAL '40 days', CURRENT_DATE + ?, ?,
                        'CUSTOMER')""",
                    Identifiers.newId(), order, DemoData.CUSTOMER_ID, amount, paid, dueInDays, status);
        });
    }

    /**
     * The held order's pick task goes to the playground's picker, so "my tasks" has one to show and the picker may
     * read its short line, as fulfillment lets an assignee.
     */
    private void pickerTask() {
        // In a transaction, as every write here: the pool does not auto-commit, so a bare update would be rolled back.
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE fulfillment.pick p SET assigned_user_id = u.id
                  FROM identity.app_user u
                 WHERE u.username = 'play.nvkho.hcm' AND p.order_number = 'SO-20991231-900001'
                   AND p.assigned_user_id IS DISTINCT FROM u.id"""));
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
