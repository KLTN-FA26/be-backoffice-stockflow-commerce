package com.stockflow.procurement;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.procurement.internal.domain.InvoiceVarianceKind;
import com.stockflow.procurement.internal.domain.SupplierInvoiceStatus;
import com.stockflow.procurement.internal.service.GoodsReceipts;
import com.stockflow.procurement.internal.service.SupplierInvoices;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * SCRUM-308/309 against a real database: invoices entered against orders received through the real receiving flow,
 * matched three ways - quantities against what was received and passed QC, less what MATCHED invoices billed; prices
 * against the configured 2 % tolerance; tax rates - and the rules the database states again. Each test builds its own
 * CONFIRMED order on the demo supplier, items and HCM map, as {@code GoodsReceiptIntegrationTest} does.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = "stockflow.upload-inspection.enabled=false")
class SupplierInvoiceIntegrationTest {

    private static final String SOFA = "SOFA-3S-GREY";   // no QC, no lot
    private static final String TABLE = "TABLE-OAK-160"; // QC, lot and expiry

    @Autowired SupplierInvoices invoices;
    @Autowired GoodsReceipts receipts;
    // The test profile stores files locally, which hands out no browser link; the scan's download link needs one. Signing
    // itself is MinioMediaIntegrationTest's; here the link only has to look like the S3 provider's.
    @MockitoSpyBean FileStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;

    private UUID clerk;
    private UUID qc;
    private UUID accountant;
    private UUID supplier;
    private String lot;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        doAnswer(call -> "http://objects.test/" + call.getArgument(0)).when(storage).presignedDownloadUrl(anyString(),
                any());
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        qc = tx.execute(s -> ReferenceRows.user(entityManager));
        accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        supplier = jdbc.queryForObject("SELECT id FROM procurement.suppliers WHERE code = 'GOVIET'", UUID.class);
        lot = "INV-" + UUID.randomUUID().toString().substring(0, 8);
        today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
    }

    /** An order of the demo supplier in {@code status}, every line at 1 000 and {@code taxRate}; returns its id. */
    private UUID order(String status, String taxRate, Object... skuAndQty) {
        UUID po = Identifiers.newId();
        UUID revision = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            UUID submitter = ReferenceRows.user(entityManager);
            UUID approver = ReferenceRows.user(entityManager);
            boolean approved = !status.equals("DRAFT");
            jdbc.update("""
                    INSERT INTO procurement.purchase_orders (id, po_number, status, supplier_id, warehouse_id, currency,
                        order_date, submitted_at, submitted_by, approved_at, approved_by, confirmed_at, confirmed_by,
                        active_revision_id, version, created_at)
                    SELECT ?, ?, ?, s.id, w.id, 'VND', CURRENT_DATE, ?, ?, ?, ?, ?, ?, ?, 0, NOW()
                      FROM procurement.suppliers s, warehouse.warehouse w WHERE s.code = 'GOVIET' AND w.prefix = 'HCM'""",
                    po, "PO-INV-" + po.toString().substring(24), status,
                    approved ? java.sql.Timestamp.from(java.time.Instant.now()) : null, approved ? submitter : null,
                    approved ? java.sql.Timestamp.from(java.time.Instant.now()) : null, approved ? approver : null,
                    approved ? java.sql.Timestamp.from(java.time.Instant.now()) : null, approved ? approver : null,
                    approved ? revision : null);
            if (approved) {
                jdbc.update("""
                        INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header,
                            changed_by, version, created_at) VALUES (?, ?, 0, 'INITIAL', '{}'::jsonb, ?, 0, NOW())""",
                        revision, po, submitter);
            }
            for (int i = 0; i + 1 < skuAndQty.length; i += 2) {
                jdbc.update("""
                        INSERT INTO procurement.purchase_order_lines (id, po_id, po_revision_id, line_no,
                            inventory_item_id, ordered_qty, unit_price, tax_rate, version, created_at)
                        SELECT ?, ?, ?, ?, id, ?, 1000, ?, 0, NOW() FROM inventory.inventory_items WHERE sku = ?""",
                        Identifiers.newId(), po, approved ? revision : null, i / 2 + 1, skuAndQty[i + 1],
                        new BigDecimal(taxRate), skuAndQty[i]);
            }
        });
        return po;
    }

    private UUID poLine(UUID po, String sku) {
        return jdbc.queryForObject("""
                SELECT l.id FROM procurement.purchase_order_lines l JOIN inventory.inventory_items i
                  ON i.id = l.inventory_item_id WHERE l.po_id = ? AND i.sku = ?""", UUID.class, po, sku);
    }

    /** Counts {@code quantity} of {@code sku} in on one receipt and posts it; returns the receipt. */
    private GoodsReceipts.ReceiptView received(UUID po, String sku, int quantity) {
        GoodsReceipts.ReceiptView draft = receipts.create(new GoodsReceipts.Create(po, "DN-" + lot, null), clerk);
        boolean tracked = sku.equals(TABLE);
        receipts.replaceLines(draft.id(), List.of(new GoodsReceipts.NewLine(poLine(po, sku), quantity,
                tracked ? lot : null, tracked ? LocalDate.now().plusYears(1) : null, "HCM-RCV01", null)));
        return receipts.confirm(draft.id(), clerk);
    }

    private SupplierInvoices.InvoiceView enter(UUID po, String number, SupplierInvoices.LineInput... lines) {
        return invoices.enter(new SupplierInvoices.Enter(supplier, po, number, today, today.plusDays(30), null, null,
                List.of(lines)), accountant);
    }

    private static SupplierInvoices.LineInput billed(UUID poLine, int quantity, String price, String taxRate) {
        return new SupplierInvoices.LineInput(poLine, null, quantity, new BigDecimal(price), new BigDecimal(taxRate));
    }

    private static String number() {
        return "HD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private static ErrorCode code(ThrowableAssert.ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    @Test
    @DisplayName("an invoice of the units received matches; a second invoice of the same units is an over-invoice")
    void billedOnce() {
        UUID po = order("CONFIRMED", "10", SOFA, 4);
        received(po, SOFA, 4);
        String number = number();

        SupplierInvoices.InvoiceView first = enter(po, number, billed(poLine(po, SOFA), 4, "1000", "10"));
        assertThat(first.status()).isEqualTo(SupplierInvoiceStatus.DRAFT);
        assertThat(first.subtotal()).isEqualByComparingTo("4000");
        assertThat(first.taxTotal()).isEqualByComparingTo("400");
        assertThat(first.total()).isEqualByComparingTo("4400");
        assertThat(first.currency()).isEqualTo("VND");
        assertThat(first.lines()).singleElement().satisfies(line -> {
            assertThat(line.sku()).isEqualTo(SOFA);
            assertThat(line.purchaseOrderId()).isEqualTo(po);
        });

        SupplierInvoices.InvoiceView matched = invoices.match(first.id(), accountant);
        assertThat(matched.status()).isEqualTo(SupplierInvoiceStatus.MATCHED);
        assertThat(matched.variances()).isEmpty();
        assertThat(matched.priceTolerancePercent()).isEqualByComparingTo("2");
        assertThat(code(() -> invoices.revise(first.id(), new SupplierInvoices.Revise(po, number, today, null, null,
                List.of(billed(poLine(po, SOFA), 3, "1000", "10")), null))))
                .as("a MATCHED invoice has billed its units").isEqualTo(ErrorCode.SUPPLIER_INVOICE_INVALID_TRANSITION);

        assertThat(code(() -> enter(po, number.toLowerCase(), billed(poLine(po, SOFA), 1, "1000", "10"))))
                .as("BR-INV-001, case aside").isEqualTo(ErrorCode.SUPPLIER_INVOICE_NUMBER_TAKEN);
        SupplierInvoices.InvoiceView second = enter(po, number(), billed(poLine(po, SOFA), 1, "1000", "10"));
        assertThat(invoices.match(second.id(), accountant)).satisfies(view -> {
            assertThat(view.status()).isEqualTo(SupplierInvoiceStatus.EXCEPTION);
            assertThat(view.variances()).extracting(SupplierInvoices.VarianceView::kind, v -> v.expected().intValue(),
                    v -> v.amount().toPlainString()).containsExactly(tuple(InvoiceVarianceKind.OVER_INVOICED, 0,
                    "1000.00"));
        });
        assertThat(invoices.basis(po).lines()).singleElement().satisfies(line -> {
            assertThat(line.billable()).isEqualTo(4);
            assertThat(line.invoiced()).as("the MATCHED invoice only").isEqualTo(4);
        });
    }

    @Test
    @DisplayName("only what QC accepted is billable: before QC the units are awaited, after it the rejected and the "
            + "quarantined are not billed")
    void qcDecidesWhatIsBillable() {
        UUID po = order("CONFIRMED", "0", TABLE, 6);
        GoodsReceipts.ReceiptView receipt = received(po, TABLE, 6);
        SupplierInvoices.InvoiceView invoice = enter(po, number(), billed(poLine(po, TABLE), 6, "1000", "0"));

        assertThat(invoices.match(invoice.id(), accountant).variances())
                .extracting(SupplierInvoices.VarianceView::kind).containsExactly(InvoiceVarianceKind.AWAITING_QC);

        UUID line = receipt.lines().getFirst().id();
        receipts.moveToQc(receipt.id(), line, "HCM-QCA01", clerk);
        receipts.inspect(receipt.id(), line, new GoodsReceipts.Decision(3,
                new GoodsReceipts.Part(2, "HCM-QC01", "scratched top"),
                new GoodsReceipts.Part(1, "HCM-RTV01", "cracked leg")), qc);
        assertThat(invoices.match(invoice.id(), accountant).variances())
                .extracting(SupplierInvoices.VarianceView::kind, v -> v.expected().intValue())
                .containsExactly(tuple(InvoiceVarianceKind.OVER_INVOICED, 3));

        SupplierInvoices.InvoiceView corrected = invoices.revise(invoice.id(), new SupplierInvoices.Revise(po,
                invoice.invoiceNumber(), today, null, "only the accepted units",
                List.of(new SupplierInvoices.LineInput(poLine(po, TABLE), line, 3, new BigDecimal("1000"),
                        BigDecimal.ZERO)), null));
        assertThat(corrected.status()).isEqualTo(SupplierInvoiceStatus.DRAFT);
        assertThat(corrected.variances()).isEmpty();
        assertThat(invoices.match(invoice.id(), accountant).status()).isEqualTo(SupplierInvoiceStatus.MATCHED);
    }

    @Test
    @DisplayName("a price beyond 2 % and another tax rate are variances, kept with their amounts; corrected, it matches")
    void priceAndTax() {
        UUID po = order("CONFIRMED", "10", SOFA, 2);
        received(po, SOFA, 2);
        SupplierInvoices.InvoiceView invoice = enter(po, number(), billed(poLine(po, SOFA), 2, "1025", "8"));

        SupplierInvoices.InvoiceView off = invoices.match(invoice.id(), accountant);
        assertThat(off.status()).isEqualTo(SupplierInvoiceStatus.EXCEPTION);
        assertThat(off.variances()).extracting(SupplierInvoices.VarianceView::kind, v -> v.amount().toPlainString())
                .containsExactly(tuple(InvoiceVarianceKind.PRICE, "50.00"), tuple(InvoiceVarianceKind.TAX_RATE, "-41.00"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM procurement.supplier_invoice_variances WHERE invoice_id = ?""", Long.class,
                invoice.id())).isEqualTo(2L);

        assertThat(code(() -> invoices.revise(invoice.id(), new SupplierInvoices.Revise(po, invoice.invoiceNumber(),
                today, null, null, List.of(billed(poLine(po, SOFA), 2, "1020", "10")), 0L))))
                .as("a version read before the match").isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        invoices.revise(invoice.id(), new SupplierInvoices.Revise(po, invoice.invoiceNumber(), today, null, null,
                List.of(billed(poLine(po, SOFA), 2, "1020", "10")), null));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM procurement.supplier_invoice_variances WHERE invoice_id = ?""", Long.class,
                invoice.id())).as("an edit drops what the last match found").isZero();
        assertThat(invoices.match(invoice.id(), accountant).status()).isEqualTo(SupplierInvoiceStatus.MATCHED);
    }

    @Test
    @DisplayName("lines bill the supplier's own approved orders, in their currency, and receipt lines of the PO line")
    void entryRefusals() {
        UUID po = order("CONFIRMED", "0", SOFA, 2, TABLE, 1);
        GoodsReceipts.ReceiptView receipt = received(po, SOFA, 2);
        UUID other = Identifiers.newId();
        // In a transaction, as every write of these tests: the pool does not commit a statement run outside one.
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO procurement.suppliers (id, code, name, status, currency, version, created_at)
                VALUES (?, ?, 'Other supplier', 'ACTIVE', 'VND', 0, NOW())""", other, "OT" + other.toString()
                .substring(30).toUpperCase()));

        assertThat(code(() -> invoices.enter(new SupplierInvoices.Enter(other, null, number(), today, null, null, null,
                List.of(billed(poLine(po, SOFA), 1, "1000", "0"))), accountant)))
                .isEqualTo(ErrorCode.SUPPLIER_INVOICE_LINE_INVALID);
        assertThat(code(() -> invoices.enter(new SupplierInvoices.Enter(supplier, po, number(), today, null, "USD",
                null, List.of(billed(poLine(po, SOFA), 1, "1000", "0"))), accountant)))
                .isEqualTo(ErrorCode.SUPPLIER_INVOICE_CURRENCY_MISMATCH);
        assertThat(code(() -> enter(po, number(), new SupplierInvoices.LineInput(poLine(po, TABLE),
                receipt.lines().getFirst().id(), 1, new BigDecimal("1000"), BigDecimal.ZERO))))
                .as("a delivery of another PO line").isEqualTo(ErrorCode.SUPPLIER_INVOICE_LINE_INVALID);
        UUID draft = order("DRAFT", "0", SOFA, 1);
        assertThat(code(() -> enter(draft, number(), billed(poLine(draft, SOFA), 1, "1000", "0"))))
                .isEqualTo(ErrorCode.PURCHASE_ORDER_NOT_INVOICEABLE);
        assertThat(code(() -> enter(po, number(), billed(poLine(draft, SOFA), 1, "1000", "0"))))
                .as("a line of another order than the one named").isEqualTo(ErrorCode.SUPPLIER_INVOICE_LINE_INVALID);
        assertThat(code(() -> invoices.enter(new SupplierInvoices.Enter(supplier, po, number(), today.plusDays(1), null,
                null, null, List.of(billed(poLine(po, SOFA), 1, "1000", "0"))), accountant)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("an invoice entered against the wrong order is corrected to the right one, its number kept")
    void wrongOrderCorrected() {
        UUID wrong = order("CONFIRMED", "0", SOFA, 2);
        UUID right = order("CONFIRMED", "0", SOFA, 2);
        received(right, SOFA, 2);
        SupplierInvoices.InvoiceView invoice = enter(wrong, number(), billed(poLine(wrong, SOFA), 2, "1000", "0"));
        assertThat(invoices.match(invoice.id(), accountant).status()).isEqualTo(SupplierInvoiceStatus.EXCEPTION);

        SupplierInvoices.InvoiceView corrected = invoices.revise(invoice.id(), new SupplierInvoices.Revise(right,
                invoice.invoiceNumber(), today, null, null, List.of(billed(poLine(right, SOFA), 2, "1000", "0")),
                null));
        assertThat(corrected.purchaseOrderId()).isEqualTo(right);
        assertThat(corrected.lines()).extracting(SupplierInvoices.LineView::purchaseOrderId).containsExactly(right);
        assertThat(invoices.match(invoice.id(), accountant).status()).isEqualTo(SupplierInvoiceStatus.MATCHED);
    }

    @Test
    @DisplayName("an invoice over several orders of the supplier is matched line by line, against each order")
    void severalOrders() {
        UUID first = order("CONFIRMED", "0", SOFA, 2);
        UUID second = order("CONFIRMED", "0", SOFA, 3);
        received(first, SOFA, 2);
        received(second, SOFA, 1);
        SupplierInvoices.InvoiceView invoice = enter(null, number(), billed(poLine(first, SOFA), 2, "1000", "0"),
                billed(poLine(second, SOFA), 3, "1000", "0"));
        assertThat(invoices.match(invoice.id(), accountant).variances())
                .extracting(SupplierInvoices.VarianceView::kind, v -> v.expected().intValue())
                .containsExactly(tuple(InvoiceVarianceKind.OVER_INVOICED, 1));
        assertThat(invoices.list(null, supplier, second, null, null, null, 0, 20, null).items())
                .extracting(SupplierInvoices.Row::id, SupplierInvoices.Row::varianceCount)
                .contains(tuple(invoice.id(), 1));
    }

    @Test
    @DisplayName("cancelled with a reason it is closed; the scan is kept and served by a link")
    void cancelAndScan() {
        UUID po = order("CONFIRMED", "0", SOFA, 1);
        SupplierInvoices.InvoiceView invoice = enter(po, number(), billed(poLine(po, SOFA), 1, "1000", "0"));
        byte[] pdf = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        SupplierInvoices.InvoiceView scanned = invoices.attachScan(invoice.id(), new FileUpload("hd.pdf",
                "application/pdf", pdf.length, new ByteArrayInputStream(pdf)));
        assertThat(scanned.scanName()).isEqualTo("hd.pdf");
        assertThat(invoices.scanLink(invoice.id()).url()).isNotBlank();

        assertThat(code(() -> invoices.cancel(invoice.id(), " ", accountant))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(invoices.cancel(invoice.id(), "Nhà cung cấp thu hồi hoá đơn", accountant).status())
                .isEqualTo(SupplierInvoiceStatus.CANCELLED);
        assertThat(code(() -> invoices.match(invoice.id(), accountant)))
                .isEqualTo(ErrorCode.SUPPLIER_INVOICE_INVALID_TRANSITION);
    }

    @Test
    @DisplayName("the database states it again: a MATCHED invoice has no variance, a cancelled one says why")
    void databaseRules() {
        UUID po = order("CONFIRMED", "0", SOFA, 1);
        SupplierInvoices.InvoiceView invoice = enter(po, number(), billed(poLine(po, SOFA), 2, "1000", "0"));
        invoices.match(invoice.id(), accountant);

        // The match rule is a deferred trigger: checked here, not at a commit the template would report otherwise.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE procurement.supplier_invoices SET status = 'MATCHED' WHERE id = ?", invoice.id());
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        })).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE procurement.supplier_invoices SET status = 'CANCELLED' WHERE id = ?", invoice.id())))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM procurement.supplier_invoices WHERE id = ?", String.class,
                invoice.id())).isEqualTo("EXCEPTION");
    }
}
