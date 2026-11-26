package com.stockflow.order.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.config.SellerProperties;
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
import com.stockflow.design.api.DesignArtifactRole;
import com.stockflow.design.api.DesignService;
import com.stockflow.design.api.FulfillmentArtifact;
import com.stockflow.design.api.FulfillmentAsset;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.StaffUser;
import com.stockflow.inventory.api.PackagingRequest;
import com.stockflow.inventory.api.PackagingSpec;
import com.stockflow.inventory.api.PackagingSpecResolver;
import com.stockflow.inventory.api.WarehouseOccupancy;
import com.stockflow.notification.api.DeliveryAttemptSummary;
import com.stockflow.notification.api.NotificationService;
import com.stockflow.notification.api.ProductionOrderMail;
import com.stockflow.notification.api.ProductionOrderResends;
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
import java.util.HashMap;
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
 *   <li><b>The coordinator sends it.</b> Before the send's transaction, the slip is drawn from what was read and kept
 *       as a {@link FileCategory#DOCUMENT} ({@link #prepareSlip}) - no lock is held while a PDF is drawn and stored;
 *       in the transaction, once the production order is SENT, the slip is recorded and {@link ProductionOrderSent}
 *       published ({@link #sent}). The send re-checks the version it read, so the slip is of the production order as
 *       sent; when the transaction fails, the stored file goes with it.</li>
 *   <li><b>The producer is told</b> by notification, through the producer's channel: e-mailed with links to the slip,
 *       the print files and the customer's original images, or nothing for a producer the coordinator hands the slip.
 *       What the message says is read when it is written ({@link #mail}, behind
 *       {@code notification.api.ProductionOrderDirectory}).</li>
 *   <li><b>"Sample approved, print the batch"</b> ({@link #sampleApproved}, called by 4A-4) and <b>"stop"</b>
 *       ({@link #stopped}: a sent production order cancelled or closed) go the same way.</li>
 *   <li><b>The links</b> work for {@code stockflow.order.production-order.file-links-valid-for} and are checked again on
 *       every use ({@link #openSlip}, {@link #openPrintFile}): the signature, the production order still open, the
 *       file's digest still the approved one. Only then is a presigned URL of minutes handed out - never logged.</li>
 *   <li><b>What "checksum checked on download" checks.</b> The digest the object store keeps for the object (computed
 *       from its bytes when it was stored), against the one recorded when it was sent; the bytes are hashed only when
 *       the store kept no digest. It catches another object put under the key, not a change to the bytes that left the
 *       store's digest metadata as it was - reading every print file through the application on each download would
 *       defeat the presigned URL.</li>
 * </ul>
 */
@Service
@Transactional
public class ProductionOrderDispatch {

    private static final Logger log = LoggerFactory.getLogger(ProductionOrderDispatch.class);
    private static final String FILES = "/api/v1/production-order-files/";

    /** The slip as stored before the send commits: adopted by it, or deleted when it does not. */
    public static final class PreparedSlip implements AutoCloseable {
        private final PreparedFiles files;
        private final int totalCartons;
        private final BigDecimal volumeM3;

        private PreparedSlip(PreparedFiles files, int totalCartons, BigDecimal volumeM3) {
            this.files = files;
            this.totalCartons = totalCartons;
            this.volumeM3 = volumeM3;
        }

        /** Where the slip is stored; kept by the send, deleted by {@link #close} when it was not. */
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
    private final SalesContractRepository contracts;
    private final SellerProperties seller;
    private final IdentityService identity;
    private final WarehouseService warehouses;
    private final CustomerSkus customerSkus;
    private final PackagingSpecResolver packaging;
    private final WarehouseOccupancy occupancy;
    private final DesignService designs;
    private final NotificationService notifications;
    private final ProductionOrderResends resends;
    private final ProductionOrderSlipDocument document;
    private final FileTransfers files;
    private final FileStorage storage;
    private final SignedLinks links;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final Duration linksValidFor;
    private final TransactionTemplate readOnly;
    private final OrderRepository orders;

    ProductionOrderDispatch(ProductionOrderRepository productionOrders, ProductionOrderSlipRepository slips,
                            ProducerRepository producers, SalesContractRepository contracts,
                            SellerProperties seller, IdentityService identity, WarehouseService warehouses,
                            CustomerSkus customerSkus, PackagingSpecResolver packaging, DesignService designs,
                            NotificationService notifications, ProductionOrderResends resends,
                            ProductionOrderSlipDocument document,
                            FileTransfers files, FileStorage storage, SignedLinks links,
                            ApplicationEventPublisher events, Clock clock, PlatformTransactionManager transactions,
                            @Value("${stockflow.order.production-order.file-links-valid-for:P30D}")
                            Duration linksValidFor, WarehouseOccupancy occupancy, OrderRepository orders) {
        this.occupancy = occupancy;
        this.orders = orders;
        this.productionOrders = productionOrders;
        this.slips = slips;
        this.producers = producers;
        this.contracts = contracts;
        this.seller = seller;
        this.identity = identity;
        this.warehouses = warehouses;
        this.customerSkus = customerSkus;
        this.packaging = packaging;
        this.designs = designs;
        this.notifications = notifications;
        this.resends = resends;
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

    /** The route serving one of the customer's original images of one of a production order's designs. */
    public static String originalPath(UUID productionOrderId, UUID designSnapshotId, UUID assetId) {
        return printFilePath(productionOrderId, designSnapshotId) + "/originals/" + assetId;
    }

    // ---- sending ----------------------------------------------------------------------------------------------------

    /**
     * Draws the slip of {@code production} as it is sent at {@code at}, and stores it. Not transactional: called
     * before the send's transaction, with no lock held. The slip names who drew the order up, for questions; who sent
     * it is recorded in the system, and the slip needs no signature.
     *
     * @return the stored slip; close it when the send is done - it deletes the file unless the send adopted it
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public PreparedSlip prepareSlip(ProductionOrder production, Instant at) {
        // Read in one short read-only transaction - the aggregates are mapped inside it - then drawn and stored outside.
        Slip facts = readOnly.execute(status -> slipFacts(production, at));
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

    private Slip slipFacts(ProductionOrder production, Instant at) {
        Map<String, PackagingSpec.CartonSpec> sizes = cartonSizes(production);
        Producer producer = producers.findById(production.producerId()).orElseThrow(() -> new BusinessException(
                ErrorCode.PRODUCER_NOT_FOUND, "No producer " + production.producerId()));
        Optional<WarehouseView> warehouse = warehouses.findWarehouse(production.receivingWarehouseId());
        // The slip no longer prints the delivery batch (design 2026-11-19), but sending a production order whose
        // batch is gone from the live contract is still refused, as it was when the slip printed its window.
        boolean batchLive = contracts.findLiveByOrderId(production.salesOrderId()).map(SalesContract::schedule)
                .map(schedule -> schedule.stream().anyMatch(b -> b.id().equals(production.deliveryBatchId())))
                .orElse(false);
        if (!batchLive) {
            throw new BusinessException(ErrorCode.PRODUCTION_ORDER_NOT_ALLOWED,
                    "Production order %s: its delivery batch is gone".formatted(production.code()));
        }
        List<ProductionOrderSlipDocument.SlipLine> lines = new ArrayList<>();
        Map<String, String> names = names(production);
        for (ProductionOrder.Line line : production.lines()) {
            lines.add(new ProductionOrderSlipDocument.SlipLine(line.sku(), names.get(line.sku()), line.cartons(),
                    line.unitsPerCarton(), line.totalUnits(), size(sizes.get(line.sku())), line.packingRequirements(),
                    line.designSnapshotId(), line.printFileChecksum()));
        }
        var coordinator = identity.user(production.drawnUpBy());
        return new Slip(new ProductionOrderSlipDocument.Facts(production.code(), at, seller.name(), producer.code(),
                producer.name(), contact(producer),
                warehouse.map(WarehouseView::name).orElse(production.receivingWarehouseId().toString()),
                warehouse.map(WarehouseView::address).orElse(null), production.needByDate(),
                production.sampleApprovalRequired(), production.note(), person(coordinator), coordinator.email(),
                lines), volume(production,
                occupancy.cartonVolumesM3(production.lines().stream().map(ProductionOrder.Line::sku).toList())));
    }

    /**
     * In the send's transaction, once {@code production} is SENT: keeps its slip and publishes
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

    /**
     * Sends the producer of a production order still open its latest message again, with links fresh from now: the way
     * back after the automatic retries gave up, the coordinator corrected the producer's address, or the links died
     * (expired, or the links key was rotated). Notification decides which message and refuses one still on its way.
     *
     * @throws BusinessException {@code PRODUCTION_ORDER_NOT_FOUND} (also outside a warehouse-bound caller's
     *         warehouses); {@code PRODUCTION_ORDER_RESEND_NOT_ALLOWED} when it is not sent or no longer open, its
     *         producer is handed the slip by the coordinator, or its latest message is still being sent
     */
    public ProductionOrderResends.Resend resend(UUID id) {
        ProductionOrder production = visible(id);
        if (!production.sentAndOpen()) {
            throw new BusinessException(ErrorCode.PRODUCTION_ORDER_RESEND_NOT_ALLOWED, ("Production order %s is %s: "
                    + "only a sent production order still open is sent again").formatted(production.code(),
                    production.status()));
        }
        Producer producer = producers.findById(production.producerId()).orElseThrow(() -> new BusinessException(
                ErrorCode.PRODUCER_NOT_FOUND, "No producer " + production.producerId()));
        if (producer.channel() != Producer.Channel.EMAIL) {
            throw new BusinessException(ErrorCode.PRODUCTION_ORDER_RESEND_NOT_ALLOWED, ("Production order %s: its "
                    + "producer is handed the slip by the coordinator, nothing is e-mailed").formatted(
                    production.code()));
        }
        ProductionOrderResends.Resend resend = resends.resend(id, clock.instant());
        log.info("Production order {}: its {} message goes to the producer again (generation {})", production.code(),
                resend.message(), resend.generation());
        return resend;
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
                            printFilePath(id, line.designSnapshotId())), originals(id, line.designSnapshotId())))
                    .toList();
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
                    production.note(), stopReason, lines, slip, issuedAt.plus(linksValidFor));
        });
    }

    /** A link route for each original image the design places, named as the customer uploaded it. */
    private List<ProductionOrderMail.File> originals(UUID id, UUID designSnapshotId) {
        return designs.confirmedAssets(designSnapshotId).stream().map(asset -> new ProductionOrderMail.File(
                asset.originalName(), originalPath(id, designSnapshotId, asset.assetId()))).toList();
    }

    /**
     * The production order as a stop message needs it: its code, status, reason and producer only. Nothing about its
     * lines is read - a SKU whose item or packaging went missing must not keep the producer from being told to stop.
     */
    @Transactional(readOnly = true)
    public Optional<ProductionOrderMail> stopMail(UUID id) {
        return productionOrders.findById(id).map(production -> {
            Producer producer = producers.findById(production.producerId()).orElseThrow();
            String stopReason = switch (production.status()) {
                case CANCELLED -> production.cancellationReason();
                case CLOSED -> production.closingReason();
                default -> null;
            };
            return new ProductionOrderMail(id, production.code(), producer.name(), producer.channel().name(),
                    producer.email(), production.drawnUpBy(), production.status().name(), production.needByDate(),
                    null, null, production.sampleApprovalRequired(), null, stopReason, List.of(), null, null);
        });
    }

    /**
     * The slip as sent, to attach to the message that sends it (for {@code notification.api.ProductionOrderDirectory}):
     * the stored bytes, hashed and compared with the digest recorded when it was sent, as a link checks them on every
     * use.
     * Empty when there is no slip or the stored one changed - the producer then gets the links, which refuse it too.
     */
    @Transactional(readOnly = true)
    public Optional<byte[]> slipPdf(UUID id) {
        return slips.find(id).flatMap(slip -> {
            byte[] pdf;
            try (InputStream in = storage.download(slip.storageKey())) {
                pdf = in.readAllBytes();
            } catch (IOException unreadable) {
                throw new UncheckedIOException("Could not read the slip of production order " + id, unreadable);
            }
            if (!slip.sha256().equals(Checksums.sha256Hex(pdf))) {
                log.error("Production order {}: the stored slip no longer matches what was sent; not attached", id);
                return Optional.empty();
            }
            return Optional.of(pdf);
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

    /**
     * One of the customer's original images of one of the production order's designs, by a link its producer was sent
     * (kltn-docs 12 §3, FE request 2026-11-18 BE-6): checked as {@link #openPrintFile} checks the print file - the
     * signature, the production order still open, the design one of its lines', the image still the approved one and
     * the bundle still adding up to the line's checksum; then a presigned URL of minutes.
     *
     * @throws BusinessException {@code PRODUCTION_ORDER_LINK_INVALID}; {@code PRODUCTION_ORDER_LINK_EXPIRED};
     *         {@code PRINT_FILE_MISMATCH}; {@code NOT_FOUND} when the design places no such image
     */
    @Transactional(readOnly = true)
    public DownloadLink openOriginal(UUID id, UUID designSnapshotId, UUID assetId, long expires, String signature) {
        ProductionOrder production = linked(originalPath(id, designSnapshotId, assetId), id, expires, signature);
        ProductionOrder.Line line = production.lines().stream()
                .filter(candidate -> candidate.designSnapshotId().equals(designSnapshotId)).findFirst()
                .orElseThrow(() -> invalidLink("not a design of production order " + production.code()));
        FulfillmentAsset file;
        try {
            file = designs.assetDownload(designSnapshotId, line.printFileChecksum(), assetId);
        } catch (BusinessException refused) {
            if (refused.errorCode() == ErrorCode.CONFLICT) {
                log.error("Production order {}: the original images of design {} no longer match what was approved",
                        production.code(), designSnapshotId);
                throw new BusinessException(ErrorCode.PRINT_FILE_MISMATCH, ("Production order %s: the print files "
                        + "no longer match what was approved").formatted(production.code()));
            }
            throw refused;
        }
        log.info("Production order {}: original image {} of design {} handed out by link", production.code(), assetId,
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

    /** The slip's presigned link, after checking the store's digest of the object is still the one sent. */
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
     * Each SKU's carton as the contract was signed in it: the carton of the order line the production line was made
     * for (Q7, Q10), measured as now - so a contract signed before the company changed carton is packed as signed, not
     * in the variant's new carton. A line whose order line names no carton (older data) falls back to the packing rule
     * chain of load planning, the variant's active carton. A SKU with no measured carton is absent. Advisory: the
     * producer packs to the line's units per carton.
     */
    private Map<String, PackagingSpec.CartonSpec> cartonSizes(ProductionOrder production) {
        Map<UUID, UUID> signed = new HashMap<>();
        orders.findById(new OrderId(production.salesOrderId())).ifPresent(order -> order.lines().stream()
                .filter(line -> line.uomConversionId() != null)
                .forEach(line -> signed.put(line.id(), line.uomConversionId())));
        Map<String, PackagingSpec.CartonSpec> sizes = new LinkedHashMap<>();
        List<PackagingRequest> unsigned = new ArrayList<>();
        for (ProductionOrder.Line line : production.lines()) {
            UUID unit = signed.get(line.orderLineId());
            if (unit == null) {
                unsigned.add(new PackagingRequest(new Sku(line.sku()), line.totalUnits()));
            } else {
                packaging.unitCarton(new Sku(line.sku()), unit)
                        .ifPresent(carton -> sizes.putIfAbsent(line.sku(), carton));
            }
        }
        // Every SKU of an order has its inventory item from the order's creation on (placing the order opens it, owner
        // decision Q5): no item is a data error, thrown rather than caught - caught inside the caller's transaction it
        // would mark it rollback-only.
        if (!unsigned.isEmpty()) {
            for (PackagingSpec spec : packaging.resolveAll(unsigned)) {
                if (spec.status() == PackagingSpec.Status.RESOLVED && spec.carton() != null) {
                    sizes.putIfAbsent(spec.sku(), spec.carton());
                }
            }
        }
        return sizes;
    }

    /**
     * The volume of every carton in cubic metres (three decimals), or null when a SKU's carton has no size. Measured by
     * the carton spec of inventory (BE1), the measure the warehouse capacity board counts what is coming by, so the m³
     * the warehouse manager is told are the m³ the board adds to its incoming column - not by the packing rule chain of
     * load planning ({@link #cartonSizes(ProductionOrder)}), which only advises the producer on the slip.
     *
     * @param cartonVolumes one carton of each SKU, m³ ({@code WarehouseOccupancy.cartonVolumesM3})
     */
    private static BigDecimal volume(ProductionOrder production, Map<String, BigDecimal> cartonVolumes) {
        BigDecimal volume = BigDecimal.ZERO;
        for (ProductionOrder.Line line : production.lines()) {
            BigDecimal carton = cartonVolumes.get(line.sku());
            if (carton == null) {
                return null;
            }
            volume = volume.add(carton.multiply(BigDecimal.valueOf(line.cartons())));
        }
        return volume.setScale(3, RoundingMode.HALF_UP);
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
    private static String person(StaffUser user) {
        return user.fullName() == null || user.fullName().isBlank() ? user.username() : user.fullName();
    }

    private static BusinessException invalidLink(String why) {
        return new BusinessException(ErrorCode.PRODUCTION_ORDER_LINK_INVALID, "This link is not valid: " + why);
    }
}
