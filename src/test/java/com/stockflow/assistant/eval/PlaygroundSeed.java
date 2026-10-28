package com.stockflow.assistant.eval;

import com.stockflow.catalog.internal.service.CatalogProjectionService;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.consumption.PickConsumption;
import com.stockflow.procurement.api.SaveSupplierItemCommand;
import com.stockflow.procurement.api.SupplierItemService;
import com.stockflow.procurement.internal.service.GoodsReceipts;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seeds a developer's own running database for the assistant playground ({@code stockflow-ai-playground}, next to
 * the repositories): everything the golden set asks about ({@link GoldenFixtures}), plus what a hand-run session
 * needs on top — a month of sales for the reorder proposals, supplier terms, open purchase orders, and one sign-in
 * account per role.
 *
 * <p><b>Not part of {@code mvn test}:</b> it writes into the database the {@code local} profile points at, so it runs
 * only when asked for, and every step is idempotent (a second run changes nothing but the accounts' password):</p>
 * <pre>
 * DB_NAME=stockflow_ai mvn test -Dtest=PlaygroundSeed -Dplayground.seed=true -Dplayground.password=...
 * </pre>
 * <p>It goes through the same services and SQL the golden set's fixtures use, so the rows pass every trigger and
 * check a hand-written SQL file would have to imitate.</p>
 */
@Tag("playground")
@EnabledIfSystemProperty(named = "playground.seed", matches = "true")
@SpringBootTest(properties = "stockflow.assistant.enabled=false")
@ActiveProfiles("local")
class PlaygroundSeed {

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
            new Account("play.qc", "Kiểm tra chất lượng HCM (playground)", "QC_STAFF", List.of("HCM")));

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

    @Test
    void seed() {
        String password = System.getProperty("playground.password", "");
        assertThat(password).as("pass -Dplayground.password=... (12+ characters)").hasSizeGreaterThanOrEqualTo(12);
        LocalDate today = LocalDate.now(GoldenFixtures.ZONE);

        new GoldenFixtures(jdbc, tx, entityManager, receipts, inventory, consumption, catalog).ensure();
        sales(today);
        supplierTerms();
        purchaseOrders(today);
        ACCOUNTS.forEach(account -> account(account, password));

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
