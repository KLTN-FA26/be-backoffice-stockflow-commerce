package com.stockflow.support;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.service.OrderReleases;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.order.internal.service.ShippingWarehouseService;
import com.stockflow.payment.internal.domain.PaymentReceipt;
import com.stockflow.payment.internal.service.PaymentReceiptService;
import jakarta.persistence.EntityManager;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * A confirmed order the way production confirms one since 4B-5/4B-6 (kltn-docs 14 §4.3 steps 11-16, 15 §4.1-§4.4,
 * BR-07): a DRAFT order of cartons - what converting an accepted quote leaves - its contract drawn up, sent and signed
 * through {@link SalesContracts}, and the installment on signing paid by a receipt the accountant records through
 * {@link PaymentReceiptService}. What a test about something downstream (holding, releasing, picking, packing,
 * shipping, returns, reports) needs, without the money ledger, the checkout's payment terms or a {@code PaymentCaptured}
 * nobody publishes any more (4B-10).
 *
 * <p>Nothing here writes a status. The order is CONFIRMED by its own listener once the receipt commits (an
 * {@code @ApplicationModuleListener}, so it is awaited, never slept for); its shipping warehouse is chosen through
 * {@link ShippingWarehouseService}, which holds whole cartons there (4B-7); it is released through
 * {@link OrderReleases}. The only rows written by SQL are what another module owns and a test has no business creating
 * through it: the customer's legal profile a contract names (18 BR-05), the users, and a carton unit of the SKU.</p>
 *
 * <p>The DRAFT order is built through the domain rather than through {@code Quotes.convert}, which is
 * {@code QuoteIntegrationTest}'s subject. What a converted quote leaves still holds here, in the database: every line
 * is cartons of a SKU the customer owns ({@code tg_order_line_carton_unit}, 14 BR-04, BR-08) - issue one with
 * {@link CustomerSkuRows} - and {@code ck_order_line_cartons} wants more than one piece to a carton. {@link Line#units}
 * orders {@code n >= 2} pieces as one carton of {@code n}, under a unit {@code PK<n>} of the SKU created on first use,
 * so a test keeps counting the pieces it counted before.</p>
 *
 * <p>Built from the test's {@link ApplicationContext} ({@code @Autowired ApplicationContext context}), so a test class
 * needs no field per collaborator. A test using it must run with {@code stockflow.upload-inspection.enabled=false}:
 * signing stores the scan, and no virus scanner runs in tests.</p>
 */
public final class ConfirmedOrders {

    public static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Duration ASYNC = Duration.ofSeconds(20);
    /** Order numbers of this fixture: {@code SO-<day>-8xxxxxx}, clear of checkout's sequence and the other fixtures'. */
    private static final AtomicLong SEQUENCE = new AtomicLong(8_000_000L
            + ThreadLocalRandom.current().nextLong(900_000L));

    /**
     * A line of the order.
     *
     * @param sku            a SKU of the order's customer (14 BR-04)
     * @param cartons        cartons ordered
     * @param unitsPerCarton pieces in one carton, more than one ({@code ck_order_line_cartons})
     * @param unitPrice      VND for one piece
     * @param taxRate        VAT percent of the line; null for none, as an order placed before 4B-3
     * @param unit           the carton unit's name, created for the SKU when it has none of that name
     */
    public record Line(String sku, int cartons, int unitsPerCarton, BigDecimal unitPrice, BigDecimal taxRate,
                       String unit) {

        /** {@code units} pieces, at least two, as one carton of them. */
        public static Line units(String sku, int units, long unitPrice) {
            if (units < 2) {
                throw new IllegalArgumentException("A carton holds more than one piece (ck_order_line_cartons)");
            }
            return new Line(sku, 1, units, BigDecimal.valueOf(unitPrice), null, "PK" + units);
        }

        public static Line units(Sku sku, int units, Money unitPrice) {
            return units(sku.code(), units, unitPrice.amount().longValueExact());
        }

        /** {@code cartons} cartons of the SKU's carton unit {@code unitsPerCarton} pieces each. */
        public static Line cartons(String sku, int cartons, int unitsPerCarton, long unitPrice) {
            return new Line(sku, cartons, unitsPerCarton, BigDecimal.valueOf(unitPrice), null, "PK" + unitsPerCarton);
        }

        /** A customer's own SKU in its carton unit (as {@link CustomerSkuRows} issues it). */
        public static Line cartons(CustomerSkuRows.Issued sku, int cartons, long unitPrice) {
            return new Line(sku.sku(), cartons, sku.unitsPerCarton(), BigDecimal.valueOf(unitPrice), null, null);
        }

        public Line withTax(BigDecimal rate) {
            return new Line(sku, cartons, unitsPerCarton, unitPrice, rate, unit);
        }

        public int units() {
            return cartons * unitsPerCarton;
        }
    }

    /**
     * A signed contract's order, PENDING_PAYMENT until its installment on signing is paid.
     *
     * @param installmentIds the order's installments in the plan's order
     */
    public record Signed(UUID orderId, String orderNumber, UUID customerId, String contractNumber,
                         List<UUID> installmentIds, BigDecimal total) {

        public UUID seq(int seq) {
            return installmentIds.get(seq - 1);
        }
    }

    /** A CONFIRMED order and the receipt that confirmed it. */
    public record Confirmed(UUID orderId, String orderNumber, UUID customerId, String contractNumber,
                            List<UUID> installmentIds, UUID receiptId, BigDecimal total) {
    }

    private final ApplicationContext context;
    private CurrentUser sales;
    private UUID accountant;

    public ConfirmedOrders(ApplicationContext context) {
        this.context = context;
    }

    private <T> T bean(Class<T> type) {
        return context.getBean(type);
    }

    private JdbcTemplate jdbc() {
        return bean(JdbcTemplate.class);
    }

    private TransactionTemplate tx() {
        return bean(TransactionTemplate.class);
    }

    /** The Sales user who draws up and signs; created once per fixture. */
    public CurrentUser sales() {
        if (sales == null) {
            UUID id = tx().execute(s -> ReferenceRows.user(bean(EntityManager.class)));
            sales = new CurrentUser(id, "sales-" + id, Set.of(Role.SALES_STAFF), Set.of(), DataScope.ALL, Set.of(),
                    Set.of());
        }
        return sales;
    }

    /** The accountant who records the receipts; created once per fixture. */
    public UUID accountant() {
        if (accountant == null) {
            accountant = tx().execute(s -> ReferenceRows.user(bean(EntityManager.class)));
        }
        return accountant;
    }

    private <T> T asSales(Supplier<T> call) {
        return WithCurrentUser.call(sales(), DataScope.ALL, call);
    }

    private static LocalDate today() {
        return LocalDate.now(VIETNAM);
    }

    // ---- the steps ----------------------------------------------------------------------------------------------------

    /**
     * The customer's registered address and legal representative, which a contract names (18 BR-05) - written once,
     * kept if the customer has them already.
     */
    public void legalProfile(UUID customerId) {
        tx().executeWithoutResult(s -> jdbc().update("""
                INSERT INTO customer.customer_legal_profile (id, customer_id, registered_address, representative_name,
                    representative_title, version, created_at)
                VALUES (?, ?, '1 Le Loi, TP HCM', 'Tran Van A', 'Director', 0, NOW())
                ON CONFLICT (customer_id) DO NOTHING""", Identifiers.newId(), customerId));
    }

    /** The SKU's carton unit of {@code line}: its own unit for a customer's SKU, else {@code PK<n>}, made on first use. */
    private UUID cartonUnit(Line line) {
        JdbcTemplate jdbc = jdbc();
        if (line.unit() == null) {
            return jdbc.queryForObject("""
                    SELECT u.id FROM inventory.uom_conversions u
                      JOIN inventory.inventory_items i ON i.id = u.inventory_item_id
                     WHERE i.sku = ? AND u.factor = ? AND NOT u.is_base AND u.active
                     ORDER BY u.default_shipping DESC, u.created_at LIMIT 1""", UUID.class, line.sku(),
                    line.unitsPerCarton());
        }
        return tx().execute(s -> {
            UUID item = jdbc.queryForObject("SELECT id FROM inventory.inventory_items WHERE sku = ?", UUID.class,
                    line.sku());
            jdbc.update("""
                    INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base,
                        default_purchase, active, version, created_at, default_shipping)
                    VALUES (?, ?, ?, ?, FALSE, FALSE, TRUE, 0, NOW(), FALSE)
                    ON CONFLICT (inventory_item_id, unit_of_measure) DO NOTHING""", Identifiers.newId(), item,
                    line.unit(), line.unitsPerCarton());
            return jdbc.queryForObject("""
                    SELECT id FROM inventory.uom_conversions WHERE inventory_item_id = ? AND unit_of_measure = ?
                       AND factor = ?""", UUID.class, item, line.unit(), line.unitsPerCarton());
        });
    }

    /** A DRAFT order of {@code lines} for the customer - what converting an accepted quote leaves. */
    public UUID draft(UUID customerId, List<Line> lines) {
        List<UUID> units = lines.stream().map(this::cartonUnit).toList();
        return tx().execute(s -> {
            List<OrderLine> orderLines = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                Line line = lines.get(i);
                OrderLine orderLine = Order.line(Identifiers.newId(), new Sku(line.sku()), line.units(),
                        Money.vnd(line.unitPrice().longValueExact()), null)
                        .withCartons(units.get(i), line.cartons(), line.unitsPerCarton());
                orderLines.add(line.taxRate() == null ? orderLine : orderLine.withTax(line.taxRate()));
            }
            Order order = Order.draft(OrderNumber.of(today(), SEQUENCE.incrementAndGet()), customerId,
                    Identifiers.newId(), orderLines, Instant.now());
            return bean(OrderRepository.class).save(order).id().value();
        });
    }

    /** Every installment on signing: the whole total paid when the contract is signed (15 BR-07). */
    public static List<SalesContracts.Installment> paidOnSigning() {
        return List.of(new SalesContracts.Installment("On signing", "ON_SIGNING", null, new BigDecimal("100"), 0,
                null));
    }

    /**
     * The order's contract - every carton in one batch three weeks out, {@code plan} - drawn up, sent and signed today
     * with a real one-page PDF scan. The order is PENDING_PAYMENT while an installment on signing is unpaid.
     */
    public Signed sign(UUID orderId, List<SalesContracts.Installment> plan) {
        legalProfile(customerOf(orderId));
        SalesContracts contracts = bean(SalesContracts.class);
        List<SalesContracts.BatchLine> batch = jdbc().queryForList("""
                        SELECT id, cartons FROM ordering.order_line WHERE order_id = ? ORDER BY id""", orderId).stream()
                .map(row -> new SalesContracts.BatchLine((UUID) row.get("id"), ((Number) row.get("cartons")).intValue()))
                .toList();
        LocalDate today = today();
        SalesContracts.ContractView drafted = asSales(() -> contracts.create(sales(), orderId, new SalesContracts.Draft(
                null, List.of(new SalesContracts.Batch(today.plusDays(21), today.plusDays(23), batch)), plan)));
        SalesContracts.ContractView sent = asSales(() -> contracts.send(sales(), orderId, drafted.version()));
        byte[] scan = TestPdfs.blank();
        SalesContracts.ContractView signed = asSales(() -> contracts.sign(sales(), orderId, sent.version(),
                IncomingFiles.sent(List.of(new FileUpload("signed.pdf", "application/pdf", scan.length,
                        new ByteArrayInputStream(scan)))), today));
        List<UUID> installments = jdbc().queryForList(
                "SELECT id FROM payment.installment WHERE order_id = ? ORDER BY seq", UUID.class, orderId);
        OrderSummary order = bean(OrderService.class).findById(orderId).orElseThrow();
        return new Signed(orderId, order.orderNumber(), order.customerId(), signed.contractNumber(), installments,
                order.total().amount());
    }

    /** A new order of {@code lines} whose contract pays everything on signing, signed and not paid yet. */
    public Signed signed(UUID customerId, Line... lines) {
        return sign(draft(customerId, Arrays.asList(lines)), paidOnSigning());
    }

    /** A new order of {@code lines} with {@code plan}, signed. */
    public Signed signed(UUID customerId, List<SalesContracts.Installment> plan, Line... lines) {
        return sign(draft(customerId, Arrays.asList(lines)), plan);
    }

    /**
     * A receipt paying {@code amount} of {@code installmentId}, recorded by the accountant today under a statement
     * reference nobody else used (15 BR-03). Returns the receipt.
     */
    public PaymentReceipt pay(UUID customerId, UUID installmentId, BigDecimal amount) {
        String reference = "FT" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        return bean(PaymentReceiptService.class).record(new PaymentReceiptService.RecordReceiptCommand(customerId,
                reference, today(), null, List.of(new PaymentReceipt.Payment(installmentId, amount)), accountant()))
                .receipt();
    }

    /**
     * Every installment on signing of {@code signed} paid in full by one receipt each, and the order awaited
     * CONFIRMED (15 BR-07).
     */
    public Confirmed pay(Signed signed) {
        List<UUID> onSigning = jdbc().queryForList("""
                SELECT id FROM payment.installment WHERE order_id = ? AND milestone = 'ON_SIGNING' ORDER BY seq""",
                UUID.class, signed.orderId());
        UUID receipt = null;
        for (UUID installment : onSigning) {
            BigDecimal amount = jdbc().queryForObject("SELECT amount FROM payment.installment WHERE id = ?",
                    BigDecimal.class, installment);
            receipt = pay(signed.customerId(), installment, amount).id();
        }
        awaitStatus(signed.orderId(), OrderStatus.CONFIRMED);
        return new Confirmed(signed.orderId(), signed.orderNumber(), signed.customerId(), signed.contractNumber(),
                signed.installmentIds(), receipt, signed.total());
    }

    /** A new order of {@code lines}, signed and its installment on signing paid: CONFIRMED. */
    public Confirmed confirmed(UUID customerId, Line... lines) {
        return pay(signed(customerId, lines));
    }

    /**
     * Sales chooses {@code warehouseId} as the order's shipping warehouse (17 §4.2 steps 2-3): it holds whole cartons
     * there, as many as are free.
     */
    public ShippingWarehouseService.Coverage ship(UUID orderId, UUID warehouseId) {
        return asSales(() -> bean(ShippingWarehouseService.class).choose(orderId, warehouseId, sales().userId()));
    }

    /**
     * A new CONFIRMED order of {@code lines} shipping from {@code warehouseId}, every carton held there, released there
     * by {@code releasedBy}: READY_TO_FULFILL. The stock must be there first.
     */
    public OrderSummary released(UUID customerId, UUID warehouseId, UUID releasedBy, Line... lines) {
        return release(confirmed(customerId, lines).orderId(), warehouseId, releasedBy);
    }

    /** {@code orderId}, CONFIRMED, shipped from {@code warehouseId} and released there. */
    public OrderSummary release(UUID orderId, UUID warehouseId, UUID releasedBy) {
        ship(orderId, warehouseId);
        return asSales(() -> bean(OrderReleases.class).release(orderId, warehouseId, releasedBy));
    }

    public OrderStatus status(UUID orderId) {
        return bean(OrderService.class).findById(orderId).orElseThrow().status();
    }

    public void awaitStatus(UUID orderId, OrderStatus wanted) {
        Await.until("order " + orderId + " " + wanted, ASYNC, () -> status(orderId) == wanted);
    }

    private UUID customerOf(UUID orderId) {
        return jdbc().queryForObject("SELECT customer_id FROM ordering.customer_order WHERE id = ?", UUID.class,
                orderId);
    }
}
