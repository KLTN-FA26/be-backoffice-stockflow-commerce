package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.Role;
import com.stockflow.design.api.DesignArtifactRole;
import com.stockflow.design.api.DesignService;
import com.stockflow.fulfillment.api.FulfillmentArtifactAccess;
import com.stockflow.fulfillment.api.FulfillmentService;
import com.stockflow.fulfillment.api.FulfillmentTask;
import com.stockflow.fulfillment.internal.domain.DesignVerificationResult;
import com.stockflow.fulfillment.internal.domain.PackStatus;
import com.stockflow.fulfillment.internal.domain.PickStatus;
import com.stockflow.fulfillment.internal.entity.DesignVerificationJpaEntity;
import com.stockflow.fulfillment.internal.entity.PackJpaEntity;
import com.stockflow.fulfillment.internal.entity.PickJpaEntity;
import com.stockflow.fulfillment.internal.repository.DesignVerificationJpaRepository;
import com.stockflow.fulfillment.internal.repository.PackJpaRepository;
import com.stockflow.fulfillment.internal.repository.PickJpaRepository;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.identity.api.IdentityService;
import java.time.Clock;
import java.util.UUID;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Fulfillment transaction boundary and task/data-scope enforcement. */
@Service
@Transactional
class FulfillmentServiceImpl implements FulfillmentService {

    private final PickJpaRepository picks;
    private final PackJpaRepository packs;
    private final DesignVerificationJpaRepository verifications;
    private final OrderService orders;
    private final DesignService designs;
    private final Clock clock;
    private final IdentityService identities;

    FulfillmentServiceImpl(PickJpaRepository picks, PackJpaRepository packs,
            DesignVerificationJpaRepository verifications, OrderService orders,
            DesignService designs, IdentityService identities, Clock clock) {
        this.picks = picks;
        this.packs = packs;
        this.verifications = verifications;
        this.orders = orders;
        this.designs = designs;
        this.identities = identities;
        this.clock = clock;
    }

    @Override
    public FulfillmentTask createTask(UUID orderId, UUID assignedUserId, CurrentUser actor) {
        requireRole(actor, Role.ORDER_COORDINATOR, Role.WAREHOUSE_MANAGER);
        requireWarehouseAssignee(assignedUserId);
        var existing = picks.findByOrderId(orderId);
        if (existing.isPresent()) {
            return visible(existing.get(), actor);
        }
        OrderSummary order = requireOrder(orderId);
        if (order.status() != OrderStatus.PAID) {
            throw new BusinessException(ErrorCode.CONFLICT, "Only a paid order can enter fulfillment");
        }
        orders.releaseToFulfillment(orderId);
        var now = clock.instant();
        var pick = new PickJpaEntity(Identifiers.newId(), null, orderId, PickStatus.PENDING);
        pick.assign(assignedUserId, now);
        picks.save(pick);
        packs.save(new PackJpaEntity(Identifiers.newId(), pick.getId(), PackStatus.PENDING, null));
        return toTask(pick);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FulfillmentTask> listTasks(CurrentUser actor) {
        java.util.List<PickJpaEntity> selected;
        if (actor.hasAnyRole(Role.WAREHOUSE_MANAGER, Role.ORDER_COORDINATOR)) {
            selected = picks.findTop100ByOrderByCreatedAtDesc();
        } else if (actor.hasRole(Role.QC_STAFF)) {
            selected = picks.findByPackStatus(PackStatus.ON_HOLD, PageRequest.of(0, 100));
        } else if (actor.hasRole(Role.WAREHOUSE_STAFF)) {
            selected = picks.findTop100ByAssignedUserIdOrderByCreatedAtDesc(actor.userId());
        } else {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return selected.stream().map(this::toTask).toList();
    }

    @Override
    public FulfillmentTask assign(UUID taskId, UUID assignedUserId, CurrentUser actor) {
        requireRole(actor, Role.ORDER_COORDINATOR, Role.WAREHOUSE_MANAGER);
        requireWarehouseAssignee(assignedUserId);
        var pick = lockPick(taskId);
        pick.assign(assignedUserId, clock.instant());
        return toTask(picks.save(pick));
    }

    @Override
    @Transactional(readOnly = true)
    public FulfillmentTask findTask(UUID taskId, CurrentUser actor) {
        return visible(requirePick(taskId), actor);
    }

    @Override
    public FulfillmentTask startPicking(UUID taskId, CurrentUser actor) {
        var pick = lockPick(taskId);
        pick.start(actor.userId(), clock.instant());
        return toTask(picks.save(pick));
    }

    @Override
    public FulfillmentTask completePicking(UUID taskId, CurrentUser actor) {
        var pick = lockPick(taskId);
        pick.complete(actor.userId(), clock.instant());
        return toTask(picks.save(pick));
    }

    @Override
    @Transactional(readOnly = true)
    public FulfillmentArtifactAccess artifactDownload(UUID taskId, UUID orderLineId, String role,
                                                       CurrentUser actor) {
        var pick = requirePick(taskId);
        requireArtifactAccess(pick, actor);
        var line = requireOrder(pick.getOrderId()).lines().stream()
                .filter(value -> value.lineId().equals(orderLineId))
                .findFirst().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (line.designSnapshotId() == null || line.designChecksum() == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Order line has no confirmed design");
        }
        DesignArtifactRole parsedRole;
        try {
            parsedRole = DesignArtifactRole.valueOf(role.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_PARAMETER, "Unknown design artifact role");
        }
        var artifact = designs.fulfillmentDownload(
                line.designSnapshotId(), line.designChecksum(), parsedRole);
        return new FulfillmentArtifactAccess(artifact.artifactId(), artifact.role().name(),
                artifact.originalName(), artifact.contentType(), artifact.sizeBytes(), artifact.checksum(),
                artifact.url(), artifact.expiresAt());
    }

    @Override
    public FulfillmentTask completePacking(UUID taskId, CurrentUser actor) {
        var pick = lockPick(taskId);
        pick.requireAssigned(actor.userId());
        if (pick.getStatus() != PickStatus.PICKED) {
            throw new BusinessException(ErrorCode.CONFLICT, "Picking must finish before packing");
        }
        var pack = requirePack(pick.getId());
        if (pack.getStatus() == PackStatus.ON_HOLD) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "A checksum hold requires QC or warehouse-manager reverification");
        }
        boolean matching = verifyAll(pack, requireOrder(pick.getOrderId()), actor.userId());
        if (!matching) {
            pack.hold();
            packs.save(pack);
            orders.putOnDesignHold(pick.getOrderId(), "DESIGN_MISMATCH");
            return toTask(pick);
        }
        pack.complete(clock.instant());
        packs.save(pack);
        return toTask(pick);
    }

    @Override
    public FulfillmentTask reverifyDesignIntegrity(UUID taskId, String resolutionNote, CurrentUser actor) {
        requireRole(actor, Role.QC_STAFF, Role.WAREHOUSE_MANAGER);
        var pick = lockPick(taskId);
        var pack = requirePack(pick.getId());
        if (pack.getStatus() != PackStatus.ON_HOLD) {
            throw new BusinessException(ErrorCode.CONFLICT, "The package has no design-integrity hold");
        }
        if (!verifyAll(pack, requireOrder(pick.getOrderId()), actor.userId())) {
            return toTask(pick);
        }
        pack.complete(clock.instant());
        packs.save(pack);
        orders.resolveDesignHold(pick.getOrderId(), actor.userId(), resolutionNote);
        return toTask(pick);
    }

    private boolean verifyAll(PackJpaEntity pack, OrderSummary order, UUID actor) {
        boolean allMatch = true;
        for (var line : order.lines()) {
            if (line.designSnapshotId() == null) {
                continue;
            }
            var check = designs.verifyForFulfillment(line.designSnapshotId(), line.designChecksum());
            var result = check.matching() ? DesignVerificationResult.MATCH : DesignVerificationResult.MISMATCH;
            verifications.save(new DesignVerificationJpaEntity(Identifiers.newId(), pack.getId(), order.orderId(),
                    line.lineId(), line.designSnapshotId(), check.expectedChecksum(), check.actualChecksum(),
                    result, actor, clock.instant()));
            allMatch &= check.matching();
        }
        return allMatch;
    }

    private FulfillmentTask visible(PickJpaEntity pick, CurrentUser actor) {
        if (!java.util.Objects.equals(pick.getAssignedUserId(), actor.userId())
                && !actor.hasAnyRole(Role.WAREHOUSE_MANAGER, Role.QC_STAFF, Role.ORDER_COORDINATOR)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return toTask(pick);
    }

    private void requireArtifactAccess(PickJpaEntity pick, CurrentUser actor) {
        if (!java.util.Objects.equals(pick.getAssignedUserId(), actor.userId())
                && !actor.hasAnyRole(Role.WAREHOUSE_MANAGER, Role.QC_STAFF)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    private FulfillmentTask toTask(PickJpaEntity pick) {
        var pack = requirePack(pick.getId());
        var evidence = verifications.findByPackIdOrderByVerifiedAtDesc(pack.getId()).stream()
                .map(value -> new FulfillmentTask.IntegrityEvidence(value.getOrderLineId(), value.getSnapshotId(),
                        value.getExpectedChecksum(), value.getActualChecksum(),
                        value.getResult() == DesignVerificationResult.MATCH, value.getVerifiedAt()))
                .toList();
        return new FulfillmentTask(pick.getId(), pick.getOrderId(), pick.getAssignedUserId(),
                pick.getStatus().name(), pack.getStatus().name(), pick.getAssignedAt(), pick.getStartedAt(),
                pick.getPickedAt(), pack.getPackedAt(), evidence);
    }

    private PickJpaEntity requirePick(UUID taskId) {
        return picks.findById(taskId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private PickJpaEntity lockPick(UUID taskId) {
        return picks.lockById(taskId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private PackJpaEntity requirePack(UUID pickId) {
        return packs.findByPickId(pickId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private OrderSummary requireOrder(UUID orderId) {
        return orders.findById(orderId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private static void requireRole(CurrentUser actor, Role... roles) {
        if (actor == null || !actor.hasAnyRole(roles)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private void requireWarehouseAssignee(UUID userId) {
        if (!identities.isActiveUserWithAnyRole(userId,
                Role.WAREHOUSE_STAFF.authority(), Role.WAREHOUSE_MANAGER.authority())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Assignee must be an active warehouse staff member or warehouse manager");
        }
    }
}
