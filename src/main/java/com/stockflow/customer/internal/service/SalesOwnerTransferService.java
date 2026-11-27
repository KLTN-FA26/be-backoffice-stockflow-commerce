package com.stockflow.customer.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.SalesOwnerChangeReason;
import com.stockflow.customer.api.SalesOwnerState;
import com.stockflow.customer.internal.domain.CustomerRepository;
import com.stockflow.customer.internal.domain.SalesOwner;
import com.stockflow.customer.internal.domain.SalesOwnerTransfers;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.SalesStaff;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * A Sales Manager's bulk transfer of one salesperson's customers, a temporary one for a long leave, and its return in
 * one step (sales-owner plan BR-S09, §10.1). One transaction per request.
 *
 * <h2>Retried requests</h2>
 * <p>The client names each request ({@code requestId}). The batch row is written first with {@code ON CONFLICT DO
 * NOTHING} on that id: a retry - or the same request twice at once, which waits on the first's insert - finds the batch
 * and answers what it did, moving nothing again. A return is keyed by its temporary batch, so it happens once.</p>
 *
 * <h2>Who is skipped (case 9)</h2>
 * <p>Only customers that are the source salesperson's when the batch runs move: assigned to them, or waiting for an
 * owner after they left. A customer the request named that was not theirs is {@code NOT_OWNED_BY_SOURCE}; one that
 * stopped being theirs while the batch ran - found so under its row lock - is {@code OWNER_CHANGED}; an id that is no
 * customer is {@code CUSTOMER_NOT_FOUND}. Customers are locked one by one in id order - PostgreSQL's order of uuids
 * ({@link Identifiers#DATABASE_ORDER}), the order every multi-customer write of this module uses, including the release
 * of a gone owner's customers and the return of a batch, which take theirs from SQL - so two batches cannot deadlock.
 * Java's own {@code UUID.compareTo} compares signed halves and disagrees with it for half of all ids (finding C4).</p>
 */
@Service
@Transactional
public class SalesOwnerTransferService {

    /** The most customers one request may name or move; a salesperson's book is far smaller. */
    static final int MAX_CUSTOMERS = 2000;

    /** The most targets one request spreads customers over. */
    static final int MAX_TARGETS = 50;

    /** The reasons a manager may give a transfer; a return has its own, and the rest belong to other paths. */
    private static final Set<SalesOwnerChangeReason> TRANSFER_REASONS = EnumSet.of(SalesOwnerChangeReason.STAFF_LEFT,
            SalesOwnerChangeReason.WORKLOAD, SalesOwnerChangeReason.CUSTOMER_REQUEST, SalesOwnerChangeReason.REGION,
            SalesOwnerChangeReason.TEMPORARY_LEAVE, SalesOwnerChangeReason.OTHER);

    private final SalesOwnerTransfers transfers;
    private final CustomerRepository customers;
    private final SalesOwnershipService ownership;
    private final SalesStaff salesStaff;
    private final IdentityService identity;
    private final Clock clock;

    SalesOwnerTransferService(SalesOwnerTransfers transfers, CustomerRepository customers,
                              SalesOwnershipService ownership, SalesStaff salesStaff, IdentityService identity,
                              Clock clock) {
        this.transfers = transfers;
        this.customers = customers;
        this.ownership = ownership;
        this.salesStaff = salesStaff;
        this.identity = identity;
        this.clock = clock;
    }

    /**
     * What a manager asks.
     *
     * @param toUserIds   one target, or several to spread the customers over evenly (in this order)
     * @param customerIds the customers to move; null or empty for every customer of {@code fromUserId}
     * @param temporary   a long leave (§10.1): the reason is TEMPORARY_LEAVE and the batch can be returned
     */
    public record Transfer(UUID requestId, UUID fromUserId, List<UUID> toUserIds, List<UUID> customerIds,
                           SalesOwnerChangeReason reason, String note, boolean temporary, boolean notifyCustomer) {
    }

    /** A customer that did not move, and why. */
    public record Skipped(UUID customerId, SalesOwnerTransfers.Skip reason) {
    }

    /** {@code moved} is a count; {@code skipped} lists each customer left as it was. */
    public record Result(UUID batchId, int moved, List<Skipped> skipped) {
    }

    /** A batch as the managers' list shows it (BO-1). */
    public record BatchRow(UUID batchId, UUID fromUserId, String fromName, List<String> toNames,
                           SalesOwnerChangeReason reason, String note, boolean temporary, Instant createdAt,
                           String createdByName, int moved, int stillHeld, Instant returnedAt) {
    }

    /**
     * Moves the customers.
     *
     * @throws BusinessException {@code VALIDATION_FAILED} (no request id, no target, a target that is the source, more
     *         than the limits, a temporary transfer whose reason is not TEMPORARY_LEAVE or the reverse, a reason a
     *         transfer cannot have); {@code SALES_OWNER_CHANGE_REASON_REQUIRED}; {@code USER_NOT_FOUND} for an unknown
     *         source; {@code SALES_OWNER_NOT_ASSIGNABLE} when a target is not an active Sales agent (nothing moves)
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "customer-sales-owner-transfer",
            resourceId = "#result?.batchId()")
    public Result transfer(Transfer request, UUID actorUserId) {
        List<UUID> targets = validated(request);
        Instant now = clock.instant();
        SalesOwnerTransfers.Batch batch = new SalesOwnerTransfers.Batch(Identifiers.newId(), request.requestId(),
                request.fromUserId(), request.reason(), blankToNull(request.note()), request.temporary(), null,
                request.notifyCustomer(), 0, 0, actorUserId, now, List.of());
        if (!transfers.claim(batch)) {
            return replay(request.requestId());
        }
        SalesOwnersTransferPlan plan = plan(request);
        List<SalesOwnerTransfers.Item> items = new ArrayList<>(plan.notOwned().stream()
                .map(id -> new SalesOwnerTransfers.Item(id, null, SalesOwnerTransfers.Skip.NOT_OWNED_BY_SOURCE))
                .toList());
        int next = 0;
        for (UUID customerId : plan.candidates()) {
            UUID target = targets.get(next % targets.size());
            boolean moved = ownership.transfer(customerId, request.fromUserId(), target, request.reason(),
                    batch.note(), request.notifyCustomer(), actorUserId, batch.id()).isPresent();
            if (moved) {
                next++;
                items.add(new SalesOwnerTransfers.Item(customerId, target, null));
            } else {
                items.add(new SalesOwnerTransfers.Item(customerId, null, SalesOwnerTransfers.Skip.OWNER_CHANGED));
            }
        }
        transfers.complete(batch.id(), items, plan.notFound());
        return result(batch.id(), items, plan.notFound());
    }

    /**
     * Gives a temporary batch's customers back to the salesperson they were taken from (§10.1): each one still held by
     * the target the batch gave it to (assigned to them, or waiting after they left). One moved on since is skipped
     * {@code REASSIGNED_SINCE}: who has it now is the manager's call. Returning a batch already returned answers that
     * return again.
     *
     * @throws BusinessException {@code SALES_OWNER_TRANSFER_NOT_FOUND}; {@code SALES_OWNER_TRANSFER_NOT_RETURNABLE} for
     *         a batch that is not temporary; {@code SALES_OWNER_NOT_ASSIGNABLE} when the salesperson is not back (not an
     *         active Sales agent): nothing moves
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "customer-sales-owner-transfer",
            resourceId = "#result?.batchId()")
    public Result returnBatch(UUID batchId, UUID actorUserId) {
        SalesOwnerTransfers.Batch temporary = transfers.findForUpdate(batchId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SALES_OWNER_TRANSFER_NOT_FOUND));
        if (!temporary.temporary()) {
            throw new BusinessException(ErrorCode.SALES_OWNER_TRANSFER_NOT_RETURNABLE);
        }
        var returned = transfers.returnOf(batchId);
        if (returned.isPresent()) {
            return replay(returned.get().requestId());
        }
        UUID original = temporary.fromUserId();
        requireAssignable(original);
        SalesOwnerTransfers.Batch giveBack = new SalesOwnerTransfers.Batch(Identifiers.newId(),
                UUID.nameUUIDFromBytes(("sales-owner-return:" + batchId).getBytes(StandardCharsets.UTF_8)), original,
                SalesOwnerChangeReason.RETURN_FROM_LEAVE, temporary.note(), false, batchId, temporary.notifyCustomer(),
                0, 0, actorUserId, clock.instant(), List.of());
        if (!transfers.claim(giveBack)) {
            return replay(giveBack.requestId());
        }
        List<SalesOwnerTransfers.Item> items = new ArrayList<>();
        for (SalesOwnerTransfers.Item taken : transfers.items(batchId)) {
            if (!taken.moved()) {
                continue;
            }
            boolean moved = ownership.transfer(taken.customerId(), taken.toUserId(), original,
                    SalesOwnerChangeReason.RETURN_FROM_LEAVE, temporary.note(), temporary.notifyCustomer(),
                    actorUserId, giveBack.id()).isPresent();
            items.add(moved ? new SalesOwnerTransfers.Item(taken.customerId(), original, null)
                    : new SalesOwnerTransfers.Item(taken.customerId(), null,
                            SalesOwnerTransfers.Skip.REASSIGNED_SINCE));
        }
        transfers.complete(giveBack.id(), items, List.of());
        return result(giveBack.id(), items, List.of());
    }

    /** The batches, newest first (returns are shown on the batch they return); {@code temporaryOnly} for §10.1. */
    @Transactional(readOnly = true)
    public List<BatchRow> list(boolean temporaryOnly) {
        List<SalesOwnerTransfers.Summary> summaries = transfers.list(temporaryOnly, 200);
        Set<UUID> people = new HashSet<>();
        summaries.forEach(summary -> {
            people.add(summary.batch().fromUserId());
            people.add(summary.batch().requestedBy());
            people.addAll(summary.toUserIds());
        });
        people.remove(null);
        Map<UUID, String> names = people.isEmpty() ? new java.util.HashMap<>() : new java.util.HashMap<>(identity.displayNames(people));
        return summaries.stream().map(summary -> {
            SalesOwnerTransfers.Batch batch = summary.batch();
            return new BatchRow(batch.id(), batch.fromUserId(), names.get(batch.fromUserId()),
                    summary.toUserIds().stream().map(names::get).filter(Objects::nonNull).sorted().toList(),
                    batch.reason(), batch.note(), batch.temporary(), batch.requestedAt(),
                    names.get(batch.requestedBy()), batch.moved(), summary.stillHeld(), summary.returnedAt());
        }).toList();
    }

    /** The customers a request names, sorted into those that move (in id order) and those skipped before the locks. */
    private record SalesOwnersTransferPlan(List<UUID> candidates, List<UUID> notOwned, List<UUID> notFound) {
    }

    private SalesOwnersTransferPlan plan(Transfer request) {
        boolean named = request.customerIds() != null && !request.customerIds().isEmpty();
        // C4: PostgreSQL's order of uuids, the order every multi-customer write of this module locks rows in.
        Collection<UUID> asked = new TreeSet<>(Identifiers.DATABASE_ORDER);
        asked.addAll(named ? request.customerIds() : ownedBy(request.fromUserId()));
        Map<UUID, SalesOwner> owners = customers.salesOwners(asked);
        List<UUID> candidates = new ArrayList<>();
        List<UUID> notOwned = new ArrayList<>();
        List<UUID> notFound = new ArrayList<>();
        for (UUID id : asked) {
            SalesOwner owner = owners.get(id);
            if (owner == null) {
                notFound.add(id);
            } else if (owner.state() == SalesOwnerState.UNASSIGNED || !owner.userId().equals(request.fromUserId())) {
                notOwned.add(id);
            } else {
                candidates.add(id);
            }
        }
        return new SalesOwnersTransferPlan(candidates, notOwned, notFound);
    }

    /** Every customer assigned to the salesperson, or waiting for an owner after they left. */
    private List<UUID> ownedBy(UUID userId) {
        List<UUID> ids = new ArrayList<>(customers.idsOwnedBy(userId, SalesOwnerState.ASSIGNED));
        ids.addAll(customers.idsOwnedBy(userId, SalesOwnerState.NEEDS_ASSIGNMENT));
        return ids;
    }

    private List<UUID> validated(Transfer request) {
        if (request.requestId() == null || request.fromUserId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Name the request and the salesperson");
        }
        if (request.reason() == null) {
            throw new BusinessException(ErrorCode.SALES_OWNER_CHANGE_REASON_REQUIRED);
        }
        if (!TRANSFER_REASONS.contains(request.reason())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A transfer cannot give " + request.reason());
        }
        if (request.reason() == SalesOwnerChangeReason.OTHER && blankToNull(request.note()) == null) {
            throw new BusinessException(ErrorCode.SALES_OWNER_CHANGE_REASON_REQUIRED);
        }
        if (request.temporary() != (request.reason() == SalesOwnerChangeReason.TEMPORARY_LEAVE)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A temporary transfer has the reason TEMPORARY_LEAVE, and only it");
        }
        List<UUID> targets = request.toUserIds() == null ? List.of()
                : List.copyOf(new LinkedHashSet<>(request.toUserIds().stream().filter(Objects::nonNull).toList()));
        if (targets.isEmpty() || targets.size() > MAX_TARGETS || targets.contains(request.fromUserId())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Name one to %d salespeople other than the one the customers leave".formatted(MAX_TARGETS));
        }
        if (request.customerIds() != null && request.customerIds().size() > MAX_CUSTOMERS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "At most %d customers at once".formatted(MAX_CUSTOMERS));
        }
        if (identity.userLabels(List.of(request.fromUserId())).isEmpty()) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        targets.forEach(this::requireAssignable);
        return targets;
    }

    /** The account read {@code FOR SHARE}: it cannot be locked or lose SALES_STAFF until the batch commits (C2). */
    private void requireAssignable(UUID userId) {
        if (!salesStaff.lockActiveSalesAgent(userId)) {
            throw new BusinessException(ErrorCode.SALES_OWNER_NOT_ASSIGNABLE,
                    "Account %s is not an active salesperson".formatted(userId));
        }
    }

    private Result replay(UUID requestId) {
        SalesOwnerTransfers.Batch batch = transfers.findByRequestId(requestId)
                .orElseThrow(() -> new IllegalStateException("Batch of request " + requestId + " vanished"));
        return result(batch.id(), transfers.items(batch.id()), batch.notFound());
    }

    private static Result result(UUID batchId, List<SalesOwnerTransfers.Item> items, List<UUID> notFound) {
        List<Skipped> skipped = new ArrayList<>(items.stream().filter(item -> !item.moved())
                .map(item -> new Skipped(item.customerId(), item.skip())).toList());
        notFound.forEach(id -> skipped.add(new Skipped(id, SalesOwnerTransfers.Skip.CUSTOMER_NOT_FOUND)));
        skipped.sort(java.util.Comparator.comparing(Skipped::customerId));
        int moved = (int) items.stream().filter(SalesOwnerTransfers.Item::moved).count();
        return new Result(batchId, moved, List.copyOf(skipped));
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
