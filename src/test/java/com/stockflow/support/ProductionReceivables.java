package com.stockflow.support;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.inventory.api.CountReceivingPackagesCommand;
import com.stockflow.inventory.api.CountReceivingPackagesCommand.PackageCount;
import com.stockflow.contracts.ReceiptStockReadyForPutaway;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.ReceiveStockCommand;
import com.stockflow.inventory.api.StockPackageView;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.Producer;
import com.stockflow.order.internal.domain.ProductionOrder;
import com.stockflow.order.internal.service.ProducerService;
import com.stockflow.order.internal.service.ProductionOrderService;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.order.internal.service.ShippingWarehouseService;
import com.stockflow.procurement.internal.service.GoodsReceipts;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Finished goods waiting to be received, as 4A-5 leaves them: a production order SENT to a producer for customer
 * SKUs of their own, on a confirmed contract, delivering to a warehouse the test names. The tests of putaway, cartons,
 * lots and stock lookups need a goods receipt to start from; since purchasing went (4A-8) there is no purchase order
 * to write into the tables, and a goods receipt is only ever against a production order, so they get one here - made
 * through the real services (a contract signed, a production order drawn up, approved, its print sample waived),
 * because the database refuses a receipt whose production order is not really SENT.
 *
 * <p>Needs the upload scanner and the customer e-mail off and a mocked mail sender - see
 * {@link ProductionReceiptTest}, which carries all of that.</p>
 *
 * <p>The orders of this class are numbered from a range of their own, apart from the other production test classes.</p>
 */
@Component
public class ProductionReceivables {

    private static final AtomicLong ORDER_NUMBERS = new AtomicLong(910_000);
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    /** One line of a production order: its SKU, the carton unit of its standard count, and the line to receive on. */
    public record Goods(String sku, UUID itemId, UUID cartonUnitId, int unitsPerCarton, int cartons,
                        UUID productionOrderLineId) {
        /** The line of a receipt that counts {@code n} new cartons of the standard count at {@code location}. */
        public GoodsReceipts.NewLine line(String location, int n) {
            return GoodsReceipts.NewLine.ofProductionOrderLine(productionOrderLineId, location, newCartons(n));
        }
    }

    /** A production order SENT, and the goods of its lines in the order they were asked for. */
    public record Receivable(UUID salesOrderId, UUID productionOrderId, String productionCode, List<Goods> goods) {
        public Goods first() {
            return goods.getFirst();
        }

        public String sku() {
            return first().sku();
        }

        public UUID itemId() {
            return first().itemId();
        }

        public UUID cartonUnitId() {
            return first().cartonUnitId();
        }

        public int unitsPerCarton() {
            return first().unitsPerCarton();
        }

        public UUID productionOrderLineId() {
            return first().productionOrderLineId();
        }

        public GoodsReceipts.NewLine line(String location, int n) {
            return first().line(location, n);
        }
    }

    private record Issued(String sku, UUID itemId, UUID cartonUnitId, int cartons, int unitsPerCarton) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EntityManager entityManager;
    private final FileStorage storage;
    private final ProductionOrderService productionOrders;
    private final ProducerService producers;
    private final SalesContracts contracts;
    private final OrderService orders;
    private final OrderRepository orderRepository;
    private final ShippingWarehouseService shippingWarehouses;
    private final GoodsReceipts receipts;
    private final InventoryService inventory;
    private final ApplicationEventPublisher events;

    private volatile Producer producer;

    @Autowired
    public ProductionReceivables(JdbcTemplate jdbc, TransactionTemplate tx, EntityManager entityManager,
                                 FileStorage storage, ProductionOrderService productionOrders,
                                 ProducerService producers, SalesContracts contracts, OrderService orders,
                                 OrderRepository orderRepository, ShippingWarehouseService shippingWarehouses,
                                 GoodsReceipts receipts, InventoryService inventory,
                                 ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.entityManager = entityManager;
        this.storage = storage;
        this.productionOrders = productionOrders;
        this.producers = producers;
        this.contracts = contracts;
        this.orders = orders;
        this.orderRepository = orderRepository;
        this.shippingWarehouses = shippingWarehouses;
        this.receipts = receipts;
        this.inventory = inventory;
        this.events = events;
    }

    /** {@code n} new cartons, their count left to the standard. */
    public static List<GoodsReceipts.NewPackage> newCartons(int n) {
        return IntStream.range(0, n).mapToObj(i -> new GoodsReceipts.NewPackage(null, 0)).toList();
    }

    /**
     * A production order of {@code cartons} cartons of {@code unitsPerCarton} of a SKU of its own, SENT and receivable
     * at {@code warehouseId}. The item has the logistics data a receipt line wants (BR-03).
     */
    public Receivable sent(UUID warehouseId, int cartons, int unitsPerCarton) {
        return sentLines(warehouseId, new int[] {cartons, unitsPerCarton});
    }

    /**
     * The same with a line for each {@code {cartons, unitsPerCarton}} pair, each of a SKU of its own: a production
     * order of several lines.
     */
    public Receivable sentLines(UUID warehouseId, int[]... lines) {
        return sentLinesOf(null, warehouseId, lines);
    }

    /**
     * A production order of one line of a SKU made from {@code product} (a customer's variant of it), for a test of
     * what a product's life cycle does with the production orders still open on it.
     */
    public Receivable sentFor(ReferenceRows.ProductRow product, UUID warehouseId, int cartons, int unitsPerCarton) {
        return sentLinesOf(product, warehouseId, new int[] {cartons, unitsPerCarton});
    }

    private Receivable sentLinesOf(ReferenceRows.ProductRow product, UUID warehouseId, int[]... lines) {
        try {
            return build(product, warehouseId, lines);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not set up a production order: " + e.getMessage(), e);
        }
    }

    /** A customer SKU as the design approval leaves it, with its logistics complete and a CARTON of {@code per}. */
    private Issued issue(UUID customerId, ReferenceRows.ProductRow given, int cartons, int per) throws Exception {
        ReferenceRows.ProductRow product = given != null ? given
                : tx.execute(s -> ReferenceRows.approvedProduct(entityManager));
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE product.products SET tax_class = 'STANDARD', kind = 'CUSTOMIZABLE' WHERE id = ?",
                product.productId()));
        ConfirmedDesigns.Design design = ConfirmedDesigns.confirmedFor(storage, jdbc, tx, entityManager, customerId,
                product.productId());
        String sku = tx.execute(s -> {
            UUID variant = Identifiers.newId();
            String code = product.sku() + "-R" + variant.toString().substring(24, 28).toUpperCase(Locale.ROOT);
            jdbc.update("""
                    INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature,
                        position, version, created_at, owner_customer_id, design_snapshot_id, source_variant_id)
                    VALUES (?, ?, ?, 'Printed cup', 'ACTIVE', FALSE, ?, 1, 0, NOW(), ?, ?, ?)""",
                    variant, product.productId(), code, "OWNED-SKU:" + code, customerId, design.snapshotId(),
                    product.variantId());
            return code;
        });
        UUID item = tx.execute(s -> jdbc.queryForObject("SELECT id FROM inventory.inventory_items WHERE sku = ?",
                UUID.class, sku));
        UUID carton = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    UPDATE inventory.inventory_items SET weight_kg = 0.02, length_cm = 9, width_cm = 9, height_cm = 12,
                           storage_class = 'NORMAL' WHERE id = ?""", item);
            jdbc.update("""
                    INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base,
                        weight_kg, length_cm, width_cm, height_cm, default_purchase, active, version, created_at,
                        default_shipping)
                    VALUES (?, ?, 'CARTON', ?, FALSE, 1.2, 60, 40, 35, FALSE, TRUE, 0, NOW(), TRUE)""",
                    carton, item, per);
        });
        return new Issued(sku, item, carton, cartons, per);
    }

    private Receivable build(ReferenceRows.ProductRow product, UUID warehouseId, int[][] specs) throws Exception {
        UUID customerId = customer();
        List<Issued> issued = new ArrayList<>();
        for (int[] spec : specs) {
            issued.add(issue(customerId, product, spec[0], spec[1]));
        }

        CurrentUser sales = sales();
        LocalDate today = LocalDate.now(VIETNAM);
        UUID orderId = tx.execute(s -> {
            List<OrderLine> lines = issued.stream().map(i -> Order.line(Identifiers.newId(), new Sku(i.sku()),
                    i.cartons() * i.unitsPerCarton(), Money.vnd(2_000), null)
                    .withCartons(i.cartonUnitId(), i.cartons(), i.unitsPerCarton()).withTax(BigDecimal.TEN)).toList();
            Order order = Order.draft(OrderNumber.of(today, ORDER_NUMBERS.incrementAndGet()), customerId,
                    Identifiers.newId(), lines, Instant.now());
            order.applyTerms(PaymentTerm.PREPAID, null, null, null);
            return orderRepository.save(order).id().value();
        });
        List<SalesContracts.BatchLine> batchLines = issued.stream().map(i -> new SalesContracts.BatchLine(
                jdbc.queryForObject("SELECT id FROM ordering.order_line WHERE order_id = ? AND sku = ?", UUID.class,
                        orderId, i.sku()), i.cartons())).toList();
        SalesContracts.ContractView drafted = as(sales, () -> contracts.create(sales, orderId,
                new SalesContracts.Draft(null, List.of(new SalesContracts.Batch(today.plusDays(21),
                        today.plusDays(23), batchLines)),
                        List.of(new SalesContracts.Installment("Before delivery", "BEFORE_DELIVERY", null,
                                new BigDecimal("100"), null, null)))));
        SalesContracts.ContractView sent = as(sales, () -> contracts.send(sales, orderId, drafted.version()));
        byte[] scan = TestPdfs.blank();
        as(sales, () -> contracts.sign(sales, orderId, sent.version(), IncomingFiles.sent(List.of(
                new FileUpload("signed.pdf", "application/pdf", scan.length, new ByteArrayInputStream(scan)))),
                today));
        if (orders.findById(orderId).orElseThrow().status() != OrderStatus.CONFIRMED) {
            throw new IllegalStateException("The fixture's contract did not confirm its order");
        }
        shippingWarehouses.choose(orderId, warehouseId, sales.userId());

        UUID coordinator = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID manager = tx.execute(s -> ReferenceRows.user(entityManager));
        ProductionOrder draft = productionOrders.drawUp(orderId, 1, producer().id(), coordinator);
        ProductionOrder waived = productionOrders.edit(draft.id(), draft.version(), new ProductionOrder.Draft(
                draft.producerId(), draft.needByDate(), draft.unitPrice(), draft.note(), false,
                "The customer approved the same print on paper", null));
        ProductionOrder pending = productionOrders.submit(waived.id(), waived.version(), coordinator);
        ProductionOrder approved = productionOrders.approve(pending.id(), pending.version(), manager);
        List<Goods> goods = issued.stream().map(i -> new Goods(i.sku(), i.itemId(), i.cartonUnitId(),
                i.unitsPerCarton(), i.cartons(), approved.lines().stream().filter(l -> l.sku().equals(i.sku()))
                .findFirst().orElseThrow().id())).toList();
        return new Receivable(orderId, approved.id(), approved.code(), goods);
    }

    /** A DRAFT goods receipt against the production order, as a clerk of the warehouse starts one. */
    public GoodsReceipts.ReceiptView draftReceipt(Receivable receivable, UUID clerk) {
        return receipts.createForProductionOrder(new GoodsReceipts.CreateForProductionOrder(
                receivable.productionOrderId(), "DN-" + receivable.productionCode(), null), clerk);
    }

    /** A DRAFT receipt with {@code cartons} cartons of the standard count counted at {@code location}. */
    public GoodsReceipts.ReceiptView counted(Receivable receivable, String location, int cartons, UUID clerk) {
        GoodsReceipts.ReceiptView draft = draftReceipt(receivable, clerk);
        return receipts.replaceLines(draft.id(), List.of(receivable.line(location, cartons)));
    }

    /** The same, confirmed: the {@code ReceiptStockReadyForPutaway} event is on its way. */
    public GoodsReceipts.ReceiptView received(Receivable receivable, String location, int cartons, UUID clerk) {
        return receipts.confirm(counted(receivable, location, cartons, clerk).id(), clerk);
    }

    /**
     * A DRAFT receipt with a line of {@code cartons} cartons' worth of units and no carton counted yet - what a clerk
     * has when the goods are on the dock and nobody has labelled them. Written as rows because the receipt service
     * only takes a line that lists its cartons; the database checks it as it does any other (the production order's
     * own line, whole cartons, never more than ordered), at commit.
     */
    public UUID uncountedLine(Receivable receivable, GoodsReceipts.ReceiptView draft, String location, int cartons) {
        return uncountedLine(receivable, receivable.first(), draft, location, cartons);
    }

    /** The same for another line of the production order than the first. */
    public UUID uncountedLine(Receivable receivable, Goods goods, GoodsReceipts.ReceiptView draft, String location,
                              int cartons) {
        UUID line = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO procurement.goods_receipt_lines (id, receipt_id, production_order_line_id,
                                                             inventory_item_id, location_id, received_qty, lot_number,
                                                             uom_conversion_id)
                SELECT ?, ?, ?, ?, l.id, ?, ?, ? FROM warehouse.storage_location l WHERE l.location_code = ?""",
                line, draft.id(), goods.productionOrderLineId(), goods.itemId(), cartons * goods.unitsPerCarton(),
                receivable.productionCode(), goods.cartonUnitId(), location));
        return line;
    }

    /** Goods counted in at a receiving area under a DRAFT receipt: the receipt, its line, its labelled cartons. */
    public record Dock(UUID receiptId, String receiptNumber, UUID warehouseId, UUID lineId, String sku, String location,
                       int quantity, List<String> lpns) {
    }

    /**
     * Goods on the dock as a count of {@code cartons} cartons leaves them: every carton labelled, everything received
     * into INBOUND stock at {@code location}, and the putaway tasks opened for it as a confirmation opens them. The
     * receipt service itself only takes a line that lists its cartons and the database checks it, at commit, as it
     * does any other, so this writes the line as rows and goes through {@code countReceivingPackages} and
     * {@code receive}, with the event as {@code confirm} publishes it; the receipt stays a draft. Cartons only, each
     * of the packing unit's count: since team plan 4C-2 nothing else is stock, so there is no short carton and no loose
     * remainder to put on the dock.
     */
    public Dock onTheDock(Receivable receivable, String location, int cartons, UUID clerk) {
        GoodsReceipts.ReceiptView draft = draftReceipt(receivable, clerk);
        UUID line = uncountedLine(receivable, draft, location, cartons);
        List<String> lpns = countCartons(receivable.first(), line, location, cartons, clerk).stream()
                .map(StockPackageView::lpn).sorted().toList();
        int quantity = cartons * receivable.unitsPerCarton();
        inventory.receive(new ReceiveStockCommand(line, new Sku(receivable.sku()), receivable.productionCode(),
                location, quantity, clerk, Instant.now()));
        tx.executeWithoutResult(s -> events.publishEvent(new ReceiptStockReadyForPutaway(draft.id(), draft.number(),
                draft.warehouseId(), List.of(new ReceiptStockReadyForPutaway.Line(line, receivable.sku(), null,
                        location, quantity)))));
        return new Dock(draft.id(), draft.number(), draft.warehouseId(), line, receivable.sku(), location, quantity,
                lpns);
    }

    /** Labels {@code cartons} new cartons on the line, as the clerk counted them. */
    public List<StockPackageView> countCartons(Goods goods, UUID line, String location, int cartons, UUID clerk) {
        return inventory.countReceivingPackages(new CountReceivingPackagesCommand(line, new Sku(goods.sku()),
                goods.cartonUnitId(), location, IntStream.range(0, cartons).mapToObj(i -> PackageCount.fresh()).toList(),
                clerk));
    }

    private UUID customer() {
        UUID customerId = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO customer.customer (id, full_name, email, tax_code, status, version, created_at)
                    VALUES (?, 'Receiving Test Co', ?, ?, 'ACTIVE', 0, NOW())""", customerId,
                    "receiving-" + customerId + "@example.com", "03" + (10_000_000 + ThreadLocalRandom.current()
                            .nextInt(89_999_999)));
            jdbc.update("""
                    INSERT INTO customer.customer_legal_profile (id, customer_id, registered_address,
                        representative_name, representative_title, version, created_at)
                    VALUES (?, ?, '12 Nguyen Hue, TP HCM', 'Le Van D', 'Director', 0, NOW())""",
                    Identifiers.newId(), customerId);
        });
        return customerId;
    }

    private CurrentUser sales() {
        UUID salesUser = tx.execute(s -> ReferenceRows.user(entityManager));
        return new CurrentUser(salesUser, "sales-" + salesUser, Set.of(Role.SALES_STAFF), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
    }

    private Producer producer() {
        Producer p = producer;
        if (p == null) {
            synchronized (this) {
                if (producer == null) {
                    producer = producers.register("PR-" + UUID.randomUUID().toString().substring(0, 8)
                            .toUpperCase(Locale.ROOT), new Producer.Details("Receiving test workshop", null, null,
                            null, Producer.Channel.PDF, 14, null));
                }
                p = producer;
            }
        }
        return p;
    }

    private static <T> T as(CurrentUser user, Supplier<T> call) {
        return WithCurrentUser.call(user, DataScope.ALL, call);
    }
}
