package com.stockflow.invoicing.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.contracts.OrderDelivered;
import com.stockflow.contracts.ReturnResolved;
import com.stockflow.contracts.ShipmentStatusChanged;
import com.stockflow.invoicing.internal.domain.CorrectionBasis;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceLine;
import com.stockflow.invoicing.internal.domain.EInvoiceQueries;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.invoicing.internal.domain.EInvoiceType;
import com.stockflow.invoicing.internal.domain.ProviderCallLog.Operation;
import com.stockflow.invoicing.internal.domain.ProviderCallLog.Outcome;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
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

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * WF-05 E2 against the real database, the real order module and the sandbox provider (plan §1.3-§1.5; kltn-docs 21):
 * a shipment delivered through {@link OrderService} gets its draft from the listener, the accountant issues it, and
 * the sandbox answers as a provider does - including the answers that go wrong.
 *
 * <p>Every order is a real one: its contract drawn up, signed and paid through {@link ConfirmedOrders}, released,
 * packed, handed over and delivered. The draft is drawn up by {@link OrderDeliveredListener} after the delivery
 * commits ({@code @ApplicationModuleListener}), so it is awaited, never slept for. The jobs do not run on their own in
 * tests (application-test.yml): reconciliation, automatic issuing and the deadline sweep are called here, so nothing
 * answers for the provider before the test asks. The sandbox answers at once (processing-delay PT0S).</p>
 *
 * <p>The goods: cartons of 50 at 2,000 a piece - 100,000 a carton - of a product taxed STANDARD (10%).</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class EInvoiceIntegrationTest {

    private static final Duration ASYNC = Duration.ofSeconds(20);
    private static final long PRICE = 2_000;
    private static final BigDecimal TEN = new BigDecimal("10");

    @Autowired OrderService orders;
    @Autowired com.stockflow.inventory.api.InventoryService inventory;
    @Autowired EInvoiceDrafts drafts;
    @Autowired EInvoiceIssuance issuance;
    @Autowired EInvoiceQueryService queries;
    @Autowired EInvoiceSettingsService settings;
    @Autowired EInvoiceJobs jobs;
    @Autowired com.stockflow.invoicing.internal.domain.EInvoiceSending sending;
    @Autowired OrderDeliveredListener listener;
    @Autowired com.stockflow.invoicing.internal.domain.EInvoiceRepository invoices;
    @Autowired org.springframework.context.ApplicationEventPublisher publisher;
    @Autowired DeliveryCorrectionListener corrections;
    @Autowired EInvoiceCorrections invoiceCorrections;
    @Autowired FileStorage storage;
    @Autowired ApplicationContext context;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private ConfirmedOrders confirmedOrders;
    private UUID customer;
    private UUID staff;
    private UUID accountant;
    private String taxCode;
    private CustomerSkuRows.Issued sku;
    private final List<UUID> placed = Collections.synchronizedList(new ArrayList<>());

    /** A delivered shipment and the draft the listener drew up for it. */
    private record Delivered(UUID orderId, String orderNumber, UUID shipmentId, EInvoice.Snapshot draft) {
    }

    @BeforeEach
    void aCustomerWithItsLegalProfile() {
        confirmedOrders = new ConfirmedOrders(context);
        customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        staff = tx.execute(s -> ReferenceRows.user(entityManager));
        accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        taxCode = "03" + (10_000_000 + ThreadLocalRandom.current().nextInt(89_999_999));
        confirmedOrders.legalProfile(customer);
        buyer(taxCode, "ke.toan@" + customer + ".example.com");
        sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customer, 50, "STANDARD", true);
        CustomerSkuRows.stock(jdbc, tx, sku.sku(), 1_000);
    }

    @AfterEach
    void putTheStockBack() {
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> placed.forEach(id -> {
            OrderStatus status = orders.findById(id).orElseThrow().status();
            Long packed = jdbc.queryForObject("SELECT count(*) FROM ordering.customer_order WHERE id = ? AND packed_at "
                    + "IS NOT NULL", Long.class, id);
            if (status.canTransitionTo(OrderStatus.CANCELLED) && packed == 0) {
                orders.cancel(id, new CancelOrderCommand(CancellationReasonCode.OTHER, "test cleanup", null, null));
            } else {
                jdbc.queryForList("SELECT id FROM inventory.stock_reservation WHERE order_id = ? AND status = 'HELD'",
                        UUID.class, id).forEach(reservation -> inventory.release(reservation, "MANUAL_OVERRIDE"));
            }
        }));
        // Left as the migration seeds it, whatever a test turned on.
        var current = settings.get().provider();
        if (current.autoIssue()) {
            settings.changeAutoIssue(false, current.version());
        }
        tx.executeWithoutResult(s -> jdbc.update("DELETE FROM ordering.tax_rate_period WHERE note = 'EInvoiceIntegrationTest'"));
    }

    /** The customer's tax code and the e-mail its invoices go to, as its profile holds them. */
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

    /** {@code cartons} cartons ordered at 10% VAT, paid, released from HCM, packed, handed over whole and delivered. */
    private Delivered delivered(int cartons) {
        ConfirmedOrders.Confirmed confirmed = confirmedOrders.confirmed(customer,
                ConfirmedOrders.Line.cartons(sku, cartons, PRICE).withTax(TEN));
        placed.add(confirmed.orderId());
        confirmedOrders.release(confirmed.orderId(), DemoData.WAREHOUSE_HCM, staff);
        orders.startPicking(confirmed.orderId(), staff);
        orders.markPacked(confirmed.orderId(), staff);
        OrderSummary order = orders.findById(confirmed.orderId()).orElseThrow();
        UUID shipment = ShipmentRows.ready(jdbc, tx, order.orderId(), UUID.randomUUID());
        var line = order.lines().getFirst();
        orders.recordHandover(order.orderId(), new ShipmentHandover(shipment,
                List.of(new ShipmentHandover.Line(line.lineId(), line.quantity())), Instant.now(), staff));
        orders.recordShipmentDelivered(order.orderId(), shipment, staff);
        Await.until("the draft e-invoice of shipment " + shipment, ASYNC, () -> invoiceOf(shipment) != null);
        return new Delivered(order.orderId(), confirmed.orderNumber(), shipment, queries.get(invoiceOf(shipment)));
    }

    /** The original invoice in force for a shipment, or null. */
    private UUID invoiceOf(UUID shipmentId) {
        List<UUID> ids = jdbc.queryForList("""
                SELECT id FROM invoicing.einvoice
                 WHERE shipment_id = ? AND type = 'ORIGINAL' AND status NOT IN ('VOIDED_DRAFT', 'REPLACED')""",
                UUID.class, shipmentId);
        assertThat(ids).as("21 BR-01: at most one original in force per shipment").hasSizeLessThanOrEqualTo(1);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    /** What the sandbox holds for an invoice, over all its attempts: the invoices that exist at the provider. */
    private long issuedAtTheProvider(UUID invoiceId) {
        return jdbc.queryForObject("SELECT count(*) FROM invoicing.sandbox_issuance WHERE transaction_id LIKE ? "
                + "AND state = 'ISSUED'", Long.class, invoiceId + "-%");
    }

    private List<org.assertj.core.groups.Tuple> calls(UUID invoiceId) {
        return queries.calls(invoiceId).stream().map(call -> tuple(call.operation(), call.outcome())).toList();
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    private String read(String key) throws Exception {
        try (InputStream in = storage.download(key)) {
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    // ---- drawing up ----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a delivered shipment gets one draft: cartons x the carton price, the day's VAT, both parties copied")
    void aDeliveryIsDrawnUp() {
        Delivered delivered = delivered(4);
        EInvoice.Snapshot draft = delivered.draft();

        assertThat(draft.status()).isEqualTo(EInvoiceStatus.DRAFT);
        assertThat(draft.type()).isEqualTo(EInvoiceType.ORIGINAL);
        assertThat(draft.code()).startsWith("EINV-");
        assertThat(draft.orderId()).isEqualTo(delivered.orderId());
        assertThat(draft.orderNumber()).isEqualTo(delivered.orderNumber());
        assertThat(draft.contractNumber()).as("the order's signed contract").isNotBlank();
        assertThat(draft.customerId()).isEqualTo(customer);
        assertThat(draft.issueDate()).as("the day of the delivery, Vietnam time")
                .isEqualTo(LocalDate.now(ConfirmedOrders.VIETNAM));
        assertThat(draft.issueDueAt()).as("NĐ 254 Phụ lục điểm 7: the next working day").isAfter(Instant.now());
        assertThat(draft.lines()).hasSize(1);
        EInvoiceLine line = draft.lines().getFirst();
        assertThat(line.kind()).isEqualTo(EInvoiceLine.Kind.GOODS);
        assertThat(line.sku()).isEqualTo(sku.sku());
        assertThat(line.unit()).isEqualTo("Thùng");
        assertThat(line.quantity()).isEqualTo(4);
        assertThat(line.unitsPerCarton()).isEqualTo(50);
        assertThat(line.unitPrice()).as("50 pieces x 2,000").isEqualByComparingTo("100000");
        assertThat(line.taxRate()).as("ordering.tax_rate: STANDARD").isEqualByComparingTo("10");
        assertThat(draft.subtotal()).isEqualByComparingTo("400000");
        assertThat(draft.taxTotal()).isEqualByComparingTo("40000");
        assertThat(draft.grandTotal()).isEqualByComparingTo("440000");
        assertThat(draft.amountInWords()).isEqualTo("Bốn trăm bốn mươi nghìn đồng");
        assertThat(draft.seller().name()).as("stockflow.seller.*").isEqualTo("DEMO STOCKFLOW CO LTD");
        assertThat(draft.seller().taxCode()).isEqualTo("0000000000");
        assertThat(draft.buyer().name()).isEqualTo("Test customer");
        assertThat(draft.buyer().taxCode()).isEqualTo(taxCode);
        assertThat(draft.buyer().address()).isEqualTo("1 Le Loi, TP HCM");
        assertThat(draft.buyer().email()).isEqualTo("ke.toan@" + customer + ".example.com");
        assertThat(draft.warnings()).isEmpty();
        assertThat(calls(draft.id())).as("nothing was sent anywhere").isEmpty();

        // The delivery told again - a redelivered event, or the accountant asking: still the one invoice.
        listener.on(new OrderDelivered(delivered.orderId(), delivered.orderNumber(), customer, null,
                draft.grandTotal(), null, "VND", null, Instant.now(), delivered.shipmentId(), null));
        EInvoiceDrafts.DrawnUp again = drafts.drawUp(delivered.orderId(), delivered.shipmentId());
        assertThat(again.created()).isFalse();
        assertThat(again.invoice().id()).isEqualTo(draft.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoicing.einvoice WHERE shipment_id = ?", Long.class,
                delivered.shipmentId())).isEqualTo(1);

        // A shipment that is not delivered, and an order delivered without a shipment, are not invoiced.
        assertThatThrownBy(() -> drafts.drawUp(delivered.orderId(), UUID.randomUUID()))
                .extracting(EInvoiceIntegrationTest::code).isEqualTo(ErrorCode.EINVOICE_NOT_INVOICEABLE);
        listener.on(new OrderDelivered(delivered.orderId(), delivered.orderNumber(), customer, null,
                draft.grandTotal(), null, "VND", null, Instant.now(), null, null));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoicing.einvoice WHERE order_id = ?", Long.class,
                delivered.orderId())).isEqualTo(1);
    }

    @Test
    @DisplayName("a VAT reduction in force on the day of delivery is the invoice's rate, and the draft is flagged")
    void theRateInForceOnTheDayOfDelivery() {
        LocalDate today = LocalDate.now(ConfirmedOrders.VIETNAM);
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO ordering.tax_rate_period (id, tax_class, rate, valid_from, valid_to, note)
                VALUES (gen_random_uuid(), 'STANDARD', 8, ?, ?, 'EInvoiceIntegrationTest')""",
                java.sql.Date.valueOf(today.minusDays(1)), java.sql.Date.valueOf(today.plusDays(1))));

        EInvoice.Snapshot draft = delivered(2).draft();

        // The contract fixed 10%; the day's rate is 8%: 200,000 at 8%.
        assertThat(draft.lines().getFirst().taxRate()).isEqualByComparingTo("8");
        assertThat(draft.taxTotal()).isEqualByComparingTo("16000");
        assertThat(draft.grandTotal()).isEqualByComparingTo("216000");
        assertThat(draft.warnings()).containsExactly(EInvoice.Warning.TAX_RATE_DIFFERS_FROM_CONTRACT);
    }

    // ---- issuing -------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("issued: numbered under the year's symbol, coded, signed, its XML and PDF stored - and then immutable")
    void issuedAndImmutable() throws Exception {
        EInvoice.Snapshot draft = delivered(3).draft();

        EInvoice.Snapshot issued = FirstSend.issue(issuance, queries, draft.id(), draft.version(), accountant);

        assertThat(issued.status()).isEqualTo(EInvoiceStatus.ISSUED);
        String year = "%02d".formatted(issued.issueDate().getYear() % 100);
        assertThat(issued.provider()).isEqualTo("SANDBOX");
        assertThat(issued.templateCode()).isEqualTo("1");
        assertThat(issued.symbol()).as("TT 91 Phụ lục I: with code, the year, registered by a business")
                .isEqualTo("C" + year + "TSB");
        assertThat(issued.invoiceNumber()).isBetween(1, 99_999_999);
        assertThat(issued.taxAuthorityCode()).startsWith("SBX");
        assertThat(issued.lookupCode()).startsWith("SBX");
        assertThat(issued.signedAt()).isNotNull();
        assertThat(issued.issuedAt()).isNotNull();
        assertThat(issued.transactionId()).isEqualTo(draft.id() + "-1");
        assertThat(issued.submittedBy()).isEqualTo(accountant);
        assertThat(issued.warnings()).isEmpty();
        // Asked first, sent because the provider had never heard of it, asked again, files fetched.
        assertThat(calls(draft.id())).containsExactly(tuple(Operation.QUERY, Outcome.NOT_FOUND),
                tuple(Operation.SUBMIT, Outcome.ACCEPTED), tuple(Operation.QUERY, Outcome.ISSUED),
                tuple(Operation.DOWNLOAD, Outcome.DOWNLOADED));
        assertThat(queries.calls(draft.id())).allSatisfy(call -> {
            assertThat(call.transactionId()).isEqualTo(issued.transactionId());
            assertThat(call.responseHash()).matches("[0-9a-f]{64}");
        });
        assertThat(issuedAtTheProvider(draft.id())).isEqualTo(1);

        // The files, as stored under the e-invoice category.
        assertThat(issued.xmlFileKey()).startsWith("einvoices/").endsWith(".xml");
        assertThat(issued.pdfFileKey()).startsWith("einvoices/").endsWith(".pdf");
        String xml = new String(read(issued.xmlFileKey()).getBytes(StandardCharsets.ISO_8859_1),
                StandardCharsets.UTF_8);
        assertThat(xml).contains("<SHDon>" + issued.invoiceNumber() + "</SHDon>")
                .contains("<KHHDon>" + issued.symbol() + "</KHHDon>")
                .contains("<MCCQT>" + issued.taxAuthorityCode() + "</MCCQT>")
                .contains("<MST>" + taxCode + "</MST>").contains("<TgTTTBSo>330000</TgTTTBSo>")
                .contains("NO LEGAL VALUE");
        assertThat(read(issued.pdfFileKey())).startsWith("%PDF-");

        // 21 BR-03, TT 91 Điều 10: no correction, no void, no second issue, no way back - by the application ...
        long version = issued.version();
        assertThatThrownBy(() -> drafts.revise(new EInvoiceDrafts.Revise(draft.id(), version, "Ten khac", null, null,
                null, null))).extracting(EInvoiceIntegrationTest::code).isEqualTo(ErrorCode.EINVOICE_NOT_DRAFT);
        assertThatThrownBy(() -> drafts.refreshBuyer(draft.id(), version))
                .extracting(EInvoiceIntegrationTest::code).isEqualTo(ErrorCode.EINVOICE_NOT_DRAFT);
        assertThatThrownBy(() -> drafts.voidDraft(draft.id(), version, "cancel it", accountant))
                .extracting(EInvoiceIntegrationTest::code).isEqualTo(ErrorCode.EINVOICE_INVALID_TRANSITION);
        assertThatThrownBy(() -> drafts.reopen(draft.id(), version))
                .extracting(EInvoiceIntegrationTest::code).isEqualTo(ErrorCode.EINVOICE_INVALID_TRANSITION);
        assertThatThrownBy(() -> issuance.issue(draft.id(), version, accountant))
                .extracting(EInvoiceIntegrationTest::code).isEqualTo(ErrorCode.EINVOICE_INVALID_TRANSITION);
        // ... and by the database, whoever writes to it.
        for (String change : List.of("buyer_name = 'KHAC'", "grand_total = grand_total + 1, subtotal = subtotal + 1",
                "invoice_number = invoice_number + 1", "status = 'DRAFT'", "status = 'VOIDED_DRAFT', voided_at = NOW(), "
                        + "void_reason = 'x'", "pdf_file_key = 'einvoices/other.pdf'")) {
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("UPDATE invoicing.einvoice SET " + change
                    + " WHERE id = ?", draft.id()))).as(change).hasMessageContaining("is issued and does not change");
        }
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE invoicing.einvoice_line SET item_name = 'khac' WHERE einvoice_id = ?", draft.id())))
                .hasMessageContaining("only while it is a draft");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update(
                "DELETE FROM invoicing.einvoice WHERE id = ?", draft.id()))).hasMessageContaining("never deleted");
        assertThat(queries.get(draft.id())).as("exactly as it was issued").isEqualTo(issued);

        // Asking the provider again about an issued invoice changes nothing and calls nobody.
        assertThat(issuance.reconcile(draft.id())).isEqualTo(issued);
        assertThat(queries.calls(draft.id())).hasSize(4);

        // The register of its day, and its CSV.
        List<EInvoiceQueries.RegisterEntry> register = queries.register(issued.issueDate(), issued.issueDate());
        assertThat(register).filteredOn(entry -> entry.symbol().equals(issued.symbol())
                        && entry.invoiceNumber() == issued.invoiceNumber())
                .singleElement().satisfies(entry -> {
                    assertThat(entry.buyerTaxCode()).isEqualTo(taxCode);
                    assertThat(entry.subtotal()).isEqualByComparingTo("300000");
                    assertThat(entry.taxTotal()).isEqualByComparingTo("30000");
                    assertThat(entry.grandTotal()).isEqualByComparingTo("330000");
                    assertThat(entry.status()).isEqualTo(EInvoiceStatus.ISSUED);
                });
        assertThat(queries.registerCsv(issued.issueDate(), issued.issueDate()))
                .contains("\"" + issued.symbol() + "\",%08d,".formatted(issued.invoiceNumber()))
                .contains(",300000,30000,330000,");
        assertThat(queries.register(issued.issueDate().minusDays(30), issued.issueDate().minusDays(1)))
                .as("not in an earlier period").noneMatch(entry -> entry.taxAuthorityCode()
                        .equals(issued.taxAuthorityCode()));
        // The accountant's list finds it by order.
        assertThat(queries.list(new EInvoiceQueries.Filter(EInvoiceStatus.ISSUED, null, issued.orderId(), null, null,
                null, null), 0, 20).items()).extracting(EInvoiceQueries.Listing::id).containsExactly(draft.id());
    }

    @Test
    @DisplayName("numbers run on within the symbol: the next invoice takes the next number")
    void numbersRunOn() {
        EInvoice.Snapshot first = delivered(1).draft();
        EInvoice.Snapshot second = delivered(1).draft();

        EInvoice.Snapshot firstIssued = FirstSend.issue(issuance, queries, first.id(), first.version(), accountant);
        EInvoice.Snapshot secondIssued = FirstSend.issue(issuance, queries, second.id(), second.version(), accountant);

        assertThat(secondIssued.symbol()).isEqualTo(firstIssued.symbol());
        assertThat(secondIssued.invoiceNumber()).as("NĐ 254 Phụ lục điểm 2: one after the other, none twice")
                .isGreaterThan(firstIssued.invoiceNumber());
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT invoice_number) = count(*) FROM invoicing.einvoice "
                + "WHERE symbol = ? AND invoice_number IS NOT NULL", Boolean.class, firstIssued.symbol())).isTrue();
    }

    @Test
    @DisplayName("21 BR-06: a buyer without a tax code or invoice e-mail is not issued until the profile has them")
    void anIncompleteBuyerBlocksIssuing() {
        buyer(null, null);
        EInvoice.Snapshot draft = delivered(1).draft();
        assertThat(draft.warnings()).containsExactly(EInvoice.Warning.BUYER_PROFILE_INCOMPLETE);
        assertThat(draft.buyer().taxCode()).isNull();

        assertThatThrownBy(() -> issuance.issue(draft.id(), draft.version(), accountant))
                .extracting(EInvoiceIntegrationTest::code).isEqualTo(ErrorCode.EINVOICE_BUYER_PROFILE_INCOMPLETE);
        assertThat(queries.get(draft.id()).status()).as("still a draft, nothing sent").isEqualTo(EInvoiceStatus.DRAFT);
        assertThat(calls(draft.id())).isEmpty();

        // Sales adds what was missing to the customer's profile; the accountant copies it onto the draft.
        buyer(taxCode, "hoa.don@" + customer + ".example.com");
        EInvoice.Snapshot refreshed = drafts.refreshBuyer(draft.id(), draft.version());
        assertThat(refreshed.buyer().taxCode()).isEqualTo(taxCode);
        assertThat(refreshed.buyer().email()).isEqualTo("hoa.don@" + customer + ".example.com");
        assertThat(refreshed.warnings()).isEmpty();
        assertThatThrownBy(() -> drafts.refreshBuyer(draft.id(), draft.version())).as("a stale version")
                .extracting(EInvoiceIntegrationTest::code).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);

        assertThat(FirstSend.issue(issuance, queries, draft.id(), refreshed.version(), accountant).status())
                .isEqualTo(EInvoiceStatus.ISSUED);
        // The profile changes afterwards; the invoice keeps the buyer it was issued to.
        buyer(taxCode, "khac@" + customer + ".example.com");
        assertThat(queries.get(draft.id()).buyer().email()).isEqualTo("hoa.don@" + customer + ".example.com");
    }

    // ---- when the provider goes wrong ----------------------------------------------------------------------------

    @Test
    @DisplayName("the reply is lost after the provider took the invoice in: asked again by transaction id, never sent twice")
    void aLostReplyNeverIssuesTwice() {
        buyer(taxCode, "ke.toan+sandbox-reply-lost@" + customer + ".example.com");
        EInvoice.Snapshot draft = delivered(1).draft();

        EInvoice.Snapshot waiting = FirstSend.issue(issuance, queries, draft.id(), draft.version(), accountant);

        assertThat(waiting.status()).as("nothing is known: it waits under its transaction id")
                .isEqualTo(EInvoiceStatus.SUBMITTING);
        assertThat(calls(draft.id())).containsExactly(tuple(Operation.QUERY, Outcome.NOT_FOUND),
                tuple(Operation.SUBMIT, Outcome.TIMEOUT));
        assertThat(issuedAtTheProvider(draft.id())).as("the provider has it, though nobody was told").isEqualTo(1);
        // Not a draft any more: it cannot be corrected, voided or issued again while it may be at the provider.
        assertThatThrownBy(() -> drafts.voidDraft(draft.id(), waiting.version(), "try again", accountant))
                .extracting(EInvoiceIntegrationTest::code).isEqualTo(ErrorCode.EINVOICE_INVALID_TRANSITION);
        assertThatThrownBy(() -> issuance.issue(draft.id(), waiting.version(), accountant))
                .extracting(EInvoiceIntegrationTest::code).isEqualTo(ErrorCode.EINVOICE_INVALID_TRANSITION);

        EInvoice.Snapshot issued = issuance.reconcile(draft.id());

        assertThat(issued.status()).isEqualTo(EInvoiceStatus.ISSUED);
        assertThat(issued.transactionId()).isEqualTo(waiting.transactionId());
        assertThat(calls(draft.id())).as("asked, found, fetched - not submitted a second time").containsExactly(
                tuple(Operation.QUERY, Outcome.NOT_FOUND), tuple(Operation.SUBMIT, Outcome.TIMEOUT),
                tuple(Operation.QUERY, Outcome.ISSUED), tuple(Operation.DOWNLOAD, Outcome.DOWNLOADED));
        assertThat(issuedAtTheProvider(draft.id())).as("one delivery, one invoice").isEqualTo(1);
    }

    @Test
    @DisplayName("the provider cannot be reached: the invoice waits, and the job sends it once it can")
    void anUnreachableProviderIsRetriedByTheJob() {
        buyer(taxCode, "ke.toan+sandbox-unreachable@" + customer + ".example.com");
        EInvoice.Snapshot draft = delivered(1).draft();

        EInvoice.Snapshot waiting = FirstSend.issue(issuance, queries, draft.id(), draft.version(), accountant);
        assertThat(waiting.status()).isEqualTo(EInvoiceStatus.SUBMITTING);
        assertThat(issuedAtTheProvider(draft.id())).as("it never arrived").isZero();

        assertThat(jobs.reconcileSubmitting()).isGreaterThanOrEqualTo(1);

        assertThat(queries.get(draft.id()).status()).isEqualTo(EInvoiceStatus.ISSUED);
        assertThat(calls(draft.id())).containsExactly(tuple(Operation.QUERY, Outcome.NOT_FOUND),
                tuple(Operation.SUBMIT, Outcome.TIMEOUT), tuple(Operation.QUERY, Outcome.NOT_FOUND),
                tuple(Operation.SUBMIT, Outcome.ACCEPTED), tuple(Operation.QUERY, Outcome.ISSUED),
                tuple(Operation.DOWNLOAD, Outcome.DOWNLOADED));
        assertThat(issuedAtTheProvider(draft.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("review H2: while one instance holds an invoice's lease in the database, a second one cannot take "
            + "it and neither asks nor sends - until the lease is given up or runs out")
    void aLeaseHeldElsewhereKeepsEveryoneFromTheProvider() {
        buyer(taxCode, "ke.toan+sandbox-unreachable@" + customer + ".example.com");
        EInvoice.Snapshot draft = delivered(1).draft();
        EInvoice.Snapshot waiting = FirstSend.issue(issuance, queries, draft.id(), draft.version(), accountant);
        assertThat(waiting.status()).isEqualTo(EInvoiceStatus.SUBMITTING);
        List<org.assertj.core.groups.Tuple> before = calls(draft.id());
        UUID other = com.stockflow.common.id.Identifiers.newId();
        UUID here = com.stockflow.common.id.Identifiers.newId();
        Duration lease = Duration.ofMinutes(15);

        // Another instance is at the provider for this invoice.
        assertThat((boolean) tx.execute(s -> sending.claim(draft.id(), other, lease))).isTrue();

        assertThat((boolean) tx.execute(s -> sending.claim(draft.id(), here, lease))).as("one holder at a time").isFalse();
        assertThat((boolean) tx.execute(s -> sending.renew(draft.id(), here, lease))).as("not the holder's to extend").isFalse();
        tx.executeWithoutResult(s -> sending.release(draft.id(), here));
        assertThat((boolean) tx.execute(s -> sending.claim(draft.id(), here, lease))).as("not the holder's to give up").isFalse();
        assertThat(issuance.reconcile(draft.id()).status()).isEqualTo(EInvoiceStatus.SUBMITTING);
        assertThat(invoices.findSubmitting(10_000)).as("review L1: the job's batch leaves it to its holder")
                .doesNotContain(draft.id());
        jobs.reconcileSubmitting();
        assertThatThrownBy(() -> issuance.resubmitAsNew(draft.id(), waiting.version(), accountant))
                .extracting(EInvoiceIntegrationTest::code).isEqualTo(ErrorCode.EINVOICE_RESUBMISSION_REFUSED);
        assertThat(calls(draft.id())).as("nobody but the holder called the provider").isEqualTo(before);
        assertThat(issuedAtTheProvider(draft.id())).isZero();

        // The holder died: its lease runs out on the database's clock, and the next one takes it.
        tx.executeWithoutResult(s -> jdbc.update("UPDATE invoicing.einvoice_sending SET held_until = now() - "
                + "interval '1 second' WHERE einvoice_id = ?", draft.id()));
        assertThat(invoices.findSubmitting(10_000)).contains(draft.id());

        EInvoice.Snapshot issued = issuance.reconcile(draft.id());

        assertThat(issued.status()).isEqualTo(EInvoiceStatus.ISSUED);
        assertThat(issuedAtTheProvider(draft.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT holder FROM invoicing.einvoice_sending WHERE einvoice_id = ?",
                UUID.class, draft.id())).as("given up when done").isNull();
    }

    @Test
    @DisplayName("review L1: more invoices unknown to the provider than a batch holds do not keep a newer one out "
            + "of the batch the job asks about")
    void invoicesUnknownToTheProviderDoNotFillTheBatch() {
        EInvoice.Snapshot draft = delivered(1).draft();
        EInvoice.Snapshot issued = FirstSend.issue(issuance, queries, draft.id(), draft.version(), accountant);
        assertThat(issued.status()).isEqualTo(EInvoiceStatus.ISSUED);
        int unknown = EInvoiceJobs.BATCH + 1;
        // Written straight to the tables, as adjustments of the one issued invoice that change no money: a batch and
        // one more at the provider for a month and marked unknown to it, and one sent just now. A real one of each
        // would be an order delivered apiece.
        String codes = "L1-" + issued.id().toString().substring(24) + "-";
        String newerCode = codes + (unknown + 1);
        try {
            tx.executeWithoutResult(s -> {
                jdbc.update("""
                        INSERT INTO invoicing.einvoice (id, code, type, status, order_id, order_number, contract_number,
                                shipment_id, shipment_code, delivery_batch_id, customer_id, original_einvoice_id,
                                issue_date, issue_due_at, seller_name, seller_tax_code, seller_address, buyer_name,
                                buyer_tax_code, buyer_address, buyer_email, currency, payment_method, subtotal,
                                tax_total, grand_total, amount_in_words, adjustment_reason, correction_basis,
                                agreement_file_key, agreement_number, agreement_date)
                        SELECT gen_random_uuid(), ? || n::text, 'ADJUSTMENT', 'DRAFT', o.order_id, o.order_number,
                               o.contract_number, o.shipment_id, o.shipment_code, o.delivery_batch_id, o.customer_id,
                               o.id, o.issue_date, o.issue_due_at, o.seller_name, o.seller_tax_code, o.seller_address,
                               o.buyer_name, o.buyer_tax_code, o.buyer_address, o.buyer_email, o.currency,
                               o.payment_method, 0, 0, 0, o.amount_in_words, 'review L1', 'ERROR',
                               'einvoices/review-l1.pdf', 'BB-L1', o.issue_date
                          FROM invoicing.einvoice o CROSS JOIN generate_series(1, ?) n
                         WHERE o.id = ?""", codes, unknown + 1, issued.id());
                jdbc.update("""
                        INSERT INTO invoicing.einvoice_line (id, einvoice_id, line_no, kind, item_name, unit, quantity,
                                unit_price, tax_rate, amount, tax_amount)
                        SELECT gen_random_uuid(), a.id, 1, 'NOTE', 'review L1', 'Lan', 1, 0, 0, 0, 0
                          FROM invoicing.einvoice a WHERE a.code LIKE ?""", codes + "%");
                jdbc.update("""
                        UPDATE invoicing.einvoice a
                           SET status = 'SUBMITTING', provider = o.provider, attempt = 1,
                               transaction_id = a.id::text || '-1', template_code = o.template_code, symbol = o.symbol,
                               submitted_at = CASE WHEN a.code = ? THEN now() ELSE now() - interval '30 days' END,
                               warnings = CASE WHEN a.code = ? THEN NULL ELSE 'UNKNOWN_TO_PROVIDER' END
                          FROM invoicing.einvoice o
                         WHERE o.id = a.original_einvoice_id AND a.code LIKE ?""", newerCode, newerCode, codes + "%");
            });
            UUID newer = jdbc.queryForObject("SELECT id FROM invoicing.einvoice WHERE code = ?", UUID.class, newerCode);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM invoicing.einvoice
                     WHERE code LIKE ? AND status = 'SUBMITTING' AND warnings = 'UNKNOWN_TO_PROVIDER'
                       AND submitted_at < now() - interval '1 day'""", Integer.class, codes + "%"))
                    .as("more than a batch, all waiting longer than the newer one").isEqualTo(unknown);
            // The job's batch - or, should other tests have left more than that waiting, as many as wait: either way
            // fewer places than the unknown ones and the rest together.
            int waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM invoicing.einvoice i
                      LEFT JOIN invoicing.einvoice_sending s ON s.einvoice_id = i.id
                     WHERE i.status = 'SUBMITTING' AND coalesce(i.warnings, '') NOT LIKE '%UNKNOWN_TO_PROVIDER%'
                       AND (s.holder IS NULL OR s.held_until <= now())""", Integer.class);

            List<UUID> batch = invoices.findSubmitting(Math.max(EInvoiceJobs.BATCH, waiting));

            assertThat(batch).as("ordered by the time they were sent alone, the unknown ones took every place")
                    .contains(newer);
        } finally {
            // Out of the way of every other test's job run: a draft again, then voided.
            tx.executeWithoutResult(s -> {
                jdbc.update("UPDATE invoicing.einvoice SET status = 'REJECTED', rejection_code = 'TEST_FIXTURE', "
                        + "rejection_message = 'review L1' WHERE code LIKE ? AND status = 'SUBMITTING'", codes + "%");
                jdbc.update("UPDATE invoicing.einvoice SET status = 'DRAFT' WHERE code LIKE ? AND status = 'REJECTED'",
                        codes + "%");
                jdbc.update("UPDATE invoicing.einvoice SET status = 'VOIDED_DRAFT', voided_at = now(), "
                        + "void_reason = 'review L1 fixture' WHERE code LIKE ? AND status = 'DRAFT'", codes + "%");
            });
        }
    }

    @Test
    @DisplayName("asked about by two at once - the accountant and the job: one invoice at the provider, issued once")
    void reconciledByTwoAtOnce() throws Exception {
        buyer(taxCode, "ke.toan+sandbox-unreachable@" + customer + ".example.com");
        EInvoice.Snapshot draft = delivered(1).draft();
        FirstSend.issue(issuance, queries, draft.id(), draft.version(), accountant);
        assertThat(queries.get(draft.id()).status()).isEqualTo(EInvoiceStatus.SUBMITTING);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<EInvoice.Snapshot>> answers = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Callable<EInvoice.Snapshot> ask = () -> issuance.reconcile(draft.id());
                answers.add(pool.submit(ask));
            }
            for (Future<EInvoice.Snapshot> answer : answers) {
                answer.get();
            }
        } finally {
            pool.shutdownNow();
        }
        // A caller that lost the race may have seen the provider busy; whoever asks next gets the result.
        EInvoice.Snapshot issued = issuance.reconcile(draft.id());

        assertThat(issued.status()).isEqualTo(EInvoiceStatus.ISSUED);
        assertThat(issuedAtTheProvider(draft.id())).as("the transaction id let one through").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoicing.sandbox_issuance WHERE transaction_id LIKE ?",
                Long.class, draft.id() + "-%")).isEqualTo(1);
        assertThat(issued.invoiceNumber()).isEqualTo(jdbc.queryForObject("SELECT invoice_number FROM "
                + "invoicing.sandbox_issuance WHERE transaction_id = ?", Integer.class, issued.transactionId()));
    }

    @Test
    @DisplayName("a wrong buyer tax code is refused: REJECTED, back to a draft, corrected, issued under a new transaction")
    void aRejectionIsCorrectedAndIssuedAgain() {
        buyer("0000000001", "ke.toan@" + customer + ".example.com");
        EInvoice.Snapshot draft = delivered(1).draft();

        EInvoice.Snapshot rejected = FirstSend.issue(issuance, queries, draft.id(), draft.version(), accountant);

        assertThat(rejected.status()).isEqualTo(EInvoiceStatus.REJECTED);
        assertThat(rejected.rejectionCode()).isEqualTo("INVALID_BUYER_TAX_CODE");
        assertThat(rejected.rejectionMessage()).isNotBlank();
        assertThat(rejected.invoiceNumber()).isNull();
        assertThat(calls(draft.id())).containsExactly(tuple(Operation.QUERY, Outcome.NOT_FOUND),
                tuple(Operation.SUBMIT, Outcome.REJECTED));
        assertThat(issuedAtTheProvider(draft.id())).as("no invoice exists for a refused transaction").isZero();
        // A rejected invoice is not corrected or issued as it stands: it goes back to a draft first.
        assertThatThrownBy(() -> drafts.revise(new EInvoiceDrafts.Revise(draft.id(), rejected.version(), null, taxCode,
                null, null, null))).extracting(EInvoiceIntegrationTest::code).isEqualTo(ErrorCode.EINVOICE_NOT_DRAFT);
        assertThatThrownBy(() -> issuance.issue(draft.id(), rejected.version(), accountant))
                .extracting(EInvoiceIntegrationTest::code).isEqualTo(ErrorCode.EINVOICE_INVALID_TRANSITION);

        EInvoice.Snapshot reopened = drafts.reopen(draft.id(), rejected.version());
        assertThat(reopened.status()).isEqualTo(EInvoiceStatus.DRAFT);
        assertThat(reopened.rejectionCode()).as("why it came back is still shown").isEqualTo("INVALID_BUYER_TAX_CODE");
        EInvoice.Snapshot corrected = drafts.revise(new EInvoiceDrafts.Revise(draft.id(), reopened.version(), null,
                taxCode, null, null, null));
        assertThat(jobs.issueAutomatically()).as("automatic issuing is off").isZero();

        EInvoice.Snapshot issued = FirstSend.issue(issuance, queries, draft.id(), corrected.version(), accountant);

        assertThat(issued.status()).isEqualTo(EInvoiceStatus.ISSUED);
        assertThat(issued.transactionId()).as("21 BR-04: a new attempt is a new transaction").isEqualTo(draft.id() + "-2");
        assertThat(issued.buyer().taxCode()).isEqualTo(taxCode);
        assertThat(issued.rejectionCode()).isNull();
        assertThat(issuedAtTheProvider(draft.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("accepted, then refused by the tax authority: REJECTED with its reason, no number")
    void theTaxAuthorityRefuses() {
        buyer(taxCode, "ke.toan+sandbox-authority-rejects@" + customer + ".example.com");
        EInvoice.Snapshot draft = delivered(1).draft();

        EInvoice.Snapshot rejected = FirstSend.issue(issuance, queries, draft.id(), draft.version(), accountant);

        assertThat(rejected.status()).isEqualTo(EInvoiceStatus.REJECTED);
        assertThat(rejected.rejectionCode()).isEqualTo("TAX_AUTHORITY_REJECTED");
        assertThat(rejected.invoiceNumber()).isNull();
        assertThat(rejected.xmlFileKey()).isNull();
        assertThat(calls(draft.id())).containsExactly(tuple(Operation.QUERY, Outcome.NOT_FOUND),
                tuple(Operation.SUBMIT, Outcome.ACCEPTED), tuple(Operation.QUERY, Outcome.REJECTED));
        assertThat(issuedAtTheProvider(draft.id())).isZero();
    }

    // ---- a draft's own life ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("a draft is corrected and voided with a reason; its shipment is then drawn up again")
    void aDraftIsCorrectedVoidedAndDrawnUpAgain() {
        Delivered delivered = delivered(2);
        EInvoice.Snapshot draft = delivered.draft();
        UUID lineId = draft.lines().getFirst().id();

        EInvoice.Snapshot revised = drafts.revise(new EInvoiceDrafts.Revise(draft.id(), draft.version(),
                "CONG TY TNHH TEST", null, "12 Nguyen Hue, P. Ben Nghe, TP HCM", null,
                java.util.Map.of(lineId, "Ly giay 12oz in logo")));
        assertThat(revised.buyer().name()).isEqualTo("CONG TY TNHH TEST");
        assertThat(revised.buyer().address()).isEqualTo("12 Nguyen Hue, P. Ben Nghe, TP HCM");
        assertThat(revised.buyer().taxCode()).as("left out: unchanged").isEqualTo(taxCode);
        assertThat(revised.lines().getFirst().itemName()).isEqualTo("Ly giay 12oz in logo");
        assertThat(revised.grandTotal()).as("money is the contract's").isEqualByComparingTo(draft.grandTotal());
        assertThat(queries.get(draft.id())).isEqualTo(revised);
        assertThatThrownBy(() -> drafts.revise(new EInvoiceDrafts.Revise(draft.id(), draft.version(), "X", null, null,
                null, null))).as("the version read before the change")
                .extracting(EInvoiceIntegrationTest::code).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);

        assertThatThrownBy(() -> drafts.voidDraft(draft.id(), revised.version(), " ", accountant))
                .extracting(EInvoiceIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        EInvoice.Snapshot voided = drafts.voidDraft(draft.id(), revised.version(), "the buyer asked for another name",
                accountant);
        assertThat(voided.status()).isEqualTo(EInvoiceStatus.VOIDED_DRAFT);
        assertThat(voided.voidedBy()).isEqualTo(accountant);
        assertThat(voided.voidReason()).isEqualTo("the buyer asked for another name");
        assertThatThrownBy(() -> issuance.issue(draft.id(), voided.version(), accountant))
                .extracting(EInvoiceIntegrationTest::code).isEqualTo(ErrorCode.EINVOICE_INVALID_TRANSITION);

        EInvoiceDrafts.DrawnUp again = drafts.drawUp(delivered.orderId(), delivered.shipmentId());
        assertThat(again.created()).isTrue();
        assertThat(again.invoice().id()).isNotEqualTo(draft.id());
        assertThat(again.invoice().code()).isNotEqualTo(draft.code());
        assertThat(again.invoice().buyer().name()).as("copied afresh from the profile").isEqualTo("Test customer");
        assertThat(again.invoice().grandTotal()).isEqualByComparingTo(draft.grandTotal());
        assertThat(invoiceOf(delivered.shipmentId())).isEqualTo(again.invoice().id());
        assertThat(queries.get(draft.id()).status()).as("the voided draft is kept").isEqualTo(EInvoiceStatus.VOIDED_DRAFT);

        assertThatThrownBy(() -> queries.fileLink(again.invoice().id(), EInvoiceQueryService.FileKind.PDF))
                .extracting(EInvoiceIntegrationTest::code).isEqualTo(ErrorCode.EINVOICE_NOT_ISSUED);
        assertThatThrownBy(() -> queries.get(UUID.randomUUID()))
                .extracting(EInvoiceIntegrationTest::code).isEqualTo(ErrorCode.EINVOICE_NOT_FOUND);
    }

    @Test
    @DisplayName("21 §4.5: a delivery corrected to a failure voids the draft; an issued invoice is left for an adjustment")
    void aCorrectedDeliveryVoidsTheDraft() {
        Delivered delivered = delivered(1);
        Delivered issuedOne = delivered(1);
        EInvoice.Snapshot issued = FirstSend.issue(issuance, queries, issuedOne.draft().id(), issuedOne.draft().version(), accountant);

        corrections.on(correction(delivered));
        corrections.on(correction(issuedOne));
        // Heard for every step of every shipment: only DELIVERED -> DELIVERY_FAILED is a correction.
        Delivered untouched = delivered(1);
        corrections.on(new ShipmentStatusChanged(UUID.randomUUID(), untouched.shipmentId(), "S", untouched.orderId(),
                untouched.orderNumber(), customer, DemoData.WAREHOUSE_HCM, "IN_TRANSIT", "DELIVERED", null, 1,
                "Truck Co", null, staff, Instant.now()));

        Await.until("the draft of the corrected delivery is voided", ASYNC,
                () -> queries.get(delivered.draft().id()).status() == EInvoiceStatus.VOIDED_DRAFT);
        EInvoice.Snapshot voided = queries.get(delivered.draft().id());
        assertThat(voided.voidedBy()).as("the system's doing").isNull();
        assertThat(voided.voidReason()).contains("corrected to a failure");
        assertThat(invoiceOf(delivered.shipmentId())).as("no invoice in force for an undelivered shipment").isNull();
        assertThat(drafts.voidForCorrectedDelivery(issuedOne.orderId(), issuedOne.shipmentId()))
                .isEqualTo(EInvoiceDrafts.CorrectionOutcome.LEFT_FOR_ADJUSTMENT);
        assertThat(queries.get(issued.id())).as("issued: untouched").isEqualTo(issued);
        assertThat(queries.get(untouched.draft().id()).status()).isEqualTo(EInvoiceStatus.DRAFT);
    }

    private ShipmentStatusChanged correction(Delivered delivered) {
        return new ShipmentStatusChanged(UUID.randomUUID(), delivered.shipmentId(), "S", delivered.orderId(),
                delivered.orderNumber(), customer, DemoData.WAREHOUSE_HCM, "DELIVERED", "DELIVERY_FAILED",
                "CUSTOMER_ABSENT", 1, "Truck Co", null, staff, Instant.now());
    }

    @Test
    @DisplayName("review finding 3: a delivery with nothing to invoice completes its event and is put before the "
            + "accountant, instead of being refused again for ever")
    void aRefusedDraftCompletesItsEvent() {
        Delivered delivered = delivered(1);
        UUID notDelivered = UUID.randomUUID();

        // Through the registry and the real listener: published inside a transaction, heard after its commit.
        tx.executeWithoutResult(s -> publisher.publishEvent(new OrderDelivered(delivered.orderId(),
                delivered.orderNumber(), customer, null, delivered.draft().grandTotal(), null, "VND", null,
                Instant.now(), notDelivered, null)));

        String mine = "%" + notDelivered + "%";
        Await.until("the invoicing listener's publication of the refused delivery is completed", ASYNC,
                () -> jdbc.queryForObject("""
                        SELECT count(*) FROM event_publication
                         WHERE listener_id LIKE '%invoicing%OrderDeliveredListener%' AND serialized_event LIKE ?
                           AND completion_date IS NOT NULL""", Long.class, mine) == 1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM event_publication
                 WHERE listener_id LIKE '%invoicing%OrderDeliveredListener%' AND serialized_event LIKE ?
                   AND completion_date IS NULL""", Long.class, mine)).as("nothing left to deliver again").isZero();
        // The refusal is announced: EInvoiceDraftFailed went to its listener, naming the shipment and the reason.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM event_publication
                 WHERE event_type LIKE '%EInvoiceDraftFailed' AND serialized_event LIKE ?
                   AND serialized_event LIKE '%EINVOICE_NOT_INVOICEABLE%'""", Long.class, mine))
                .isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoicing.einvoice WHERE shipment_id = ?", Long.class,
                notDelivered)).isZero();
        assertThat(queries.get(delivered.draft().id()).status()).as("the order's real draft is untouched")
                .isEqualTo(EInvoiceStatus.DRAFT);
    }

    @Test
    @DisplayName("what an order's invoices already carry is read from its originals that were not voided")
    void whatTheOrdersInvoicesCarry() {
        Delivered delivered = delivered(4);

        assertThat(invoices.invoicedOfOrder(delivered.orderId()).units()).as("4 cartons of 50").isEqualTo(200);
        assertThat(invoices.invoicedOfOrder(delivered.orderId()).plateFeeSkus()).as("no fee lines on this order")
                .isEmpty();
        assertThat(invoices.invoicedOfOrder(delivered.orderId()).shippingFee()).isEqualByComparingTo("0");
        assertThat(invoices.invoicedOfOrder(UUID.randomUUID()).units()).as("an order without invoices").isZero();
        assertThat(invoices.hasReplacedOriginalOfShipment(delivered.shipmentId())).isFalse();

        drafts.voidDraft(delivered.draft().id(), delivered.draft().version(), "drawn up by mistake", accountant);
        assertThat(invoices.invoicedOfOrder(delivered.orderId()).units()).as("a voided draft carries nothing").isZero();
    }

    // ---- goods returned --------------------------------------------------------------------------------------------

    /** A delivery of 5 cartons - 250 pieces, 550,000 with tax - invoiced and issued. */
    private EInvoice.Snapshot issuedDelivery() {
        EInvoice.Snapshot draft = delivered(5).draft();
        EInvoice.Snapshot issued = FirstSend.issue(issuance, queries, draft.id(), draft.version(), accountant);
        assertThat(issued.status()).isEqualTo(EInvoiceStatus.ISSUED);
        return issued;
    }

    /** A draft adjustment of the invoice's goods line: {@code cartons} at the line's own price, or at {@code price}. */
    private void adjusted(EInvoice.Snapshot invoice, CorrectionBasis basis, int cartons, BigDecimal price) {
        invoiceCorrections.adjust(new EInvoiceCorrections.Adjust(invoice.id(), basis, "review L2", null,
                List.of(new EInvoiceCorrections.LineChange(invoice.lines().getFirst().id(), cartons, price, null,
                        null)), null, null));
    }

    /**
     * {@code pieces} of the invoice's goods line accepted back and refunded. The return itself is a bare row: the
     * adjustment only has to be able to name it ({@code fk_einvoice_return_request}).
     */
    private ReturnResolved returned(EInvoice.Snapshot invoice, int pieces) {
        UUID returnId = com.stockflow.common.id.Identifiers.newId();
        String rma = "RMA-L2-" + returnId.toString().substring(24);
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO ordering.return_request (id, rma_number, order_id, customer_id, status, reason_code,
                                                     requested_at, order_number, request_id, channel, requested_by)
                VALUES (?, ?, ?, ?, 'REQUESTED', 'PRINT_DEFECT', NOW(), ?, ?, 'PORTAL', ?)""", returnId, rma,
                invoice.orderId(), invoice.customerId(), invoice.orderNumber(), UUID.randomUUID(), staff));
        EInvoiceLine goods = invoice.lines().getFirst();
        return new ReturnResolved(returnId, rma, invoice.orderId(), invoice.orderNumber(), invoice.customerId(), null,
                null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "VND", true,
                List.of(new ReturnResolved.Line(goods.orderLineId(), goods.sku(), pieces, 0, pieces, BigDecimal.ZERO)),
                staff, Instant.now());
    }

    @Test
    @DisplayName("review L2: a return is taken off what the invoice still holds - what it invoiced, with the pieces "
            + "an error's adjustment added or took off; a wrong price and a trade discount move none")
    void aReturnCountsThePiecesAnErrorAdjustmentMoved() {
        // 250 pieces invoiced, and one carton more by an error's adjustment: 300 held.
        EInvoice.Snapshot raised = issuedDelivery();
        UUID raisedLine = raised.lines().getFirst().orderLineId();
        adjusted(raised, CorrectionBasis.ERROR, 1, null);
        // 250 pieces invoiced, and one carton fewer by an error's adjustment: 200 held. The price was 1,000 a carton
        // too low on all five, and two cartons get a discount of 500 each: money, not pieces.
        EInvoice.Snapshot lowered = issuedDelivery();
        UUID loweredLine = lowered.lines().getFirst().orderLineId();
        adjusted(lowered, CorrectionBasis.ERROR, -1, null);
        adjusted(lowered, CorrectionBasis.ERROR, 5, new BigDecimal("1000"));
        adjusted(lowered, CorrectionBasis.TRADE_DISCOUNT, -2, new BigDecimal("500"));
        assertThat(invoices.piecesTakenOff(raised.id())).as("pieces put back count below zero")
                .isEqualTo(Map.of(raisedLine, -50L));
        assertThat(invoices.piecesTakenOff(lowered.id())).as("only the line that changed a quantity")
                .isEqualTo(Map.of(loweredLine, 50L));

        EInvoiceCorrections.ReturnAdjustments ofRaised = invoiceCorrections.drawUpForReturn(returned(raised, 300));
        EInvoiceCorrections.ReturnAdjustments ofLowered = invoiceCorrections.drawUpForReturn(returned(lowered, 250));

        assertThat(ofRaised.drafts()).hasSize(1);
        assertThat(ofRaised.drafts().getFirst().lines()).as("all 300 held come off, the added carton with them")
                .extracting(EInvoiceLine::pieces).containsExactly(-300L);
        assertThat(ofRaised.unplaced()).isEmpty();
        assertThat(ofLowered.drafts()).hasSize(1);
        assertThat(ofLowered.drafts().getFirst().lines()).as("the carton the error took off is not returned again")
                .extracting(EInvoiceLine::pieces).containsExactly(-200L);
        assertThat(ofLowered.unplaced()).as("left to the accountant").isEqualTo(Map.of(loweredLine, 50L));
        assertThat(invoices.piecesTakenOff(lowered.id())).as("the return's own adjustment counts from here on")
                .isEqualTo(Map.of(loweredLine, 250L));
    }

    @Test
    @DisplayName("automatic issuing: on, a complete draft is issued by the job; a flagged one waits for the accountant")
    void automaticIssuing() {
        EInvoice.Snapshot complete = delivered(1).draft();
        buyer(null, null);
        EInvoice.Snapshot flagged = delivered(1).draft();
        assertThat(flagged.warnings()).isNotEmpty();

        assertThat(jobs.issueAutomatically()).as("off: the accountant issues (21 BR-02)").isZero();
        assertThat(queries.get(complete.id()).status()).isEqualTo(EInvoiceStatus.DRAFT);

        var before = settings.get();
        assertThat(before.provider().provider()).isEqualTo("SANDBOX");
        assertThat(before.provider().environment()).isEqualTo("SANDBOX");
        assertThat(before.provider().secretSet()).isFalse();
        assertThat(before.series()).extracting(series -> series.symbol().substring(0, 1) + series.symbol().substring(3))
                .contains("CTSB");
        var on = settings.changeAutoIssue(true, before.provider().version());
        assertThat(on.provider().autoIssue()).isTrue();
        assertThatThrownBy(() -> settings.changeAutoIssue(false, before.provider().version())).as("a stale version")
                .extracting(EInvoiceIntegrationTest::code).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);

        assertThat(jobs.issueAutomatically()).isGreaterThanOrEqualTo(1);

        EInvoice.Snapshot issued = FirstSend.awaited(issuance, queries, complete.id());
        assertThat(issued.status()).isEqualTo(EInvoiceStatus.ISSUED);
        assertThat(issued.submittedBy()).as("issued by the system").isNull();
        assertThat(queries.get(flagged.id()).status()).as("something to look at: left to the accountant")
                .isEqualTo(EInvoiceStatus.DRAFT);
        assertThat(calls(flagged.id())).isEmpty();
    }

    @Test
    @DisplayName("NĐ 254 Phụ lục điểm 7: a draft close to its deadline is announced once; overdue ones are listed first")
    void theDeadlineIsAnnouncedOnce() {
        EInvoice.Snapshot draft = delivered(1).draft();
        assertThat(jdbc.queryForObject("SELECT due_warned_at IS NULL FROM invoicing.einvoice WHERE id = ?",
                Boolean.class, draft.id())).isTrue();
        // More than a day ahead: nothing to announce yet. Then the deadline is an hour away.
        tx.executeWithoutResult(s -> jdbc.update("UPDATE invoicing.einvoice SET issue_due_at = NOW() + interval '3 days' "
                + "WHERE id = ?", draft.id()));
        jobs.warnDue();
        assertThat(jdbc.queryForObject("SELECT due_warned_at IS NULL FROM invoicing.einvoice WHERE id = ?",
                Boolean.class, draft.id())).isTrue();
        tx.executeWithoutResult(s -> jdbc.update("UPDATE invoicing.einvoice SET issue_due_at = NOW() - interval '1 hour' "
                + "WHERE id = ?", draft.id()));

        assertThat(jobs.warnDue()).isGreaterThanOrEqualTo(1);

        Instant warned = jdbc.queryForObject("SELECT due_warned_at FROM invoicing.einvoice WHERE id = ?",
                java.sql.Timestamp.class, draft.id()).toInstant();
        assertThat(warned).isNotNull();
        jobs.warnDue();
        assertThat(jdbc.queryForObject("SELECT due_warned_at FROM invoicing.einvoice WHERE id = ?",
                java.sql.Timestamp.class, draft.id()).toInstant()).as("told once").isEqualTo(warned);

        // Past its deadline and still to issue: what the accountant's list is asked for.
        assertThat(queries.list(new EInvoiceQueries.Filter(null, null, draft.orderId(), null, null, null,
                Instant.now()), 0, 20).items()).extracting(EInvoiceQueries.Listing::id).containsExactly(draft.id());
        assertThat(queries.list(new EInvoiceQueries.Filter(null, null, draft.orderId(), null, null, null,
                Instant.now().minus(Duration.ofDays(2))), 0, 20).items()).isEmpty();
        // Issued late, it still is issued - and no longer among what is due.
        EInvoice.Snapshot current = queries.get(draft.id());
        assertThat(FirstSend.issue(issuance, queries, draft.id(), current.version(), accountant).status()).isEqualTo(EInvoiceStatus.ISSUED);
        assertThat(queries.list(new EInvoiceQueries.Filter(null, null, draft.orderId(), null, null, null,
                Instant.now()), 0, 20).items()).isEmpty();
    }
}
