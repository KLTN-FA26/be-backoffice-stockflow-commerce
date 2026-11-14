package com.stockflow.invoicing.internal.service;

import com.stockflow.common.security.DataScope;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.ShipmentHandover;
import com.stockflow.support.Await;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.ShipmentRows;
import com.stockflow.support.StaffFixtures;
import com.stockflow.support.TestUsers;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WF-05 E5 (plan §1.4 "Bị từ chối hoặc lỗi thì báo kế toán") against the real database, the real identity matrix and
 * the sandbox provider: the people who may issue e-invoices - whoever holds {@code einvoices:APPROVE}, here an
 * ACCOUNTANT account - find in their inbox an invoice the provider refused, one stuck SUBMITTING for longer than
 * {@code stockflow.invoicing.stuck-after}, and one close to its deadline. Each is said once per submission; a Sales
 * account, which only reads invoices, hears none of it.
 *
 * <p>The notices are posted by listeners after the invoicing transaction commits, so they are awaited. The sweeps do
 * not run on their own in tests (application-test.yml); they are called here. Time is moved by moving the invoice's
 * {@code submitted_at} / {@code issue_due_at}, never by sleeping.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class EInvoiceAccountantNoticesIntegrationTest {

    private static final Duration ASYNC = Duration.ofSeconds(20);
    private static final long PRICE = 2_000;

    @Autowired OrderService orders;
    @Autowired EInvoiceDrafts drafts;
    @Autowired EInvoiceIssuance issuance;
    @Autowired EInvoiceQueryService queries;
    @Autowired EInvoiceJobs jobs;
    @Autowired com.stockflow.inventory.api.InventoryService inventory;
    @Autowired ApplicationContext context;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private ConfirmedOrders confirmedOrders;
    private UUID customer;
    private UUID staff;
    private UUID accountant;
    private UUID salesStaff;
    private String taxCode;
    private CustomerSkuRows.Issued sku;
    private final List<UUID> placed = new ArrayList<>();

    @BeforeEach
    void anAccountantAndACustomer() {
        confirmedOrders = new ConfirmedOrders(context);
        StaffFixtures people = new StaffFixtures(jdbc, tx, entityManager);
        accountant = people.staff("ACCOUNTANT");
        salesStaff = people.staff("SALES_STAFF");
        customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        staff = tx.execute(s -> ReferenceRows.user(entityManager));
        taxCode = "03" + (10_000_000 + ThreadLocalRandom.current().nextInt(89_999_999));
        confirmedOrders.legalProfile(customer);
        buyer(taxCode, "ke.toan@" + customer + ".example.com");
        sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customer, 50, "STANDARD", true);
        CustomerSkuRows.stock(jdbc, tx, sku.sku(), 500);
    }

    @AfterEach
    void putTheStockBack() {
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> placed.forEach(id -> jdbc.queryForList(
                "SELECT id FROM inventory.stock_reservation WHERE order_id = ? AND status = 'HELD'", UUID.class, id)
                .forEach(reservation -> inventory.release(reservation, "MANUAL_OVERRIDE"))));
    }

    private void buyer(String taxCode, String invoiceEmail) {
        tx.executeWithoutResult(s -> {
            // A tax code belongs to one customer (uk_customer_tax_code): the sandbox's refused one is taken back from
            // whichever earlier test customer was given it.
            jdbc.update("UPDATE customer.customer SET tax_code = NULL WHERE tax_code = ? AND id <> ?", taxCode, customer);
            jdbc.update("UPDATE customer.customer SET tax_code = ? WHERE id = ?", taxCode, customer);
            jdbc.update("UPDATE customer.customer_legal_profile SET invoice_email = ? WHERE customer_id = ?",
                    invoiceEmail, customer);
        });
    }

    /** One carton ordered, paid, released, packed, handed over and delivered; the draft the listener drew up. */
    private EInvoice.Snapshot draft() {
        ConfirmedOrders.Confirmed confirmed = confirmedOrders.confirmed(customer,
                ConfirmedOrders.Line.cartons(sku, 1, PRICE).withTax(BigDecimal.TEN));
        placed.add(confirmed.orderId());
        confirmedOrders.release(confirmed.orderId(), DemoData.WAREHOUSE_HCM, staff);
        orders.startPicking(confirmed.orderId(), staff);
        OrderSummary order = orders.markPacked(confirmed.orderId(), staff);
        UUID shipment = ShipmentRows.ready(jdbc, tx, order.orderId(), UUID.randomUUID());
        orders.recordHandover(order.orderId(), new ShipmentHandover(shipment,
                List.of(new ShipmentHandover.Line(order.lines().getFirst().lineId(), 50)), Instant.now(), staff));
        orders.recordShipmentDelivered(order.orderId(), shipment, staff);
        Await.until("the draft e-invoice of shipment " + shipment, ASYNC, () -> jdbc.queryForObject(
                "SELECT count(*) FROM invoicing.einvoice WHERE shipment_id = ?", Long.class, shipment) == 1);
        return queries.get(jdbc.queryForObject("SELECT id FROM invoicing.einvoice WHERE shipment_id = ?", UUID.class,
                shipment));
    }

    /** What a person's inbox holds about one invoice, in a category, oldest first. */
    private List<Map<String, Object>> notices(UUID recipient, UUID invoice, String category) {
        return jdbc.queryForList("""
                SELECT severity, title, body, link, dedupe_key FROM notification.user_notification
                 WHERE recipient_id = ? AND source_id = ? AND category = ? ORDER BY created_at, id""", recipient,
                invoice.toString(), category);
    }

    private long noticesOfAnyKind(UUID recipient, UUID invoice) {
        return jdbc.queryForObject("SELECT count(*) FROM notification.user_notification WHERE recipient_id = ? AND "
                + "source_id = ?", Long.class, recipient, invoice.toString());
    }

    @Test
    @DisplayName("REJECTED: the accountant is told at once, critically, with the provider's code; refused again after "
            + "being sent again, told again; an invoice that is issued raises nothing")
    void aRefusalReachesTheAccountant() {
        buyer("0000000001", "ke.toan@" + customer + ".example.com");
        EInvoice.Snapshot draft = draft();

        EInvoice.Snapshot rejected = FirstSend.issue(issuance, queries, draft.id(), draft.version(), accountant);
        assertThat(rejected.status()).isEqualTo(EInvoiceStatus.REJECTED);

        Await.until("the accountant hears of the refusal", ASYNC,
                () -> notices(accountant, draft.id(), "EINVOICE_ATTENTION").size() == 1);
        Map<String, Object> notice = notices(accountant, draft.id(), "EINVOICE_ATTENTION").getFirst();
        assertThat(notice.get("severity")).isEqualTo("CRITICAL");
        assertThat((String) notice.get("title")).contains(draft.code()).contains("bị từ chối");
        assertThat((String) notice.get("body")).contains(draft.code(), draft.orderNumber(), "INVALID_BUYER_TAX_CODE");
        assertThat(notice.get("link")).isEqualTo("/invoicing/einvoices/" + draft.id());
        assertThat((String) notice.get("dedupe_key")).startsWith("einvoice-rejected:" + draft.id());
        assertThat((String) notice.get("body")).as("the provider's sentence stays on the invoice")
                .doesNotContain(rejected.rejectionMessage());

        // Sent again as it stands, refused again: a new submission, a new notice.
        EInvoice.Snapshot reopened = drafts.reopen(draft.id(), rejected.version());
        assertThat(FirstSend.issue(issuance, queries, draft.id(), reopened.version(), accountant).status())
                .isEqualTo(EInvoiceStatus.REJECTED);
        Await.until("the second refusal is announced", ASYNC,
                () -> notices(accountant, draft.id(), "EINVOICE_ATTENTION").size() == 2);
        assertThat(notices(accountant, draft.id(), "EINVOICE_ATTENTION")).extracting(row -> row.get("dedupe_key"))
                .doesNotHaveDuplicates();

        // Corrected and issued: nothing more to hear about it.
        EInvoice.Snapshot again = drafts.reopen(draft.id(), queries.get(draft.id()).version());
        EInvoice.Snapshot corrected = drafts.revise(new EInvoiceDrafts.Revise(draft.id(), again.version(), null,
                taxCode, null, null, null));
        assertThat(FirstSend.issue(issuance, queries, draft.id(), corrected.version(), accountant).status())
                .isEqualTo(EInvoiceStatus.ISSUED);
        Await.until("the issue is published and handled", ASYNC, () -> jdbc.queryForObject("""
                SELECT count(*) FROM event_publication WHERE completion_date IS NULL AND serialized_event LIKE ?""",
                Long.class, "%" + draft.id() + "%") == 0);
        assertThat(notices(accountant, draft.id(), "EINVOICE_ATTENTION")).hasSize(2);

        assertThat(noticesOfAnyKind(salesStaff, draft.id())).as("Sales staff do not issue invoices").isZero();
    }

    @Test
    @DisplayName("stuck SUBMITTING: not announced while the provider may still answer; past stuck-after the accountant "
            + "is warned once per submission, the invoice untouched and still asked about")
    void aStuckSubmissionReachesTheAccountantOnce() {
        buyer(taxCode, "ke.toan+sandbox-unreachable@" + customer + ".example.com");
        EInvoice.Snapshot draft = draft();
        EInvoice.Snapshot waiting = FirstSend.issue(issuance, queries, draft.id(), draft.version(), accountant);
        assertThat(waiting.status()).isEqualTo(EInvoiceStatus.SUBMITTING);

        jobs.warnStuck();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoicing.einvoice_stuck_notice WHERE einvoice_id = ?",
                Long.class, draft.id())).as("sent a moment ago: the provider may still answer").isZero();

        tx.executeWithoutResult(s -> jdbc.update("UPDATE invoicing.einvoice SET submitted_at = NOW() - interval "
                + "'31 minutes' WHERE id = ?", draft.id()));
        assertThat(jobs.warnStuck()).isGreaterThanOrEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT transaction_id FROM invoicing.einvoice_stuck_notice WHERE einvoice_id "
                + "= ?", String.class, draft.id())).isEqualTo(waiting.transactionId());
        Await.until("the accountant hears of the stuck submission", ASYNC,
                () -> notices(accountant, draft.id(), "EINVOICE_ATTENTION").size() == 1);
        Map<String, Object> notice = notices(accountant, draft.id(), "EINVOICE_ATTENTION").getFirst();
        assertThat(notice.get("severity")).isEqualTo("WARN");
        assertThat((String) notice.get("title")).contains(draft.code()).contains("chưa có kết quả");
        assertThat((String) notice.get("body")).contains(draft.orderNumber());
        assertThat((String) notice.get("dedupe_key")).startsWith("einvoice-stuck:" + draft.id());

        // The sweep again, as often as it runs: nothing new, and the invoice is as it was.
        jobs.warnStuck();
        jobs.warnOfDeadlines();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoicing.einvoice_stuck_notice WHERE einvoice_id = ?",
                Long.class, draft.id())).isEqualTo(1);
        EInvoice.Snapshot still = queries.get(draft.id());
        assertThat(still.status()).isEqualTo(EInvoiceStatus.SUBMITTING);
        assertThat(still.transactionId()).isEqualTo(waiting.transactionId());
        assertThat(still.version()).as("the announcement does not touch the invoice").isEqualTo(waiting.version());

        // The reconciliation went on asking; the provider is back and the invoice is issued under the same transaction.
        EInvoice.Snapshot issued = issuance.reconcile(draft.id());
        assertThat(issued.status()).isEqualTo(EInvoiceStatus.ISSUED);
        assertThat(issued.transactionId()).isEqualTo(waiting.transactionId());
        jobs.warnStuck();
        Await.until("the issue is published and handled", ASYNC, () -> jdbc.queryForObject("""
                SELECT count(*) FROM event_publication WHERE completion_date IS NULL AND serialized_event LIKE ?""",
                Long.class, "%" + draft.id() + "%") == 0);
        assertThat(notices(accountant, draft.id(), "EINVOICE_ATTENTION")).as("told once").hasSize(1);
        assertThat(noticesOfAnyKind(salesStaff, draft.id())).isZero();
    }

    @Test
    @DisplayName("deadline approaching: the accountant is warned once about a draft inside the warning lead, not "
            + "about one whose deadline is days away")
    void anApproachingDeadlineReachesTheAccountantOnce() {
        EInvoice.Snapshot far = draft();
        EInvoice.Snapshot near = draft();
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE invoicing.einvoice SET issue_due_at = NOW() + interval '3 days' WHERE id = ?",
                    far.id());
            jdbc.update("UPDATE invoicing.einvoice SET issue_due_at = NOW() + interval '2 hours' WHERE id = ?",
                    near.id());
        });

        assertThat(jobs.warnDue()).isGreaterThanOrEqualTo(1);

        Await.until("the accountant hears of the deadline", ASYNC,
                () -> notices(accountant, near.id(), "EINVOICE_DUE").size() == 1);
        Map<String, Object> notice = notices(accountant, near.id(), "EINVOICE_DUE").getFirst();
        assertThat(notice.get("severity")).isEqualTo("WARN");
        assertThat((String) notice.get("title")).contains(near.code()).contains("phải phát hành trước");
        assertThat((String) notice.get("body")).contains(near.orderNumber());
        assertThat(notice.get("link")).isEqualTo("/invoicing/einvoices/" + near.id());

        jobs.warnDue();
        jobs.warnOfDeadlines();
        Await.until("the sweeps' publications are handled", ASYNC, () -> jdbc.queryForObject("""
                SELECT count(*) FROM event_publication WHERE completion_date IS NULL AND serialized_event LIKE ?""",
                Long.class, "%" + near.id() + "%") == 0);
        assertThat(notices(accountant, near.id(), "EINVOICE_DUE")).as("told once").hasSize(1);
        assertThat(noticesOfAnyKind(accountant, far.id())).as("three days ahead: nothing to say yet").isZero();
        assertThat(noticesOfAnyKind(salesStaff, near.id())).isZero();
        assertThat(queries.get(near.id()).status()).as("a warning changes nothing").isEqualTo(EInvoiceStatus.DRAFT);
    }
}
