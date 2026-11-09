package com.stockflow.order;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.SaveAddressCommand;
import com.stockflow.order.api.FeeKind;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.internal.domain.QuoteStatus;
import com.stockflow.order.internal.service.Quotes;
import com.stockflow.support.Await;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 4B-3 (kltn-docs 14 BR-09, BR-17, BR-20, BR-21; 17 §4.11, D33) against the real database: a SKU produced for the first
 * time gets a plate fee the system adds and Sales prices; a shipping fee is Sales' to add; every line and fee line takes
 * its product's VAT rate (the shipping fee STANDARD); the quote is sent once all of it is known; the order keeps the
 * lines' and fees' VAT and charges the total with it; the dashboard counts its revenue before tax, the fees included.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false", "stockflow.notification.email.provider=smtp",
        "stockflow.notification.email.customer.enabled=false"})
class QuoteVatIntegrationTest {

    private static final Duration ASYNC = Duration.ofSeconds(15);

    @Autowired Quotes quotes;
    @Autowired OrderService orders;
    @Autowired CustomerService customers;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @MockitoBean JavaMailSenderImpl mail;

    private CurrentUser sales;
    private CurrentUser portal;
    private UUID customerId;

    @BeforeEach
    void setUp() {
        UUID salesUser = tx.execute(s -> ReferenceRows.user(entityManager));
        sales = new CurrentUser(salesUser, "sales-" + salesUser, Set.of(Role.SALES_STAFF), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
        UUID portalUser = tx.execute(s -> ReferenceRows.user(entityManager));
        portal = new CurrentUser(portalUser, "portal-" + portalUser, Set.of(Role.CUSTOMER), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
        customerId = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, user_id, full_name, email, phone, status, version, created_at)
                VALUES (?, ?, 'Cong ty Thue', ?, '0901234567', 'ACTIVE', 0, NOW())""",
                customerId, portalUser, "vat-" + customerId + "@example.com"));
        customers.addAddress(customerId, salesUser, true, new SaveAddressCommand("SHIPPING", "Nguyen Van A",
                "0901234567", "1 Le Loi", null, "26734", "Ben Nghe", "79", "Ho Chi Minh", "VN", null, true, null));
    }

    /** 3 cartons of 10 at 3,000 VND a piece from 2 cartons. */
    private Quotes.Draft draft(String sku, List<Quotes.Fee> fees) {
        return new Quotes.Draft(PaymentTerm.PREPAID, null, Instant.now().plus(Duration.ofDays(7)), null,
                List.of(new Quotes.Line(sku, 3, List.of(new Quotes.Tier(2, new BigDecimal("3000"))))), null, fees);
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    @Test
    @DisplayName("a first production: plate fee added, priced by Sales; VAT per line; sent, accepted and placed with the "
            + "total with tax; revenue before tax on the dashboard")
    void aFirstProductionQuotedWithVatAndFees() {
        String sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customerId, 10, "STANDARD", false).sku();
        CustomerSkuRows.stock(jdbc, tx, sku, 100);

        Quotes.QuoteView drafted = quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId,
                draft(sku, List.of())));
        assertThat(drafted.fees()).singleElement().satisfies(fee -> {
            assertThat(fee.kind()).isEqualTo(FeeKind.PLATE_FEE);
            assertThat(fee.sku()).isEqualTo(sku);
            assertThat(fee.amount()).as("the system adds it; Sales prices it").isNull();
            assertThat(fee.taxRate()).isEqualByComparingTo("10");
        });
        assertThat(drafted.proposedTotal()).isNull();
        assertThatThrownBy(() -> quotes.send(sales, drafted.id(), drafted.version()))
                .extracting(QuoteVatIntegrationTest::code).isEqualTo(ErrorCode.QUOTE_NOT_PRICED);

        Quotes.QuoteView priced = quotes.edit(sales, drafted.id(), drafted.version(), draft(sku, List.of(
                new Quotes.Fee(FeeKind.PLATE_FEE, sku, new BigDecimal("500000")),
                new Quotes.Fee(FeeKind.SHIPPING_FEE, null, new BigDecimal("300000")))));
        // 90,000 of goods + 800,000 of fees; VAT 10% each: 9,000 + 50,000 + 30,000.
        assertThat(priced.proposed().subtotal()).isEqualByComparingTo("90000");
        assertThat(priced.proposed().fees()).isEqualByComparingTo("800000");
        assertThat(priced.proposed().tax()).isEqualByComparingTo("89000");
        assertThat(priced.proposedTotal()).isEqualByComparingTo("979000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.quote_fee_line WHERE quote_id = ?", Long.class,
                drafted.id())).isEqualTo(2);

        Quotes.QuoteView sent = quotes.send(sales, drafted.id(), priced.version());
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(offer -> 'fees') FROM ordering.quote_status_history "
                + "WHERE quote_id = ? AND to_status = 'SENT'", Integer.class, sent.id())).as("the offer as sent").isEqualTo(2);
        Quotes.QuoteView accepted = quotes.accept(portal, sent.id(), new Quotes.Acceptance(
                quotes.get(portal, sent.id()).version(), Map.of()));
        assertThat(accepted.status()).isEqualTo(QuoteStatus.ACCEPTED);
        assertThat(accepted.acceptedTotal()).isEqualByComparingTo("979000");

        UUID orderId = quotes.convert(sales, accepted.id(), new Quotes.Conversion(accepted.version(), null, null))
                .convertedOrder().orderId();
        OrderSummary order = orders.findById(orderId).orElseThrow();
        assertThat(order.total().amount()).as("what the customer pays").isEqualByComparingTo("979000");
        assertThat(order.netTotal().amount()).isEqualByComparingTo("890000");
        assertThat(order.charges().taxTotal().amount()).isEqualByComparingTo("89000");
        assertThat(order.charges().fees()).extracting(OrderSummary.FeeSummary::kind)
                .containsExactlyInAnyOrder(FeeKind.PLATE_FEE, FeeKind.SHIPPING_FEE);
        assertThat(order.lines()).singleElement().satisfies(line -> {
            assertThat(line.taxRate()).isEqualByComparingTo("10");
            assertThat(line.taxAmount().amount()).isEqualByComparingTo("9000");
        });
        assertThat(jdbc.queryForObject("SELECT net_amount || '/' || tax_amount || '/' || total_amount FROM "
                + "ordering.customer_order WHERE id = ?", String.class, orderId))
                .isEqualTo("890000.0000/89000.0000/979000.0000");

        // The dashboard (17 §4.11, D33): revenue before tax, the fee lines included; the SKU's cartons counted.
        Await.until("the order rolled up", ASYNC, () -> jdbc.queryForObject("SELECT count(*) FROM "
                + "reporting.processed_order_events WHERE order_id = ? AND event_type = 'PLACED'", Long.class,
                orderId) == 1);
        assertThat(jdbc.queryForObject("SELECT total FROM reporting.processed_order_events WHERE order_id = ? "
                + "AND event_type = 'PLACED'", BigDecimal.class, orderId)).isEqualByComparingTo("890000");
        assertThat(jdbc.queryForObject("SELECT SUM(cartons_sold) FROM reporting.product_sales_summary WHERE sku = ?",
                Long.class, sku)).isEqualTo(3);

        // The order waits for its transfer, so nothing was produced yet: another quote still carries a plate fee. Once
        // an order of the SKU is confirmed, a reorder has none (14 BR-17).
        assertThat(quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId, draft(sku, List.of())))
                .fees()).extracting(Quotes.FeeView::kind).containsExactly(FeeKind.PLATE_FEE);
        CustomerSkuRows.produced(jdbc, tx, customerId, sku);
        assertThat(quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId, draft(sku, List.of())))
                .fees()).as("produced before").isEmpty();
    }

    @Test
    @DisplayName("a product without a tax class: the quote is drafted, not sent (14 §4.1 step 3)")
    void noTaxClassNoSending() {
        String sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customerId, 10, null, true).sku();
        Quotes.QuoteView drafted = quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId,
                draft(sku, List.of())));
        assertThat(drafted.lines().getFirst().taxRate()).isNull();
        assertThat(drafted.proposed().tax()).isNull();
        assertThatThrownBy(() -> quotes.send(sales, drafted.id(), drafted.version()))
                .extracting(QuoteVatIntegrationTest::code).isEqualTo(ErrorCode.QUOTE_TAX_CLASS_MISSING);
    }
}
