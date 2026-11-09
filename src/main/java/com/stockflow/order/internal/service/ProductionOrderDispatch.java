package com.stockflow.order.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.AssignedWarehouses;
import com.stockflow.common.security.SignedLinks;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.common.storage.Checksums;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.PreparedFiles;
import com.stockflow.contracts.ProductionOrderSampleApproved;
import com.stockflow.contracts.ProductionOrderSent;
import com.stockflow.contracts.ProductionOrderStopped;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.design.api.DesignArtifactRole;
import com.stockflow.design.api.DesignService;
import com.stockflow.design.api.FulfillmentArtifact;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.inventory.api.PackagingRequest;
import com.stockflow.inventory.api.PackagingSpec;
import com.stockflow.inventory.api.PackagingSpecResolver;
import com.stockflow.notification.api.DeliveryAttemptSummary;
import com.stockflow.notification.api.NotificationService;
import com.stockflow.notification.api.ProductionOrderMail;
import com.stockflow.order.internal.domain.DeliveryBatch;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.Producer;
import com.stockflow.order.internal.domain.ProducerRepository;
import com.stockflow.order.internal.domain.ProductionOrder;
import com.stockflow.order.internal.domain.ProductionOrderRepository;
import com.stockflow.order.internal.domain.ProductionOrderSlip;
import com.stockflow.order.internal.domain.ProductionOrderSlipRepository;
import com.stockflow.order.internal.domain.ProductionOrderStatus;
import com.stockflow.order.internal.domain.SalesContract;
import com.stockflow.order.internal.domain.SalesContractRepository;
import com.stockflow.product.api.CustomerSku;
import com.stockflow.product.api.CustomerSkus;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Sending a production order to its producer (4A-3, kltn-docs 17 §4.3 steps 7, 10, 12-13, BR-23; 00 §6).
 *
 * <ul>
 *   <li><b>Approval sends it.</b> Before the approval's transaction, the slip is drawn from what was read and kept as a
 *       {@link FileCategory#DOCUMENT} ({@link #prepareSlip}) - no lock is held while a PDF is drawn and stored; in the
 *       transaction, once the production order is SENT, the slip is recorded and {@link ProductionOrderSent} published
 *       ({@link #sent}). The approval re-checks the version it read, so the slip is of the production order as
 *       approved; when the transaction fails, the stored file goes with it.</li>
 *   <li><b>The producer is told</b> by notification, through the producer's channel: e-mailed with links to the slip
 *       and the print files, or nothing for a producer the coordinator hands the slip. What the message says is read
 *       when it is written ({@link #mail}, behind {@code notification.api.ProductionOrderDirectory}).</li>
 *   <li><b>"Sample approved, print the batch"</b> ({@link #sampleApproved}, called by 4A-4) and <b>"stop"</b>
 *       ({@link #stopped}: a sent production order cancelled or closed) go the same way.</li>
 *   <li><b>The links</b> work for {@code stockflow.order.production-order.file-links-valid-for} and are checked again on
 *       every use ({@link #openSlip}, {@link #openPrintFile}): the signature, the production order still open, the
 *       file still hashing to what was approved. Only then is a presigned URL of minutes handed out - never logged.</li>
 * </ul>
 */
@Service
@Transactional
public class ProductionOrderDispatch {

    private static final Logger log = LoggerFactory.getLogger(ProductionOrderDispatch.class);
    private static final String FILES = "/api/v1/production-order-files/";

    /** The slip as stored before the approval commits: adopted by it, or deleted when it does not. */
    public static final class PreparedSlip implements AutoCloseable {
        private final PreparedFiles files;
        private final int totalCartons;
        private final BigDecimal volumeM3;

        private PreparedSlip(PreparedFiles files, int totalCartons, BigDecimal volumeM3) {
            this.files = files;
            this.totalCartons = totalCartons;
            this.volumeM3 = volumeM3;
        }

        /** Where the slip is stored; kept by the approval, deleted by {@link #close} when it was not. */
        public String storageKey() {
            return files.single().file().key();
        }

        @Override
        public void close() {
            files.close();
        }
    }

    /** What a production order's dispatch looks like: its producer's channel, its slip and every message attempted. */
    public record DispatchView(String channel, ProductionOrderSlip slip, PageResponse<DeliveryAttemptSummary> messages) {
    }

    private final ProductionOrderRepository productionOrders;
    private final ProductionOrderSlipRepository slips;
    private final ProducerRepository producers;
    private final OrderRepository orders;
    private final SalesContractRepository contracts;
    private final CustomerService customers;
    private final IdentityService identity;
    private final WarehouseService warehouses;
    private final CustomerSkus customerSkus;
    private final PackagingSpecResolver packaging;
    private final DesignService designs;
    private final NotificationService notifications;
    private final ProductionOrderSlipDocument document;
    private final FileTransfers files;
    private final FileStorage storage;
    private final SignedLinks links;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final Duration linksValidFor;
    private final TransactionTemplate readOnly;

    ProductionOrderDispatch(ProductionOrderRepository productionOrders, ProductionOrderSlipRepository slips,
                            ProducerRepository producers, OrderRepository orders, SalesContractRepository contracts,
                            CustomerService customers, IdentityService identity, WarehouseService warehouses,
                            CustomerSkus customerSkus, PackagingSpecResolver packaging, DesignService designs,
                            NotificationService notifications, ProductionOrderSlipDocument document,
                            FileTransfers files, FileStorage storage, SignedLinks links,
                            ApplicationEventPublisher events, Clock clock, PlatformTransactionManager transactions,
                            @Value("${stockflow.order.production-order.file-links-valid-for:P30D}")
                            Duration linksValidFor) {
        this.productionOrders = productionOrders;
        this.slips = slips;
        this.producers = producers;
        this.orders = orders;
        this.contracts = contracts;
        this.customers = customers;
        this.identity = identity;
        this.warehouses = warehouses;
        this.customerSkus = customerSkus;
        this.packaging = packaging;
        this.designs = designs;
        this.notifications = notifications;
        this.document = document;
        this.files = files;
        this.storage = storage;
        this.links = links;
        this.events = events;
        this.clock = clock;
        if (linksValidFor == null || linksValidFor.isNegative() || linksValidFor.isZero()) {
            throw new IllegalStateException("stockflow.order.production-order.file-links-valid-for must be positive");
        }
        this.linksValidFor = linksValidFor;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
    }

    /** The route serving a production order's slip by a signed link. */
    public static String slipPath(UUID productionOrderId) {
        return FILES + productionOrderId + "/slip";
    }

    /** The route serving the print file of one of a production order's designs by a signed link. */
    public static String printFilePath(UUID productionOrderId, UUID designSnapshotId) {
        return FILES + productionOrderId + "/print-files/" + designSnapshotId;
    }

    // ---- sending ----------------------------------------------------------------------------------------------------

    /**
     * Draws the slip of {@code production} as {@code approver} approves it at {@code at}, and stores it. Not
     * transactional: called before the approval's transaction, with no lock held.
     *
     * @return the stored slip; close it when the approval is done - it deletes the file unless the approval adopted it
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public PreparedSlip prepareSlip(ProductionOrder production, UUID approver, Instant at) {
        // Read in one short read-only transaction - the aggregates are mapped inside it - then drawn and stored outside.
        Slip facts = readOnly.execute(status -> slipFacts(production, approver, at));
        byte[] pdf = document.render(facts.facts());
        PreparedFiles stored = files.prepare(FileCategory.DOCUMENT, new FileUpload(
                ProductionOrderSlip.fileName(production.code()), "application/pdf", pdf.length,
                new ByteArrayInputStream(pdf)));
        return new PreparedSlip(stored, production.lines().stream().mapToInt(ProductionOrder.Line::cartons).sum(),
                facts.volumeM3());
    }

    /** What the slip says, and the volume the warehouse is told of. */
    private record Slip(ProductionOrderSlipDocument.Facts facts, BigDecimal volumeM3) {
    }

    private Slip slipFacts(ProductionOrder production, UUID approver, Instant at) {
        Map<String, PackagingSpec.CartonSpec> sizes = cartonSizes(production);
        Producer producer = producers.findById(production.producerId()).orElseThrow(() -> new BusinessException(
                ErrorCode.PRODUCER_NOT_FOUND, "No producer " + production.producerId()));
        Optional<WarehouseView> warehouse = warehouses.findWarehouse(production.receivingWarehouseId());
        String orderNumber = orders.findById(new OrderId(production.salesOrderId()))
                .map(order -> order.orderNumber().toString()).orElse("-");
        DeliveryBatch batch = contracts.findLiveByOrderId(production.salesOrderId()).map(SalesContract::schedule)
                .flatMap(schedule -> schedule.stream().filter(b -> b.id().equals(production.deliveryBatchId()))
                        .findFirst())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCTION_ORDER_NOT_ALLOWED,
                        "Production order %s: its delivery batch is gone".formatted(production.code())));
        List<ProductionOrderSlipDocument.SlipLine> lines = new ArrayList<>();
        Map<String, String> names = names(production);
        for (ProductionOrder.Line line : production.lines()) {
            lines.add(new ProductionOrderSlipDocument.SlipLine(line.sku(), names.get(line.sku()), line.cartons(),
                    line.unitsPerCarton(), line.totalUnits(), size(sizes.get(line.sku())), line.packingRequirements(),
                    line.designSnapshotId(), line.printFileChecksum()));
        }
        return new Slip(new ProductionOrderSlipDocument.Facts(production.code(), at, producer.code(),
                producer.name(), contact(producer), orderNumber,
                customers.findById(production.customerId()).map(CustomerSummary::fullName).orElse(null), batch.seq(),
                batch.windowStart(), batch.windowEnd(),
                warehouse.map(WarehouseView::name).orElse(production.receivingWarehouseId().toString()),
                warehouse.map(WarehouseView::address).orElse(null), production.needByDate(),
                production.sampleApprovalRequired(), production.unitPrice(), production.currency(), production.note(),
                person(production.drawnUpBy()), person(approver), lines), volume(production, sizes));
    }

    /**
     * In the approval's transaction, once {@code production} is SENT: keeps its slip and publishes
     * {@link ProductionOrderSent}, so its producer and the receiving warehouse's managers are told after commit.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void sent(ProductionOrder production, PreparedSlip slip) {
        if (production.status() != ProductionOrderStatus.SENT || production.sentAt() == null) {
            throw new IllegalStateException("Production order " + production.code() + " is not sent");
        }
        PreparedFiles.PreparedFile file = slip.files.single();
        slips.record(new ProductionOrderSlip(production.id(), file.file().key(), file.sha256(),
                file.file().sizeBytes(), production.sentAt()));
        slip.files.adopt();
        events.publishEvent(new ProductionOrderSent(Identifiers.newId(), production.id(), production.code(),
                production.receivingWarehouseId(), production.needByDate(), slip.totalCartons, slip.volumeM3,
                production.sentAt()));
    }

    /**
     * Tells the producer of {@code production} that the customer approved its print sample, so mass printing starts
     * (17 §4.3 steps 9-10, B31), in the caller's transaction - the one moving it to IN_PRODUCTION (4A-4).
     *
     * @throws BusinessException {@code PRODUCTION_ORDER_STATUS_INVALID} unless it is IN_PRODUCTION with a sample
     *         required
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void sampleApproved(ProductionOrder production, Instant at) {
        if (production.status() != ProductionOrderStatus.IN_PRODUCTION || !production.sampleApprovalRequired()) {
            throw new BusinessException(ErrorCode.PRODUCTION_ORDER_STATUS_INVALID, ("Production order %s is %s: its "
                    + "producer is told of an approved sample when it goes into production after one")
                    .formatted(production.code(), production.status()));
        }
        events.publishEvent(new ProductionOrderSampleApproved(Identifiers.newId(), production.id(), production.code(),
                at));
    }

    /**
     * Tells the producer to stop, in the caller's transaction, when {@code production} was sent and has just been
     * cancelled or closed (17 §4.3 steps 12-13); a production order never sent has no producer to tell.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void stopped(ProductionOrder production) {
        if (production.sentAt() == null) {
            return;
        }
        Instant at = switch (production.status()) {
            case CANCELLED -> production.cancelledAt();
            case CLOSED -> production.closedAt();
            default -> throw new IllegalStateException("Production order " + production.code() + " is "
                    + production.status() + ", not stopped");
        };
        events.publishEvent(new ProductionOrderStopped(Identifiers.newId(), production.id(), production.code(),
                production.status().name(), at));
    }

    // ---- reading ----------------------------------------------------------------------------------------------------

    /** A link of minutes to the slip a production order was sent with, for staff who may see it. */
    @Transactional(readOnly = true)
    public DownloadLink slipLink(UUID id) {
        ProductionOrder production = visible(id);
        return intactSlipLink(production);
    }

    /** The producer's channel, the slip and the messages to the producer, newest first. */
    @Transactional(readOnly = true)
    public DispatchView dispatch(UUID id, int page, int size) {
        ProductionOrder production = visible(id);
        String channel = producers.findById(production.producerId()).map(p -> p.channel().name()).orElse(null);
        return new DispatchView(channel, slips.find(id).orElse(null),
                notifications.productionOrderDeliveries(id, page, size));
    }

    /**
     * The production order as its producer reads it, with links valid for the configured time from {@code issuedAt};
     * empty when there is none. For {@code notification.api.ProductionOrderDirectory}: read when a message is written,
     * so an address or a text is never stored on the way.
     */
    @Transactional(readOnly = true)
    public Optional<ProductionOrderMail> mail(UUID id, Instant issuedAt) {
        return productionOrders.findById(id).map(production -> {
            Producer producer = producers.findById(production.producerId()).orElseThrow();
            Optional<WarehouseView> warehouse = warehouses.findWarehouse(production.receivingWarehouseId());
            Map<String, PackagingSpec.CartonSpec> sizes = cartonSizes(production);
            Map<String, String> names = names(production);
            List<ProductionOrderMail.Line> lines = production.lines().stream().map(line ->
                    new ProductionOrderMail.Line(line.sku(), names.get(line.sku()), line.cartons(),
                            line.unitsPerCarton(), line.totalUnits(), size(sizes.get(line.sku())),
                            line.packingRequirements(), new ProductionOrderMail.File(line.sku() + " print file",
                            printFilePath(id, line.designSnapshotId())))).toList();
            ProductionOrderMail.File slip = slips.find(id).map(found -> new ProductionOrderMail.File(
                    ProductionOrderSlip.fileName(production.code()), slipPath(id))).orElse(null);
            String stopReason = switch (production.status()) {
                case CANCELLED -> production.cancellationReason();
                case CLOSED -> production.closingReason();
                default -> null;
            };
            return new ProductionOrderMail(id, production.code(), producer.name(), producer.channel().name(),
                    producer.email(), production.drawnUpBy(), production.status().name(), production.needByDate(),
                    warehouse.map(WarehouseView::name).orElse(production.receivingWarehouseId().toString()),
                    warehouse.map(WarehouseView::address).orElse(null), production.sampleApprovalRequired(),
                    production.unitPrice(), production.currency(), production.note(), stopReason, lines, slip,
                    issuedAt.plus(linksValidFor));
        });
    }

    // ---- the producer's links ---------------------------------------------------------------------------------------

    /**
     * The slip, by a link its producer was sent: the signature, the production order still open, the stored slip still
     * the one sent; then a presigned URL of minutes.
     *
     * @throws BusinessException {@code PRODUCTION_ORDER_LINK_INVALID}; {@code PRODUCTION_ORDER_LINK_EXPIRED} (also once
     *         the production order is no longer open); {@code PRODUCTION_ORDER_SLIP_NOT_FOUND};
     *         {@code PRODUCTION_ORDER_SLIP_MISMATCH}
     */
    @Transactional(readOnly = true)
    public DownloadLink openSlip(UUID id, long expires, String signature) {
        return intactSlipLink(linked(slipPath(id), id, expires, signature));
    }

    /**
     * The print file of one of the production order's designs, by a link its producer was sent: the signature, the
     * production order still open, the design one of its lines', its print files still the approved ones; then a
     * presigned URL of minutes.
     *
     * @throws BusinessException {@code PRODUCTION_ORDER_LINK_INVALID}; {@code PRODUCTION_ORDER_LINK_EXPIRED};
     *         {@code PRINT_FILE_MISMATCH}; {@code NOT_FOUND} when the design has no print file
     */
    @Transactional(readOnly = true)
    public DownloadLink openPrintFile(UUID id, UUID designSnapshotId, long expires, String signature) {
        ProductionOrder production = linked(printFilePath(id, designSnapshotId), id, expires, signature);
        ProductionOrder.Line line = production.lines().stream()
                .filter(candidate -> candidate.designSnapshotId().equals(designSnapshotId)).findFirst()
                .orElseThrow(() -> invalidLink("not a design of production order " + production.code()));
        FulfillmentArtifact file;
        try {
            file = designs.fulfillmentDownload(designSnapshotId, line.printFileChecksum(),
                    DesignArtifactRole.PRINT_READY);
        } catch (BusinessException refused) {
            if (refused.errorCode() == ErrorCode.CONFLICT) {
                log.error("Production order {}: the print files of design {} no longer match what was approved",
                        production.code(), designSnapshotId);
                throw new BusinessException(ErrorCode.PRINT_FILE_MISMATCH, ("Production order %s: the print files "
                        + "no longer match what was approved").formatted(production.code()));
            }
            throw refused;
        }
        log.info("Production order {}: print file of design {} handed out by link", production.code(),
                designSnapshotId);
        return new DownloadLink(file.url(), file.expiresAt());
    }

    private ProductionOrder linked(String path, UUID id, long expires, String signature) {
        switch (links.verify(path, expires, signature, clock.instant())) {
            case INVALID -> throw invalidLink("the signature does not match");
            case EXPIRED -> throw new BusinessException(ErrorCode.PRODUCTION_ORDER_LINK_EXPIRED,
                    "This link has expired");
            case VALID -> {
                // checked below
            }
        }
        ProductionOrder production = productionOrders.findById(id)
                .orElseThrow(() -> invalidLink("no production order " + id));
        if (!production.sentAndOpen()) {
            throw new BusinessException(ErrorCode.PRODUCTION_ORDER_LINK_EXPIRED, ("Production order %s is %s: its "
                    + "files are no longer handed out").formatted(production.code(), production.status()));
        }
        return production;
    }

    /** The slip's presigned link, after checking the stored object still hashes to what was sent. */
    private DownloadLink intactSlipLink(ProductionOrder production) {
        ProductionOrderSlip slip = slips.find(production.id()).orElseThrow(() -> new BusinessException(
                ErrorCode.PRODUCTION_ORDER_SLIP_NOT_FOUND, "Production order %s has no slip"
                .formatted(production.code())));
        String actual = storage.stat(slip.storageKey()).sha256();
        if (actual == null) {
            // An object the store recorded no digest for: hash its bytes (a slip is small).
            actual = hash(slip.storageKey());
        }
        if (!slip.sha256().equals(actual)) {
            log.error("Production order {}: the stored slip no longer matches what was sent", production.code());
            throw new BusinessException(ErrorCode.PRODUCTION_ORDER_SLIP_MISMATCH, ("Production order %s: the stored "
                    + "slip no longer matches what was sent").formatted(production.code()));
        }
        return files.downloadLink(slip.storageKey(), ProductionOrderSlip.fileName(production.code()));
    }

    private String hash(String key) {
        MessageDigest digest = Checksums.sha256();
        try (InputStream in = new DigestInputStream(storage.download(key), digest)) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Could not read the slip", unreadable);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * A warehouse-bound caller (the warehouse manager) sees the production orders coming to their warehouses - the rule
     * {@code ProductionOrderService} reads them by.
     */
    private ProductionOrder visible(UUID id) {
        return productionOrders.findById(id)
                .filter(production -> WarehouseScope.restriction().map(AssignedWarehouses::ids)
                        .map(allowed -> allowed.contains(production.receivingWarehouseId())).orElse(true))
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCTION_ORDER_NOT_FOUND,
                        "No production order " + id));
    }

    // ---- facts ------------------------------------------------------------------------------------------------------

    /** Each SKU's name, as quotes and orders show it. */
    private Map<String, String> names(ProductionOrder production) {
        Map<String, String> names = new LinkedHashMap<>();
        production.lines().forEach(line -> customerSkus.find(line.sku()).map(CustomerSku::name)
                .ifPresent(name -> names.put(line.sku(), name)));
        return names;
    }

    /**
     * Each SKU's carton, from the item's master data (the packing rule chain of load planning); a SKU with none
     * recorded is absent. Advisory: the producer packs to the line's units per carton.
     */
    private Map<String, PackagingSpec.CartonSpec> cartonSizes(ProductionOrder production) {
        List<PackagingRequest> requests = production.lines().stream()
                .map(line -> new PackagingRequest(new Sku(line.sku()), line.totalUnits())).toList();
        Map<String, PackagingSpec.CartonSpec> sizes = new LinkedHashMap<>();
        // A customer's SKU has its inventory item from the day it was issued (CustomerSkus.issue): no item is a data
        // error, thrown rather than caught - caught inside the caller's transaction it would mark it rollback-only.
        for (PackagingSpec spec : packaging.resolveAll(requests)) {
            if (spec.status() == PackagingSpec.Status.RESOLVED && spec.carton() != null) {
                sizes.putIfAbsent(spec.sku(), spec.carton());
            }
        }
        return sizes;
    }

    /** The volume of every carton in cubic metres, or null when a SKU's carton size is unknown. */
    private static BigDecimal volume(ProductionOrder production, Map<String, PackagingSpec.CartonSpec> sizes) {
        BigDecimal cubicMillimetres = BigDecimal.ZERO;
        for (ProductionOrder.Line line : production.lines()) {
            PackagingSpec.CartonSpec carton = sizes.get(line.sku());
            if (carton == null) {
                return null;
            }
            cubicMillimetres = cubicMillimetres.add(BigDecimal.valueOf((long) carton.lengthMm() * carton.widthMm()
                    * carton.heightMm()).multiply(BigDecimal.valueOf(line.cartons())));
        }
        return cubicMillimetres.divide(BigDecimal.valueOf(1_000_000_000L), 3, RoundingMode.HALF_UP);
    }

    private static String size(PackagingSpec.CartonSpec carton) {
        return carton == null ? null : "%d x %d x %d mm".formatted(carton.lengthMm(), carton.widthMm(),
                carton.heightMm());
    }

    private static String contact(Producer producer) {
        List<String> parts = new ArrayList<>();
        for (String part : new String[] {producer.contactName(), producer.contactPhone(), producer.email()}) {
            if (part != null && !part.isBlank()) {
                parts.add(part);
            }
        }
        return parts.isEmpty() ? null : String.join(" - ", parts);
    }

    /** A staff member's name as the slip prints it: an account named on a production order exists (its foreign key). */
    private String person(UUID userId) {
        var user = identity.user(userId);
        return user.fullName() == null || user.fullName().isBlank() ? user.username() : user.fullName();
    }

    private static BusinessException invalidLink(String why) {
        return new BusinessException(ErrorCode.PRODUCTION_ORDER_LINK_INVALID, "This link is not valid: " + why);
    }
}
