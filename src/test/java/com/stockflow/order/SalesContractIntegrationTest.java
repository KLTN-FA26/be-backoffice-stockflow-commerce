package com.stockflow.order;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.order.api.FeeKind;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderFee;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 4B-4 (kltn-docs 14 §4.3 steps 11-17, §5.2, BR-12, BR-13, BR-14, BR-18, BR-23; 18 BR-05) against the real database: a
 * contract is drawn up for a draft order from the defaults, its schedule and plan edited, sent as a PDF the customer can
 * read, recorded signed with the signed file, and locked from then on - by the service and by the database.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = "stockflow.upload-inspection.enabled=false")
class SalesContractIntegrationTest {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired SalesContracts contracts;
    @Autowired OrderRepository orders;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired com.stockflow.common.storage.FileStorage storage;
    @Autowired List<com.stockflow.common.storage.StoredFileReferences> references;

    private CurrentUser sales;
    private UUID customerId;
    private CustomerSkuRows.Issued sku;
    private LocalDate asked;

    @BeforeEach
    void setUp() {
        UUID salesUser = tx.execute(s -> ReferenceRows.user(entityManager));
        sales = new CurrentUser(salesUser, "sales-" + salesUser, Set.of(Role.SALES_STAFF), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
        customerId = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, full_name, email, tax_code, status, version, created_at)
                VALUES (?, 'Cong ty Hop Dong', ?, ?, 'ACTIVE', 0, NOW())""",
                customerId, "contract-" + customerId + "@example.com", "03" + (10_000_000 + ThreadLocalRandom.current()
                        .nextInt(89_999_999))));
        sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customerId, 50, "STANDARD", true);
        asked = LocalDate.now(VIETNAM).plusDays(30);
    }

    private void legalProfile() {
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer_legal_profile (id, customer_id, registered_address, representative_name,
                    representative_title, version, created_at)
                VALUES (?, ?, '12 Nguyen Hue, Ho Chi Minh City', 'Tran Van B', 'Director', 0, NOW())""",
                Identifiers.newId(), customerId));
    }

    /** A draft order of 20 cartons of 50 at 2,000 a piece with 10% VAT, and a shipping fee: 2,530,000 with tax. */
    private UUID draftOrder() {
        return tx.execute(s -> {
            OrderLine line = Order.line(Identifiers.newId(), new Sku(sku.sku()), 1000, Money.vnd(2_000), null)
                    .withCartons(sku.uomConversionId(), 20, 50).withTax(BigDecimal.TEN).withRequestedDeliveryDate(asked);
            Order order = Order.draft(OrderNumber.of(LocalDate.now(VIETNAM), 800_000
                            + ThreadLocalRandom.current().nextInt(199_999)), customerId, Identifiers.newId(),
                    List.of(line), Instant.now());
            order.attachFees(List.of(OrderFee.of(Identifiers.newId(), FeeKind.SHIPPING_FEE, null,
                    Money.vnd(300_000), BigDecimal.TEN)));
            order.applyTerms(PaymentTerm.PREPAID, null, null, null);
            return orders.save(order).id().value();
        });
    }

    private <T> T asSales(Supplier<T> call) {
        return WithCurrentUser.call(sales, DataScope.ALL, call);
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    /** What the order module's references keep of the documents category. */
    private record SalesContractFileReferencesProbe(List<com.stockflow.common.storage.StoredFileReferences> all) {
        Set<String> referenced(Set<String> keys) {
            Set<String> kept = new java.util.HashSet<>();
            all.stream().filter(r -> r.getClass().getName().contains("SalesContractFileReferences"))
                    .forEach(r -> kept.addAll(r.referenced(com.stockflow.common.storage.FileCategory.DOCUMENT, keys)));
            return kept;
        }
    }

    /** A one-page PDF standing for the scan of the signed paper: the upload checks it is a real PDF. */
    private static byte[] signedScan() throws java.io.IOException {
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            var bytes = new java.io.ByteArrayOutputStream();
            document.save(bytes);
            return bytes.toByteArray();
        }
    }

    private UUID lineOf(UUID orderId) {
        return jdbc.queryForObject("SELECT id FROM ordering.order_line WHERE order_id = ?", UUID.class, orderId);
    }

    @Test
    @DisplayName("18 BR-05: no contract before the customer's registered address and legal representative are known")
    void noLegalProfileNoContract() {
        UUID orderId = draftOrder();
        assertThatThrownBy(() -> asSales(() -> contracts.create(sales, orderId, null)))
                .extracting(SalesContractIntegrationTest::code).isEqualTo(ErrorCode.CUSTOMER_LEGAL_PROFILE_MISSING);
    }

    @Test
    @DisplayName("drawn up from the defaults, edited, sent as a PDF, signed with the file; locked from then on")
    void drawnUpSentSignedAndLocked() throws Exception {
        legalProfile();
        UUID orderId = draftOrder();
        UUID line = lineOf(orderId);

        SalesContracts.ContractView drafted = asSales(() -> contracts.create(sales, orderId, null));
        assertThat(drafted.status()).isEqualTo("DRAFT");
        assertThat(drafted.contractNumber()).startsWith("HD-").endsWith("-1");
        assertThat(drafted.freeSampleRounds()).as("BR-18, as configured").isEqualTo(2);
        assertThat(drafted.buyer().representative()).isEqualTo("Tran Van B");
        assertThat(drafted.seller().name()).isEqualTo("DEMO STOCKFLOW CO LTD");
        assertThat(drafted.totals().totalAmount()).as("the order's total with tax").isEqualByComparingTo("2530000");
        assertThat(drafted.deliverySchedule()).singleElement().satisfies(batch -> {
            assertThat(batch.windowStart()).as("the day the customer asked for").isEqualTo(asked);
            assertThat(batch.lines()).singleElement().satisfies(l -> {
                assertThat(l.cartons()).isEqualTo(20);
                assertThat(l.units()).isEqualTo(1000);
            });
            assertThat(batch.value()).as("the goods with tax").isEqualByComparingTo("2200000");
        });
        assertThat(drafted.paymentPlan()).singleElement().satisfies(i -> {
            assertThat(i.milestone()).as("a prepaid customer's default: everything on signing").isEqualTo("ON_SIGNING");
            assertThat(i.amount()).isEqualByComparingTo("2530000");
        });
        assertThatThrownBy(() -> asSales(() -> contracts.create(sales, orderId, null)))
                .as("BR-12").extracting(SalesContractIntegrationTest::code).isEqualTo(ErrorCode.SALES_CONTRACT_EXISTS);

        // Two batches; 30% on signing, the second batch's goods before it is handed over, the rest before delivery.
        SalesContracts.ContractView edited = asSales(() -> contracts.edit(sales, orderId, drafted.version(),
                new SalesContracts.Draft(3, List.of(
                        new SalesContracts.Batch(asked, asked.plusDays(2), List.of(new SalesContracts.BatchLine(line, 8))),
                        new SalesContracts.Batch(asked.plusDays(14), asked.plusDays(14),
                                List.of(new SalesContracts.BatchLine(line, 12)))),
                        List.of(new SalesContracts.Installment("Dat coc", "ON_SIGNING", null, new BigDecimal("30"), 3,
                                        null),
                                new SalesContracts.Installment("Dot 2", "BEFORE_DELIVERY", new BigDecimal("1320000"),
                                        null, null, 2),
                                new SalesContracts.Installment("Con lai", "BEFORE_DELIVERY", new BigDecimal("451000"),
                                        null, null, null)))));
        assertThat(edited.freeSampleRounds()).isEqualTo(3);
        assertThat(edited.deliverySchedule()).extracting(SalesContracts.BatchView::value)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("880000"), new BigDecimal("1320000"));
        assertThat(edited.paymentPlan()).extracting(SalesContracts.InstallmentView::amount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("759000"), new BigDecimal("1320000"), new BigDecimal("451000"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.delivery_batch b JOIN ordering.sales_contract c "
                + "ON c.id = b.contract_id WHERE c.order_id = ?", Long.class, orderId)).isEqualTo(2);
        assertThatThrownBy(() -> asSales(() -> contracts.edit(sales, orderId, drafted.version(),
                new SalesContracts.Draft(1, null, null))))
                .as("a stale version").extracting(SalesContractIntegrationTest::code)
                .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        // The schedule alone edited moves the version too: whoever still holds the one before is refused.
        SalesContracts.ContractView rescheduled = asSales(() -> contracts.edit(sales, orderId, edited.version(),
                new SalesContracts.Draft(null, List.of(
                        new SalesContracts.Batch(asked, asked.plusDays(3), List.of(new SalesContracts.BatchLine(line, 8))),
                        new SalesContracts.Batch(asked.plusDays(14), asked.plusDays(14),
                                List.of(new SalesContracts.BatchLine(line, 12)))), null)));
        assertThat(rescheduled.version()).isGreaterThan(edited.version());
        assertThat(rescheduled.freeSampleRounds()).as("kept").isEqualTo(3);
        assertThat(rescheduled.paymentPlan()).as("kept").hasSize(3);
        assertThatThrownBy(() -> asSales(() -> contracts.edit(sales, orderId, edited.version(),
                new SalesContracts.Draft(null, null, List.of(new SalesContracts.Installment("All", "ON_SIGNING", null,
                        new BigDecimal("100"), null, null))))))
                .as("a stale version, after a schedule-only edit").extracting(SalesContractIntegrationTest::code)
                .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);

        byte[] preview = asSales(() -> contracts.preview(orderId));
        assertThat(new String(preview, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        try (var pdf = Loader.loadPDF(preview)) {
            String text = new PDFTextStripper().getText(pdf);
            assertThat(text).contains(edited.contractNumber(), "Cong ty Hop Dong", "Tran Van B", sku.sku());
        }

        assertThatThrownBy(() -> asSales(() -> contracts.send(sales, orderId, edited.version())))
                .as("sent from a stale version: nothing drawn is kept").extracting(SalesContractIntegrationTest::code)
                .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        SalesContracts.ContractView sent = asSales(() -> contracts.send(sales, orderId, rescheduled.version()));
        assertThat(sent.status()).isEqualTo("SENT");
        assertThat(sent.sentAt()).isNotNull();
        assertThat(sent.creditWarning()).as("nothing after delivery").isNull();
        // The PDF sent is kept (links to it need S3, which tests run without).
        String sentKey = jdbc.queryForObject("SELECT sent_document_key FROM ordering.sales_contract WHERE id = ?",
                String.class, sent.id());
        assertThat(sentKey).startsWith("documents/");
        assertThat(storage.exists(sentKey)).isTrue();
        assertThatThrownBy(() -> asSales(() -> contracts.edit(sales, orderId, sent.version(),
                new SalesContracts.Draft(1, null, null))))
                .as("BR-14").extracting(SalesContractIntegrationTest::code)
                .isEqualTo(ErrorCode.SALES_CONTRACT_STATUS_INVALID);
        // The database keeps a sent contract's schedule too.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("UPDATE ordering.delivery_batch SET "
                + "window_end = window_end + 1 WHERE contract_id = ?", sent.id())))
                .hasRootCauseInstanceOf(SQLException.class).rootCause()
                .hasMessageContaining("do not change (14 BR-14)");

        // The customer reads the contract sent; another customer's order is not there.
        SalesContracts.ContractView customersCopy = contracts.customerContract(customerId, orderId);
        assertThat(customersCopy.contractNumber()).isEqualTo(sent.contractNumber());
        assertThat(customersCopy.sentAt()).isNotNull();
        assertThat(customersCopy.sentBy()).as("who in the back office sent it is not the customer's").isNull();
        assertThatThrownBy(() -> contracts.customerContract(Identifiers.newId(), orderId))
                .extracting(SalesContractIntegrationTest::code).isEqualTo(ErrorCode.NOT_FOUND);

        byte[] scan = signedScan();
        LocalDate today = LocalDate.now(VIETNAM);
        SalesContracts.ContractView signed = asSales(() -> contracts.sign(sales, orderId, sent.version(),
                IncomingFiles.sent(List.of(new FileUpload("hop-dong-da-ky.pdf", "application/pdf", scan.length,
                        new ByteArrayInputStream(scan)))), today));
        assertThat(signed.status()).isEqualTo("SIGNED");
        assertThat(signed.signedOn()).isEqualTo(today);
        assertThat(signed.signedFileName()).isEqualTo("hop-dong-da-ky.pdf");
        String signedKey = jdbc.queryForObject("SELECT signed_file_key FROM ordering.sales_contract WHERE id = ?",
                String.class, signed.id());
        assertThat(signedKey).isNotEqualTo(sentKey);
        assertThat(storage.exists(signedKey)).isTrue();
        assertThat(new SalesContractFileReferencesProbe(references).referenced(Set.of(sentKey, signedKey, "x")))
                .as("the orphan sweep keeps both").containsExactlyInAnyOrder(sentKey, signedKey);
        assertThatThrownBy(() -> asSales(() -> contracts.cancel(sales, orderId, signed.version(), "no")))
                .as("a signed contract is never cancelled").extracting(SalesContractIntegrationTest::code)
                .isEqualTo(ErrorCode.SALES_CONTRACT_STATUS_INVALID);
    }

    @Test
    @DisplayName("cancelled before signing, with a reason, and drawn up again; a draft is not the customer's to read")
    void cancelledAndDrawnUpAgain() {
        legalProfile();
        UUID orderId = draftOrder();
        SalesContracts.ContractView first = asSales(() -> contracts.create(sales, orderId,
                new SalesContracts.Draft(0, null, null)));
        assertThatThrownBy(() -> contracts.customerContract(customerId, orderId))
                .extracting(SalesContractIntegrationTest::code).isEqualTo(ErrorCode.SALES_CONTRACT_NOT_FOUND);
        SalesContracts.ContractView cancelled = asSales(() -> contracts.cancel(sales, orderId, first.version(),
                "The customer wants two batches"));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.cancellationReason()).isEqualTo("The customer wants two batches");
        assertThatThrownBy(() -> contracts.get(orderId))
                .extracting(SalesContractIntegrationTest::code).isEqualTo(ErrorCode.SALES_CONTRACT_NOT_FOUND);

        SalesContracts.ContractView second = asSales(() -> contracts.create(sales, orderId, null));
        assertThat(second.contractNumber()).isEqualTo(first.contractNumber().replaceAll("-1$", "-2"));
        assertThat(contracts.history(orderId)).extracting(SalesContracts.ContractView::status)
                .containsExactly("DRAFT", "CANCELLED");
    }

    @Test
    @DisplayName("BR-13 / BR-23: a schedule or plan that breaks a rule is refused with every rule named")
    void rulesNamed() {
        legalProfile();
        UUID orderId = draftOrder();
        UUID line = lineOf(orderId);
        assertThatThrownBy(() -> asSales(() -> contracts.create(sales, orderId, new SalesContracts.Draft(null,
                List.of(new SalesContracts.Batch(asked, asked, List.of(new SalesContracts.BatchLine(line, 19)))),
                null))))
                .extracting(SalesContractIntegrationTest::code).isEqualTo(ErrorCode.DELIVERY_SCHEDULE_INVALID);
        assertThatThrownBy(() -> asSales(() -> contracts.create(sales, orderId, new SalesContracts.Draft(null, null,
                List.of(new SalesContracts.Installment("All", "AFTER_DELIVERY", null, new BigDecimal("100"), null,
                        null))))))
                .as("15 BR-12: paying after delivery needs approved credit")
                .extracting(SalesContractIntegrationTest::code).isEqualTo(ErrorCode.PAYMENT_SCHEDULE_NEEDS_CREDIT);
        assertThatThrownBy(() -> asSales(() -> contracts.create(sales, orderId, new SalesContracts.Draft(null,
                java.util.Arrays.asList((SalesContracts.Batch) null), null))))
                .as("a null batch is a rule broken, not a crash")
                .extracting(SalesContractIntegrationTest::code).isEqualTo(ErrorCode.DELIVERY_SCHEDULE_INVALID);
        assertThat(contracts.history(orderId)).as("nothing was kept").isEmpty();
    }
}
