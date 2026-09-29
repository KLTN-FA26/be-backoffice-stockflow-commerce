package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.*;
import com.stockflow.common.error.*;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.*;
import com.stockflow.inventory.internal.domain.*;
import com.stockflow.inventory.internal.entity.CycleCountJpaEntity;
import com.stockflow.inventory.internal.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.*;

@Service
@Transactional
public class CycleCountService {
    private final CycleCountJpaRepository counts;
    private final CycleCountRepository lines;
    private final StockItemRepository stocks;
    private final InventoryPolicyRepository policies;
    private final EntityManager entities;
    private final Clock clock;
    private final com.stockflow.identity.api.IdentityService identities;
    private final org.springframework.context.ApplicationEventPublisher events;
    public CycleCountService(CycleCountJpaRepository counts,CycleCountRepository lines,StockItemRepository stocks,
            InventoryPolicyRepository policies,EntityManager entities,Clock clock,org.springframework.context.ApplicationEventPublisher events,
            com.stockflow.identity.api.IdentityService identities) {
        this.counts=counts;this.lines=lines;this.stocks=stocks;this.policies=policies;this.entities=entities;this.clock=clock;this.events=events;
        this.identities=identities;
    }
    public record Summary(UUID id,String warehouse,UUID assignedTo,String status,long version,UUID approvedBy,
                          String note,java.time.Instant createdAt,java.time.Instant approvedAt,java.time.Instant postedAt,List<CycleCountLine> lines) {}
    @Transactional(readOnly=true)
    public Summary get(UUID id){return summary(require(id));}
    @Transactional(readOnly=true)
    public PageResponse<Summary> list(int page,int size) {
        return Pages.toResponse(counts.findAllInScope(null,Pages.of(page,size,org.springframework.data.domain.Sort.by("id").descending()))
                .map(c -> new Summary(c.getId(),c.getWarehouseCode(),c.getAssignedTo(),c.getStatus().name(),c.getVersion(),c.getApprovedBy(),c.getNote(),c.getCreatedAt(),c.getApprovedAt(),c.getPostedAt(),List.of())));
    }
    @Auditable(action=AuditAction.CREATE,resourceType="cycle-count")
    public Summary create(UUID requestId,String warehouse,UUID assignedTo,List<UUID> stockIds,String note) {
        warehouse=new LocationId(warehouse).code();
        authorizeNew(warehouse,assignedTo);
        if(stockIds.isEmpty() || stockIds.size()>200 || new HashSet<>(stockIds).size()!=stockIds.size())
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        lines.lockRequest(requestId);
        var previous=counts.findByRequestId(requestId);
        if(previous.isPresent()) {
            var prior=require(previous.get().getId());
            if(!prior.getWarehouseCode().equals(warehouse) || !prior.getAssignedTo().equals(assignedTo)
                    || !new HashSet<>(lines.lines(prior.getId()).stream().map(CycleCountLine::stockId).toList()).equals(new HashSet<>(stockIds)))
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            return summary(prior);
        }
        eligible(assignedTo);
        lines.skus(stockIds).forEach(policies::lock);
        var count=new CycleCountJpaEntity(Identifiers.newId(),requestId,warehouse,assignedTo,note);
        counts.saveAndFlush(count);
        for(UUID stockId:stockIds.stream().sorted(Comparator.comparing(UUID::toString)).toList()) {
            var stock=stocks.findByIdForUpdate(new StockItemId(stockId)).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
            if(!stock.location().warehouseCode().equals(warehouse)) throw new BusinessException(ErrorCode.NOT_FOUND);
            try {lines.add(count.getId(),stockId);}
            catch(org.springframework.dao.DuplicateKeyException conflict){throw new BusinessException(ErrorCode.COUNT_ALREADY_ACTIVE);}
        }
        return summary(count);
    }
    @Auditable(action=AuditAction.UPDATE,resourceType="cycle-count",resourceId="#id")
    public Summary start(UUID id,long version) {
        var count=locked(id,version); counter(count); var state=count.state().start();
        lockStocks(id); lines.refresh(id); count.apply(state,count.getNote());return saved(count);
    }
    @Auditable(action=AuditAction.UPDATE,resourceType="cycle-count",resourceId="#id")
    public Summary record(UUID id,UUID stockId,long version,int quantity,String reason) {
        var count=locked(id,version);counter(count);count.state().require(CycleCountStatus.COUNTING);
        lockStocks(id);
        var line=lines.lines(id).stream().filter(l -> l.stockId().equals(stockId)).findFirst().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        // Preserve the physical observation, even when holds make it unsafe to post yet.
        unchanged(line);CycleCount.validateMeasurement(line.baseline(),quantity,line.serial(),reason);
        lines.count(id,stockId,quantity,reason);return saved(count);
    }
    @Auditable(action=AuditAction.UPDATE,resourceType="cycle-count",resourceId="#id")
    public Summary submit(UUID id,long version) {
        var count=locked(id,version);counter(count);lockStocks(id);var items=lines.lines(id);
        validate(items);boolean variance=items.stream().anyMatch(l -> l.counted()!=l.baseline());
        count.apply(count.state().submit(variance),count.getNote());
        if(!variance)lines.release(id);
        return saved(count);
    }
    @Auditable(action=AuditAction.APPROVE,resourceType="cycle-count",resourceId="#id")
    public Summary approve(UUID id,long version) {
        var count=locked(id,version);lockStocks(id);validate(lines.lines(id));
        count.apply(count.state().approve(actor()),count.getNote());return saved(count);
    }
    @Auditable(action=AuditAction.UPDATE,resourceType="cycle-count",resourceId="#id")
    public Summary recount(UUID id,long version,String reason) {
        if(reason==null || reason.isBlank())throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        var count=locked(id,version);counter(count);var state=count.state().recount();
        lockStocks(id);lines.refresh(id);count.apply(state,reason);return saved(count);
    }
    @Auditable(action=AuditAction.UPDATE,resourceType="cycle-count",resourceId="#id")
    public Summary cancel(UUID id,long version,String reason) {
        if(reason==null || reason.isBlank())throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        var count=locked(id,version);count.apply(count.state().cancel(),reason);lines.release(id);return saved(count);
    }
    @Auditable(action=AuditAction.APPROVE,resourceType="cycle-count",resourceId="#id")
    public Summary reject(UUID id,long version,String reason) {
        if(reason==null || reason.isBlank())throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        var count=locked(id,version);count.state().require(CycleCountStatus.VARIANCE_REVIEW);
        lockStocks(id);lines.refresh(id);count.apply(count.state().recount(),reason);return saved(count);
    }
    /** Reassignment clears prior measurements/approval; a replacement must count independently. */
    @Auditable(action=AuditAction.APPROVE,resourceType="cycle-count",resourceId="#id")
    public Summary reassign(UUID id,long version,UUID assignedTo,String reason) {
        if(reason==null || reason.isBlank())throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        var count=locked(id,version);eligible(assignedTo);
        var state=count.state().reassign(assignedTo);
        lockStocks(id);lines.refresh(id);count.apply(state,reason);return saved(count);
    }
    @Auditable(action=AuditAction.APPROVE,resourceType="cycle-count",resourceId="#id")
    public Summary post(UUID id,long version) {
        var count=require(id);entities.refresh(count,LockModeType.PESSIMISTIC_WRITE);
        if(count.getStatus()==CycleCountStatus.POSTED)return summary(count);
        if(count.getVersion()!=version)throw new BusinessException(ErrorCode.CONFLICT);
        var state=count.state().post();
        // Policy changes and positive corrections serialize before taking stock row locks.
        lines.lines(id).stream().map(CycleCountLine::sku).distinct().sorted().forEach(policies::lock);
        lockStocks(id);var items=lines.lines(id);validate(items);
        for(var line:items) if(line.counted()!=line.baseline()) {
            lines.post(id,line,count.getAssignedTo(),count.getApprovedBy(),clock.instant());
            events.publishEvent(new com.stockflow.contracts.StockLevelChanged(line.sku()));
        }
        count.apply(state,count.getNote());lines.release(id);return saved(count);
    }
    private void lockStocks(UUID id) {
        for(var line:lines.lines(id))stocks.findByIdForUpdate(new StockItemId(line.stockId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
    private void validate(List<CycleCountLine> items) {
        for(var l:items) {
            unchanged(l);
            if(l.counted()==null)throw new BusinessException(ErrorCode.COUNT_INCOMPLETE);
            CycleCount.validateQuantity(l.baseline(),l.counted(),l.reserved(),l.serial(),l.reason());
            if(l.counted()>0)policies.find(l.sku()).ifPresent(p -> StockPolicy.validateCount(p,l));
        }
    }
    private void unchanged(CycleCountLine line) {
        if(line.version()!=line.currentVersion() || line.baseline()!=line.onHand())throw new BusinessException(ErrorCode.COUNT_STOCK_CHANGED);
    }
    private CycleCountJpaEntity require(UUID id){return counts.findByIdInScope(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));}
    private CycleCountJpaEntity locked(UUID id,long version) {
        var count=require(id);entities.refresh(count,LockModeType.PESSIMISTIC_WRITE);
        if(count.getVersion()!=version)throw new BusinessException(ErrorCode.CONFLICT);return count;
    }
    private Summary saved(CycleCountJpaEntity c){c.stamp(clock.instant());c.touch();counts.flush();return summary(c);}
    private Summary summary(CycleCountJpaEntity c){return new Summary(c.getId(),c.getWarehouseCode(),c.getAssignedTo(),c.getStatus().name(),c.getVersion(),c.getApprovedBy(),c.getNote(),c.getCreatedAt(),c.getApprovedAt(),c.getPostedAt(),lines.lines(c.getId()));}
    private static UUID actor(){return DataScopeContext.current().orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN)).user().userId();}
    private static void counter(CycleCountJpaEntity c){if(!c.getAssignedTo().equals(actor()))throw new BusinessException(ErrorCode.FORBIDDEN);}
    private void eligible(UUID user) {
        if(!identities.isActiveUserWithAnyRole(user,"WAREHOUSE_STAFF","WAREHOUSE_MANAGER","INVENTORY_PLANNER"))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    /** Creation has no persisted row for ScopedJpaRepository to filter yet. Existing reads use it. */
    private static void authorizeNew(String warehouse,UUID assignedTo) {
        var scope=DataScopeContext.current().orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        boolean allowed=switch(scope.effective()) {
            case ALL -> true;
            case WAREHOUSE -> scope.user().warehouseCodes().contains(warehouse);
            case OWN -> scope.user().userId().equals(assignedTo) && scope.user().warehouseCodes().contains(warehouse);
            default -> false;
        };
        if(!allowed)throw new BusinessException(ErrorCode.OUT_OF_DATA_SCOPE);
    }
}
