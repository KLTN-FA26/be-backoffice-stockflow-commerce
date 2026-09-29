package com.stockflow.order.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.*;
import com.stockflow.common.domain.*;
import com.stockflow.common.error.*;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.*;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.design.api.DesignService;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.order.internal.domain.*;
import com.stockflow.order.internal.entity.DesignQuoteJpaEntity;
import com.stockflow.order.internal.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
@Transactional
public class DesignQuoteService {
    private final DesignQuoteJpaRepository quotes;
    private final QuoteOfferRepository offers;
    private final CustomerService customers;
    private final DesignService designs;
    private final EntityManager entities;
    private final Clock clock;
    public DesignQuoteService(DesignQuoteJpaRepository quotes,QuoteOfferRepository offers,CustomerService customers,
                              DesignService designs,EntityManager entities,Clock clock){
        this.quotes=quotes;this.offers=offers;this.customers=customers;this.designs=designs;this.entities=entities;this.clock=clock;
    }
    public record Summary(UUID id,UUID customerId,UUID designSnapshotId,String sku,String status,long version,
                          QuoteOffer offer,UUID acceptedBy,Instant acceptedAt,UUID consumedOrderId,String responseNote){}
    @Transactional(readOnly=true)
    public Summary get(UUID id){return summary(require(id));}
    @Transactional(readOnly=true)
    public PageResponse<Summary> list(int page,int size){return Pages.toResponse(quotes.findAllInScope(
            (root,query,cb)->ownScope()?cb.isTrue(root.get("currentRevisionIssued")):cb.conjunction(),
            Pages.of(page,size,org.springframework.data.domain.Sort.by("id").descending())).map(this::summary));}
    @Transactional(readOnly=true)
    public PageResponse<QuoteOffer> history(UUID id,int page,int size){var q=require(id);var p=Pages.of(page,size);
        return PageResponse.of(offers.page(id,p.getOffset(),p.getPageSize(),ownScope()),page,size,offers.count(id,ownScope()));}
    @Auditable(action=AuditAction.CREATE,resourceType="design-quote")
    public Summary create(UUID requestId,UUID customerId,UUID snapshotId,String code,int quantity,BigDecimal price,Instant validUntil,String terms){
        // Creation has no scoped row yet. Only company-wide sales grants may create customer offers.
        if(DataScopeContext.current().map(s->s.effective()!=DataScope.ALL).orElse(true))throw new BusinessException(ErrorCode.OUT_OF_DATA_SCOPE);
        String sku=new Sku(code).code();offers.lockRequest(requestId);
        var prior=quotes.findByRequestId(requestId);
        if(prior.isPresent()){
            var q=require(prior.get().getId());var original=offers.get(q.getId(),1);
            if(!q.getCustomerId().equals(customerId)||!q.getDesignSnapshotId().equals(snapshotId)||!q.getSku().equals(sku)
                    ||original.quantity()!=quantity||price==null||original.unitPrice().amount().compareTo(price)!=0
                    ||validUntil==null||!original.validUntil().equals(validUntil.truncatedTo(java.time.temporal.ChronoUnit.MICROS))||terms==null||!original.terms().equals(terms.trim()))
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            return summary(q);
        }
        var customer=customers.findById(customerId).filter(c->"ACTIVE".equals(c.status()) && c.userId()!=null)
                .orElseThrow(()->new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
        designs.verifySnapshotForSku(snapshotId,customerId,sku);
        var offer=QuoteOffer.create(1,quantity,price,validUntil,terms,clock.instant(),actor());
        var q=new DesignQuoteJpaEntity(Identifiers.newId(),requestId,customerId,customer.userId(),snapshotId,sku);
        quotes.saveAndFlush(q);offers.save(q.getId(),offer);return summary(q);
    }
    @Auditable(action=AuditAction.UPDATE,resourceType="design-quote",resourceId="#id")
    public Summary revise(UUID id,long version,int quantity,BigDecimal price,Instant validUntil,String terms){
        var q=locked(id,version);q.state().revise();
        var offer=QuoteOffer.create(q.getRevision()+1,quantity,price,validUntil,terms,clock.instant(),actor());
        q.revise();offers.save(id,offer);return saved(q);
    }
    @Auditable(action=AuditAction.APPROVE,resourceType="design-quote",resourceId="#id")
    public Summary issue(UUID id,long version){var q=locked(id,version);
        q.transition(q.state().issue(offers.get(id,q.getRevision()),clock.instant()),null);
        offers.issue(id,q.getRevision(),clock.instant(),actor());return saved(q);}
    @Auditable(action=AuditAction.UPDATE,resourceType="design-quote",resourceId="#id")
    public Summary accept(UUID id,long version){
        var q=locked(id,version);var offer=offers.get(id,q.getRevision());
        q.state().accept(actor(),offer,clock.instant());
        customers.findById(q.getCustomerId()).filter(c->"ACTIVE".equals(c.status())).orElseThrow(()->new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
        q.accept(actor(),clock.instant());return saved(q);
    }
    @Auditable(action=AuditAction.UPDATE,resourceType="design-quote",resourceId="#id")
    public Summary requestChanges(UUID id,long version,String reason){var q=locked(id,version);
        q.transition(q.state().requestChanges(actor(),reason),reason);return saved(q);}
    @Auditable(action=AuditAction.UPDATE,resourceType="design-quote",resourceId="#id")
    public Summary cancel(UUID id,long version,String reason){var q=locked(id,version);q.transition(q.state().cancel(reason),reason);return saved(q);}

    /** Order use case already establishes customer ownership. Locks persist through order/stock commit. */
    @Transactional(propagation=Propagation.MANDATORY)
    public Map<UUID,Money> checkout(UUID customerId,List<PlaceOrderCommand.Line> lines){
        var designLines=lines.stream().filter(l->l.designSnapshotId()!=null).toList();
        var ids=designLines.stream().map(PlaceOrderCommand.Line::quoteId).toList();
        if(ids.contains(null) || new HashSet<>(ids).size()!=ids.size())throw new BusinessException(ErrorCode.DESIGN_QUOTE_REQUIRED);
        Map<UUID,Money> result=new HashMap<>();
        for(UUID id:ids.stream().sorted(Comparator.comparing(UUID::toString)).toList()){
            var q=quotes.findById(id).orElseThrow(()->new BusinessException(ErrorCode.DESIGN_QUOTE_REQUIRED));
            entities.refresh(q,LockModeType.PESSIMISTIC_WRITE);
            var line=designLines.stream().filter(l->id.equals(l.quoteId())).findFirst().orElseThrow();
            var offer=offers.get(id,q.getRevision());
            if(!q.getCustomerId().equals(customerId)||!q.getDesignSnapshotId().equals(line.designSnapshotId())
                    ||!q.getSku().equals(line.sku().code())||offer.quantity()!=line.quantity()||q.getStatus()!=DesignQuoteStatus.ACCEPTED)
                throw new BusinessException(ErrorCode.DESIGN_QUOTE_REQUIRED);
            offer.requireValid(clock.instant());
            if(!offer.unitPrice().equals(line.unitPrice()))throw new BusinessException(ErrorCode.CHECKOUT_PRICE_CHANGED);
            result.put(id,offer.unitPrice());
        }
        return Map.copyOf(result);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void consume(Set<UUID> ids,UUID orderId){
        for(UUID id:ids){var q=quotes.findById(id).orElseThrow();q.state().require(DesignQuoteStatus.ACCEPTED);q.consume(orderId);}
        quotes.flush();
    }
    private static boolean ownScope(){return DataScopeContext.current().map(s->s.effective()==DataScope.OWN).orElse(false);}
    private DesignQuoteJpaEntity require(UUID id){var q=quotes.findByIdInScope(id).orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND));
        if(ownScope()&&!q.isCurrentRevisionIssued())throw new BusinessException(ErrorCode.NOT_FOUND);return q;}
    private DesignQuoteJpaEntity locked(UUID id,long version){var q=require(id);entities.refresh(q,LockModeType.PESSIMISTIC_WRITE);
        if(ownScope()&&!q.isCurrentRevisionIssued())throw new BusinessException(ErrorCode.NOT_FOUND);
        if(q.getVersion()!=version)throw new BusinessException(ErrorCode.DESIGN_QUOTE_CONFLICT);return q;}
    private Summary saved(DesignQuoteJpaEntity q){quotes.flush();return summary(q);}
    private Summary summary(DesignQuoteJpaEntity q){var offer=offers.get(q.getId(),q.getRevision());
        String status=q.getStatus().name();
        if((q.getStatus()==DesignQuoteStatus.SENT||q.getStatus()==DesignQuoteStatus.ACCEPTED)&&!clock.instant().isBefore(offer.validUntil()))status="EXPIRED";
        return new Summary(q.getId(),q.getCustomerId(),q.getDesignSnapshotId(),q.getSku(),status,q.getVersion(),offer,q.getAcceptedBy(),q.getAcceptedAt(),q.getConsumedOrderId(),q.getResponseNote());}
    private static UUID actor(){return DataScopeContext.current().orElseThrow(()->new BusinessException(ErrorCode.FORBIDDEN)).user().userId();}
}
