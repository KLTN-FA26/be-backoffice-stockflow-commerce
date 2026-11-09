package com.stockflow.order.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.common.storage.PreparedFiles;
import com.stockflow.common.storage.StagedUploads;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.common.storage.UploadSlot;
import com.stockflow.common.storage.UploadSlotRequest;
import com.stockflow.contracts.ProductionOrderSampleApproved;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.ProductionOrder;
import com.stockflow.order.internal.domain.ProductionOrderRepository;
import com.stockflow.order.internal.domain.ProductionOrderStatus;
import com.stockflow.order.internal.domain.ReprintReason;
import com.stockflow.order.internal.domain.SalesContract;
import com.stockflow.order.internal.domain.SalesContractRepository;
import com.stockflow.order.internal.domain.SampleRound;
import com.stockflow.order.internal.domain.SampleRoundRepository;
import com.stockflow.order.internal.domain.SampleRounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Print sample rounds (4A-4; kltn-docs 17 §4.3 steps 9-10, BR-24; 13 §4.3 step 5, BR-03, BR-13; 14 BR-18).
 *
 * <ul>
 *   <li><b>The Production Coordinator</b> uploads the photos and videos of what the producer printed: round 1 once the
 *       producer accepted the order, which moves it to SAMPLE_PENDING; a new round, with the reprint reason, after
 *       the customer asked for one. They record when a physical sample went out, and add photos to a round still
 *       waiting.</li>
 *   <li><b>The customer</b> who owns the production order sees the rounds waiting for them and answers each once:
 *       approved - mass printing starts, the producer is told - or printed again, with a note. Another customer's
 *       round does not exist for them.</li>
 *   <li>Every view carries the contract's free sample rounds against the rounds counted so far: past them is the
 *       coordinator's and Sales' warning, never a refusal (14 BR-18).</li>
 * </ul>
 *
 * <p>Every write locks the sales order, then the production order (the order every production-order write keeps -
 * {@code ProductionOrderService}), and writes the round before the production order it moves:
 * {@code tg_production_order_sample} reads the round. The rounds need no version of their own: a round is opened or
 * answered only under that lock, and whether it may be is decided from the rounds read under it - two coordinators
 * opening round 2 together, or a customer answering twice, find the first one's round there.</p>
 *
 * <p>Files are checked, scanned and stored before any lock is taken, then adopted by the short transaction that
 * records them, as {@code ShipmentPodFiles} does; an upload attached by an earlier try of the same request answers
 * with what it made. Links to them are short-lived and never logged.</p>
 */
@Service
@Transactional
public class ProductionSampleService {

    private static final Logger log = LoggerFactory.getLogger(ProductionSampleService.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** A production order's rounds, and its contract's free sample rounds against those counted. */
    public record RoundsView(ProductionOrder order, SampleRounds rounds, SampleRounds.Allowance allowance) {
    }

    private final ProductionOrderRepository productionOrders;
    private final SampleRoundRepository sampleRounds;
    private final OrderRepository orders;
    private final SalesContractRepository contracts;
    private final FileTransfers transfers;
    private final StagedUploads staged;
    private final TransactionOperations tx;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    ProductionSampleService(ProductionOrderRepository productionOrders, SampleRoundRepository sampleRounds,
                            OrderRepository orders, SalesContractRepository contracts, FileTransfers transfers,
                            StagedUploads staged, TransactionOperations tx, ApplicationEventPublisher events,
                            Clock clock) {
        this.productionOrders = productionOrders;
        this.sampleRounds = sampleRounds;
        this.orders = orders;
        this.contracts = contracts;
        this.transfers = transfers;
        this.staged = staged;
        this.tx = tx;
        this.events = events;
        this.clock = clock;
    }

    // ---- the coordinator --------------------------------------------------------------------------------------------

    /** A production order's rounds; a warehouse-bound caller sees those received at their warehouses only. */
    @Transactional(readOnly = true)
    public RoundsView rounds(UUID productionOrderId) {
        return view(visible(productionOrderId));
    }

    /**
     * A slot to upload one photo or video of a print sample of this production order straight to the object store
     * (FileCategory.PRINT_SAMPLE - a video is too large to pass through the backend).
     *
     * @throws BusinessException {@code PRODUCTION_ORDER_NOT_FOUND}; {@code PRODUCTION_ORDER_SAMPLE_NOT_REQUIRED}
     */
    @Transactional(readOnly = true)
    public UploadSlot beginUpload(UUID productionOrderId, UploadSlotRequest request, UUID by) {
        ProductionOrder production = visible(productionOrderId);
        requireSampleWanted(production);
        if (production.status() != ProductionOrderStatus.ACCEPTED
                && production.status() != ProductionOrderStatus.SAMPLE_PENDING) {
            throw new BusinessException(ErrorCode.PRODUCTION_ORDER_STATUS_INVALID, ("Production order %s is %s: print "
                    + "samples are uploaded once the producer accepted it and until the customer approves one")
                    .formatted(production.code(), production.status()));
        }
        return staged.begin(FileCategory.PRINT_SAMPLE, by, request);
    }

    /**
     * Opens the next round of the production order's print sample with {@code files} (17 §4.3 step 9): round 1 moves it
     * from ACCEPTED to SAMPLE_PENDING; a later round follows the customer's reprint request and says why it is printed
     * again. Not transactional itself: the files are prepared first.
     *
     * @param reason null for round 1; PRINT_DEFECT or CUSTOMER_CHANGE after
     * @param sentOn the day a physical sample went to the customer, if one did
     * @throws BusinessException {@code PRODUCTION_ORDER_NOT_FOUND}; {@code PRODUCTION_ORDER_SAMPLE_NOT_REQUIRED};
     *         {@code PRODUCTION_ORDER_STATUS_INVALID}; {@code SAMPLE_ROUND_NOT_OPENABLE}; {@code VALIDATION_FAILED}
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "production-sample-round", resourceId = "#productionOrderId")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RoundsView open(UUID productionOrderId, ReprintReason reason, LocalDate sentOn, IncomingFiles files,
                           UUID by) {
        requireFileCount(files);
        Optional<RoundsView> replay = replay(productionOrderId, null, files, by);
        if (replay.isPresent()) {
            return replay.get();
        }
        // Refused before the files are read and stored, where the bytes would not change the answer.
        tx.executeWithoutResult(status -> requireSampleWanted(visible(productionOrderId)));
        try (PreparedFiles prepared = files.prepare(FileCategory.PRINT_SAMPLE, transfers, staged)) {
            return tx.execute(status -> {
                ProductionOrder production = lockedAfterItsOrder(productionOrderId);
                SampleRounds rounds = sampleRounds.of(productionOrderId);
                Instant now = clock.instant();
                SampleRound opened = rounds.open(production, reason, sentOn, media(prepared, by, now), by, now,
                        today());
                // The round first: tg_production_order_sample lets the production order wait for its sample once
                // round 1 is there.
                sampleRounds.insert(opened);
                ProductionOrder saved = opened.roundNo() == 1 ? productionOrders.save(production) : production;
                prepared.adopt();
                RoundsView view = new RoundsView(saved, rounds, allowance(saved, rounds));
                log.info("Production order {}: print sample round {} opened with {} file(s)", saved.code(),
                        opened.roundNo(), opened.media().size());
                if (view.allowance().exceeded()) {
                    log.warn("Production order {}: {} print sample round(s) counted, {} free on the contract (14 BR-18)",
                            saved.code(), view.allowance().counted(), view.allowance().free());
                }
                return view;
            });
        }
    }

    /**
     * More photos or videos for the round still waiting for the customer. Not transactional itself: the files are
     * prepared first.
     *
     * @throws BusinessException {@code PRODUCTION_ORDER_NOT_FOUND}; {@code SAMPLE_ROUND_NOT_FOUND};
     *         {@code SAMPLE_ROUND_ANSWERED}; {@code PRODUCTION_ORDER_STATUS_INVALID}; {@code VALIDATION_FAILED}
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "production-sample-round", resourceId = "#roundId",
            details = "{productionOrderId: #productionOrderId}")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RoundsView addMedia(UUID productionOrderId, UUID roundId, IncomingFiles files, UUID by) {
        requireFileCount(files);
        Optional<RoundsView> replay = replay(productionOrderId, roundId, files, by);
        if (replay.isPresent()) {
            return replay.get();
        }
        tx.executeWithoutResult(status -> sampleRounds.of(productionOrderId)
                .waitingFor(visible(productionOrderId), roundId));
        try (PreparedFiles prepared = files.prepare(FileCategory.PRINT_SAMPLE, transfers, staged)) {
            return tx.execute(status -> {
                ProductionOrder production = lockedAfterItsOrder(productionOrderId);
                SampleRounds rounds = sampleRounds.of(productionOrderId);
                List<SampleRound.Media> media = media(prepared, by, clock.instant());
                rounds.addMedia(production, roundId, media);
                sampleRounds.addMedia(roundId, media);
                prepared.adopt();
                return new RoundsView(production, rounds, allowance(production, rounds));
            });
        }
    }

    /**
     * The day a physical sample of the round went to the customer (17 §4.3 step 9), while the round waits.
     *
     * @throws BusinessException {@code PRODUCTION_ORDER_NOT_FOUND}; {@code SAMPLE_ROUND_NOT_FOUND};
     *         {@code SAMPLE_ROUND_ANSWERED}; {@code PRODUCTION_ORDER_STATUS_INVALID}; {@code VALIDATION_FAILED}
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "production-sample-round", resourceId = "#roundId",
            details = "{productionOrderId: #productionOrderId}")
    public RoundsView physicalSampleSent(UUID productionOrderId, UUID roundId, LocalDate on) {
        ProductionOrder production = lockedAfterItsOrder(productionOrderId);
        SampleRounds rounds = sampleRounds.of(productionOrderId);
        sampleRounds.update(rounds.physicalSampleSent(production, roundId, on, today()));
        return new RoundsView(production, rounds, allowance(production, rounds));
    }

    /** A short link showing one photo or video of a round in the page; never logged. */
    @Transactional(readOnly = true)
    public DownloadLink mediaLink(UUID productionOrderId, UUID roundId, UUID mediaId) {
        RoundsView view = view(visible(productionOrderId));
        return transfers.viewLink(mediaOf(view.rounds(), roundId, mediaId).storageKey());
    }

    // ---- the customer -----------------------------------------------------------------------------------------------

    /**
     * The customer's production orders whose print sample waits for them (13 §4.3 step 5), with every round - the one
     * waiting and the answers before it.
     */
    @Transactional(readOnly = true)
    public List<RoundsView> awaiting(UUID customerId) {
        List<UUID> ids = sampleRounds.awaitingCustomer(customerId);
        Map<UUID, SampleRounds> rounds = sampleRounds.of(ids);
        List<RoundsView> views = new ArrayList<>();
        for (ProductionOrder production : productionOrders.findAllById(ids)) {
            if (customerId.equals(production.customerId())) {
                SampleRounds of = rounds.getOrDefault(production.id(), new SampleRounds(production.id(), List.of()));
                views.add(new RoundsView(production, of, allowance(production, of)));
            }
        }
        views.sort(java.util.Comparator.comparing(v -> v.rounds().waiting().map(SampleRound::openedAt)
                .orElse(Instant.MAX)));
        return views;
    }

    /**
     * The production order of one of the customer's own rounds, whatever its status now - a round answered meanwhile
     * shows its answer (13 edge cases).
     *
     * @throws BusinessException {@code SAMPLE_ROUND_NOT_FOUND} for no such round, or another customer's
     */
    @Transactional(readOnly = true)
    public RoundsView customerRound(UUID customerId, UUID roundId) {
        return view(ownedBy(customerId, roundId));
    }

    /** A short link showing one photo or video of the customer's own round; never logged. */
    @Transactional(readOnly = true)
    public DownloadLink customerMediaLink(UUID customerId, UUID roundId, UUID mediaId) {
        RoundsView view = view(ownedBy(customerId, roundId));
        return transfers.viewLink(mediaOf(view.rounds(), roundId, mediaId).storageKey());
    }

    /**
     * The customer's one answer to their round (13 BR-13). Approved, the production order goes into mass production -
     * the sales order records that printing started (BR-11), in this transaction - and its producer is told to print
     * the batch (B31). Printed again, it stays SAMPLE_PENDING for the coordinator to open the next round.
     *
     * @param userId the customer's portal account: who answered
     * @throws BusinessException {@code SAMPLE_ROUND_NOT_FOUND} for no such round or another customer's;
     *         {@code SAMPLE_ROUND_ANSWERED}; {@code PRODUCTION_ORDER_STATUS_INVALID} once the production order is no
     *         longer pending; {@code VALIDATION_FAILED} for a reprint request with no note
     */
    @Auditable(action = AuditAction.APPROVE, resourceType = "production-sample-round", resourceId = "#roundId",
            details = "{answer: #answer}")
    public RoundsView answer(UUID customerId, UUID userId, UUID roundId, SampleRound.Result answer, String note) {
        UUID productionOrderId = ownedBy(customerId, roundId).id();
        ProductionOrder production = lockedAfterItsOrder(productionOrderId);
        SampleRounds rounds = sampleRounds.of(productionOrderId);
        Instant now = clock.instant();
        SampleRound answered = rounds.answer(production, customerId, roundId, answer, note, userId, now);
        // The round first: tg_production_order_sample starts mass printing on an approved round only.
        sampleRounds.update(answered);
        ProductionOrder saved = production;
        if (answer == SampleRound.Result.APPROVED) {
            saved = productionOrders.save(production);
            tellProducerSampleApproved(saved, now);
        }
        log.info("Production order {}: print sample round {} {} by its customer", saved.code(), answered.roundNo(),
                answer == SampleRound.Result.APPROVED ? "approved" : "sent back to be printed again");
        return new RoundsView(saved, rounds, allowance(saved, rounds));
    }

    /**
     * B31: the producer is told the sample is approved, to print the batch - after commit, through the notification
     * module, which listens for {@link ProductionOrderSampleApproved} (4A-3). When 4A-3's
     * {@code ProductionOrderDispatch.sampleApproved} is on this branch, this is a call to it: it publishes the same
     * event with the same facts, and checks the production order is IN_PRODUCTION with a sample required first.
     */
    private void tellProducerSampleApproved(ProductionOrder production, Instant at) {
        events.publishEvent(new ProductionOrderSampleApproved(Identifiers.newId(), production.id(), production.code(),
                at));
    }

    // ---- shared -----------------------------------------------------------------------------------------------------

    private RoundsView view(ProductionOrder production) {
        SampleRounds rounds = sampleRounds.of(production.id());
        return new RoundsView(production, rounds, allowance(production, rounds));
    }

    /** 14 BR-18: the free sample rounds of the order's live contract; unknown once it is gone. */
    private SampleRounds.Allowance allowance(ProductionOrder production, SampleRounds rounds) {
        Integer free = contracts.findLiveByOrderId(production.salesOrderId()).map(SalesContract::freeSampleRounds)
                .orElse(null);
        return rounds.allowance(free);
    }

    private ProductionOrder ownedBy(UUID customerId, UUID roundId) {
        return sampleRounds.productionOrderOf(roundId).flatMap(productionOrders::findById)
                .filter(production -> production.customerId().equals(customerId))
                .orElseThrow(() -> new BusinessException(ErrorCode.SAMPLE_ROUND_NOT_FOUND, "No print sample round "
                        + roundId));
    }

    /**
     * The production order locked after its sales order - the lock order of every write to a production order
     * ({@code ProductionOrderService}); the progress listener reloads the sales order under the lock already held.
     */
    private ProductionOrder lockedAfterItsOrder(UUID productionOrderId) {
        UUID salesOrderId = visible(productionOrderId).salesOrderId();
        orders.findByIdForUpdate(new OrderId(salesOrderId)).orElseThrow();
        return productionOrders.findByIdForUpdate(productionOrderId).orElseThrow(() -> notFound(productionOrderId));
    }

    /** A warehouse-bound caller sees the production orders coming to their warehouses, as on the production order. */
    private ProductionOrder visible(UUID productionOrderId) {
        return productionOrders.findById(productionOrderId)
                .filter(production -> WarehouseScope.restriction()
                        .map(allowed -> allowed.ids().contains(production.receivingWarehouseId())).orElse(true))
                .orElseThrow(() -> notFound(productionOrderId));
    }

    private static void requireSampleWanted(ProductionOrder production) {
        if (!production.sampleApprovalRequired()) {
            throw new BusinessException(ErrorCode.PRODUCTION_ORDER_SAMPLE_NOT_REQUIRED, ("Production order %s has no "
                    + "print sample to approve (BR-24)").formatted(production.code()));
        }
    }

    /** Refused before anything is read or stored: a request's file count is known from the request alone. */
    private static void requireFileCount(IncomingFiles files) {
        if (files == null || files.count() < 1 || files.count() > SampleRounds.MEDIA_PER_UPLOAD_MAX) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, ("A print sample round takes 1 to %d photos or "
                    + "videos at once").formatted(SampleRounds.MEDIA_PER_UPLOAD_MAX));
        }
    }

    /**
     * Every upload of the request already shown by a round of this production order ({@code roundId}'s, when named) was
     * attached by an earlier try of this request, which committed without its caller learning it: the answer is the
     * rounds as they are, not a second round. Only some of them attached, or attached to another round or production
     * order, is no earlier try of this request - refused rather than answered as if it had done something.
     *
     * @throws BusinessException {@code CONFLICT}
     */
    private Optional<RoundsView> replay(UUID productionOrderId, UUID roundId, IncomingFiles files, UUID by) {
        if (!(files instanceof IncomingFiles.Uploaded uploaded)) {
            return Optional.empty();
        }
        List<String> keys = uploaded.uploadIds().stream()
                .map(id -> staged.keyOf(FileCategory.PRINT_SAMPLE, by, id))
                .flatMap(Optional::stream).toList();
        return tx.execute(status -> {
            Map<String, UUID> stored = sampleRounds.roundsOfKeys(keys);
            if (stored.isEmpty()) {
                return Optional.empty();
            }
            RoundsView view = view(visible(productionOrderId));
            Set<UUID> own = new java.util.HashSet<>();
            view.rounds().rounds().forEach(round -> own.add(round.id()));
            boolean earlierTry = stored.size() == uploaded.uploadIds().size() && stored.values().stream()
                    .allMatch(round -> own.contains(round) && (roundId == null || round.equals(roundId)));
            if (!earlierTry) {
                throw new BusinessException(ErrorCode.CONFLICT, ("%d of the uploads are attached to a print sample "
                        + "round already: upload the files again").formatted(stored.size()));
            }
            return Optional.of(view);
        });
    }

    private static List<SampleRound.Media> media(PreparedFiles prepared, UUID by, Instant at) {
        return prepared.files().stream().map(file -> {
            StoredFile stored = file.file();
            String name = stored.originalName() == null || stored.originalName().isBlank() ? "sample"
                    : stored.originalName().length() > 255 ? stored.originalName().substring(0, 255)
                    : stored.originalName();
            return new SampleRound.Media(Identifiers.newId(), stored.key(), name, stored.contentType(),
                    stored.sizeBytes(), by, at);
        }).toList();
    }

    private static SampleRound.Media mediaOf(SampleRounds rounds, UUID roundId, UUID mediaId) {
        return rounds.find(roundId).flatMap(round -> round.media().stream()
                        .filter(media -> media.id().equals(mediaId)).findFirst())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No such photo or video " + mediaId));
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), BUSINESS_ZONE);
    }

    private static BusinessException notFound(UUID id) {
        return new BusinessException(ErrorCode.PRODUCTION_ORDER_NOT_FOUND, "No production order " + id);
    }
}
