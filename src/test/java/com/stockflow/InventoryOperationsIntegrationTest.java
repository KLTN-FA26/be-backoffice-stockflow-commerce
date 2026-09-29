package com.stockflow;

import com.stockflow.support.*;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.*;
import com.stockflow.common.security.*;
import com.stockflow.inventory.api.*;
import com.stockflow.inventory.internal.service.CycleCountService;
import com.stockflow.inventory.internal.service.InventoryAlertService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@IntegrationTest
@Import(PostgresContainer.class)
class InventoryOperationsIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired CycleCountService counts;
    @Autowired InventoryAlertService alerts;
    @Autowired InventoryControlService policies;
    @Autowired InventoryService inventory;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.stockflow.identity.api.IdentityService identities;
    @org.junit.jupiter.api.BeforeEach void eligibleCounters(){
        org.mockito.Mockito.when(identities.isActiveUserWithAnyRole(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(String[].class))).thenReturn(true);
    }
    final UUID counter=UUID.randomUUID(),manager=UUID.randomUUID();
    record Stock(UUID id,String sku){}
    Stock stock(int qty){
        UUID id=UUID.randomUUID();String sku="COUNT-"+id.toString().substring(0,12).toUpperCase();
        tx.executeWithoutResult(s -> jdbc.update("""
                insert into inventory.stock_item(id,sku,location_code,on_hand,reserved,status,received_at,created_at)
                values (?,?,'HCM-A01',?,0,'AVAILABLE','2026-09-01T00:00:00Z',now())
                """,id,sku,qty));return new Stock(id,sku);
    }
    <T>T as(UUID user,java.util.function.Supplier<T> action){return scoped(user,DataScope.ALL,Set.of(),action);}
    <T>T scoped(UUID user,DataScope scope,Set<String> warehouses,java.util.function.Supplier<T> action){
        var previous=DataScopeContext.current().orElse(null);
        DataScopeContext.set(DataScope.ALL,new CurrentUser(user,"test",Set.of(),Set.of(),scope,warehouses));
        try{return action.get();}finally{DataScopeContext.restore(previous);}
    }
    CycleCountService.Summary planned(Stock stock){return as(manager,()->counts.create(UUID.randomUUID(),"HCM",counter,List.of(stock.id()),"Scheduled count"));}
    CycleCountService.Summary recorded(Stock stock,int quantity){
        var planned=planned(stock);var started=as(counter,()->counts.start(planned.id(),planned.version()));
        return as(counter,()->counts.record(started.id(),stock.id(),started.version(),quantity,"Physical recount evidence"));
    }
    @Test void fifoCorrectionPreservesReceiptDateRequiresApprovalAndPostsOnce() throws Exception {
        var stock=stock(10);policies.configure(new Sku(stock.sku()),new InventoryPolicy(null,null,RemovalStrategy.FIFO,TrackingMode.NONE,false,null));
        assertThatThrownBy(()->tx.executeWithoutResult(s->jdbc.update("update inventory.stock_item set on_hand=11 where id=?",stock.id())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var recorded=recorded(stock,11);var submitted=as(counter,()->counts.submit(recorded.id(),recorded.version()));
        assertThatThrownBy(()->as(counter,()->counts.approve(submitted.id(),submitted.version())))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(ErrorCode.COUNT_SELF_APPROVAL));
        var approved=as(manager,()->counts.approve(submitted.id(),submitted.version()));
        CycleCountService.Summary posted;
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=workers.submit(()->as(manager,()->counts.post(approved.id(),approved.version())));
            var second=workers.submit(()->as(manager,()->counts.post(approved.id(),approved.version())));
            posted=first.get(30,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(second.get(30,java.util.concurrent.TimeUnit.SECONDS).id()).isEqualTo(posted.id());
        }
        assertThat(posted.status()).isEqualTo("POSTED");
        assertThat(posted.postedAt()).isNotNull();assertThat(posted.approvedAt()).isNotNull();
        as(manager,()->counts.post(approved.id(),approved.version()));
        assertThat(inventory.availableToPromise(new Sku(stock.sku()))).isEqualTo(11);
        assertThat(jdbc.queryForObject("select count(*) from inventory.stock_adjustment where count_id=?",Integer.class,posted.id())).isEqualTo(1);
        UUID postedId=posted.id();
        assertThatThrownBy(()->tx.executeWithoutResult(s->jdbc.update("update inventory.stock_adjustment set reason='Changed history' where count_id=?",postedId)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select received_at from inventory.stock_item where id=?",java.sql.Timestamp.class,stock.id()).toInstant())
                .isEqualTo(java.time.Instant.parse("2026-09-01T00:00:00Z"));
    }
    @Test void zeroVariancePostsWithoutLedgerAndWrongWarehouseIsHidden(){
        var stock=stock(10);var recorded=recorded(stock,10);
        assertThatThrownBy(()->scoped(manager,DataScope.WAREHOUSE,Set.of("HN"),()->counts.get(recorded.id())))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(as(counter,()->counts.submit(recorded.id(),recorded.version())).status()).isEqualTo("POSTED");
        assertThat(jdbc.queryForObject("select count(*) from inventory.stock_adjustment where count_id=?",Integer.class,recorded.id())).isZero();
    }
    @Test void rejectedOrReassignedCountRequiresFreshMeasurements(){
        var stock=stock(10);var recorded=recorded(stock,9);
        var submitted=as(counter,()->counts.submit(recorded.id(),recorded.version()));
        var rejected=as(manager,()->counts.reject(submitted.id(),submitted.version(),"Count again with bin evidence"));
        assertThat(rejected.status()).isEqualTo("COUNTING");assertThat(rejected.lines().getFirst().counted()).isNull();
        var replacement=UUID.randomUUID();
        var reassigned=as(manager,()->counts.reassign(rejected.id(),rejected.version(),replacement,"Counter unavailable"));
        assertThat(reassigned.assignedTo()).isEqualTo(replacement);
        assertThatThrownBy(()->as(counter,()->counts.record(reassigned.id(),stock.id(),reassigned.version(),10,null)))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        var fresh=as(replacement,()->counts.record(reassigned.id(),stock.id(),reassigned.version(),10,null));
        assertThat(as(replacement,()->counts.submit(fresh.id(),fresh.version())).status()).isEqualTo("POSTED");
        assertThatThrownBy(()->as(manager,()->counts.reassign(fresh.id(),fresh.version()+1,counter,"Must not reopen")))
                .isInstanceOf(BusinessException.class);
    }
    @Test void inactiveCounterIsRejectedBeforeCreatingCount(){
        var stock=stock(10);
        org.mockito.Mockito.when(identities.isActiveUserWithAnyRole(org.mockito.ArgumentMatchers.eq(counter),org.mockito.ArgumentMatchers.any(String[].class))).thenReturn(false);
        assertThatThrownBy(()->planned(stock)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
    @Test void operationalPolicyCannotChangeDuringCountButThresholdCan(){
        var stock=stock(0);var recorded=recorded(stock,1);
        assertThatThrownBy(()->policies.configure(new Sku(stock.sku()),new InventoryPolicy(5,null,RemovalStrategy.FEFO,TrackingMode.LOT,false,null)))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(ErrorCode.COUNT_POLICY_CONFLICT));
        policies.configure(new Sku(stock.sku()),new InventoryPolicy(5,null,RemovalStrategy.FEFO,TrackingMode.NONE,false,null));
        var submitted=as(counter,()->counts.submit(recorded.id(),recorded.version()));
        assertThat(as(manager,()->counts.approve(submitted.id(),submitted.version())).status()).isEqualTo("APPROVED");
    }
    @Test void countOfOldZeroLayerCannotApproveAgainstAlreadyChangedPolicy(){
        var stock=stock(0);policies.configure(new Sku(stock.sku()),new InventoryPolicy(null,null,RemovalStrategy.FEFO,TrackingMode.LOT,false,null));
        var measured=recorded(stock,1);
        assertThatThrownBy(()->as(counter,()->counts.submit(measured.id(),measured.version())))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(ErrorCode.COUNT_POLICY_CONFLICT));
    }
    @Test void reverseBasketAndCycleCountUseTheSameGlobalStockLockOrder() throws Exception {
        var a=stock(10);var b=stock(10);
        var planned=as(manager,()->counts.create(UUID.randomUUID(),"HCM",counter,List.of(a.id(),b.id()),"Concurrent basket"));
        // Hold the first row while both use cases begin. The basket lists the larger ID first.
        var ordered=java.util.stream.Stream.of(a,b).sorted(java.util.Comparator.comparing(s->s.id().toString())).toList();
        var ready=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(3)){
            var blocker=workers.submit(()->tx.executeWithoutResult(t->{
                jdbc.query("select id from inventory.stock_item where id=? for update",r->{},ordered.getFirst().id());ready.countDown();
                try{if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
            }));
            assertThat(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var basket=workers.submit(()->tx.executeWithoutResult(t->{
                inventory.prepareReservation(Set.of(new Sku(a.sku()),new Sku(b.sku())));
                for(var s:ordered.reversed())inventory.reserve(new ReserveStockCommand(UUID.randomUUID(),new Sku(s.sku()),1,UUID.randomUUID()));
            }));
            var count=workers.submit(()->as(counter,()->counts.start(planned.id(),planned.version())));
            release.countDown();blocker.get(10,java.util.concurrent.TimeUnit.SECONDS);
            basket.get(10,java.util.concurrent.TimeUnit.SECONDS);assertThat(count.get(10,java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo("COUNTING");
        }
        assertThat(inventory.availableToPromise(new Sku(a.sku()))).isEqualTo(9);
        assertThat(inventory.availableToPromise(new Sku(b.sku()))).isEqualTo(9);
    }
    @Test void stockMovementRequiresRecountAndClearsApproval(){
        var stock=stock(10);var recorded=recorded(stock,9);var submitted=as(counter,()->counts.submit(recorded.id(),recorded.version()));
        var approved=as(manager,()->counts.approve(submitted.id(),submitted.version()));
        inventory.reserve(new ReserveStockCommand(UUID.randomUUID(),new Sku(stock.sku()),1,UUID.randomUUID()));
        assertThatThrownBy(()->as(manager,()->counts.post(approved.id(),approved.version())))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(ErrorCode.COUNT_STOCK_CHANGED));
        var recounted=as(counter,()->counts.recount(approved.id(),approved.version(),"Movement while counting"));
        assertThat(recounted.status()).isEqualTo("COUNTING");assertThat(recounted.approvedBy()).isNull();
        assertThat(recounted.lines().getFirst().counted()).isNull();
    }
    @Test void shortageCannotDiscardOrderHoldsAndActiveCountCannotBeDuplicated(){
        var stock=stock(10);inventory.reserve(new ReserveStockCommand(UUID.randomUUID(),new Sku(stock.sku()),8,UUID.randomUUID()));
        var planned=planned(stock);var started=as(counter,()->counts.start(planned.id(),planned.version()));
        var shortage=as(counter,()->counts.record(started.id(),stock.id(),started.version(),7,"Missing"));
        assertThat(shortage.lines().getFirst().counted()).isEqualTo(7);
        assertThatThrownBy(()->as(counter,()->counts.submit(shortage.id(),shortage.version())))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(ErrorCode.COUNT_RESERVED_CONFLICT));
        assertThatThrownBy(()->planned(stock)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(ErrorCode.COUNT_ALREADY_ACTIVE));
        assertThat(inventory.availableToPromise(new Sku(stock.sku()))).isEqualTo(2);
    }
    @Test void alertEpisodesDeduplicateResolveReopenAndClearWithoutSendingInsideTransaction(){
        var stock=stock(2);policies.configure(new Sku(stock.sku()),new InventoryPolicy(5,3,RemovalStrategy.FEFO,TrackingMode.NONE,false,null));
        alerts.evaluate(stock.sku());alerts.evaluate(stock.sku());
        assertThat(jdbc.queryForObject("select count(*) from inventory.stock_alert where sku=? and status='OPEN'",Integer.class,stock.sku())).isEqualTo(2);
        var id=jdbc.queryForObject("select id from inventory.stock_alert where sku=? and kind='REORDER'",UUID.class,stock.sku());
        as(manager,()->alerts.acknowledge(id));assertThat(as(manager,()->alerts.get(id)).status()).isEqualTo("OPEN");
        tx.executeWithoutResult(s->jdbc.update("update inventory.stock_item set on_hand=10,version=version+1 where id=?",stock.id()));
        alerts.evaluate(stock.sku());assertThat(as(manager,()->alerts.get(id)).status()).isEqualTo("RESOLVED");
        tx.executeWithoutResult(s->jdbc.update("update inventory.stock_item set on_hand=2,version=version+1 where id=?",stock.id()));
        alerts.evaluate(stock.sku());
        assertThat(jdbc.queryForObject("select count(*) from inventory.stock_alert where sku=?",Integer.class,stock.sku())).isEqualTo(4);
        policies.configure(new Sku(stock.sku()),new InventoryPolicy(null,null,RemovalStrategy.FEFO,TrackingMode.NONE,false,null));
        alerts.evaluate(stock.sku());
        assertThat(jdbc.queryForObject("select count(*) from inventory.stock_alert where sku=? and status='OPEN'",Integer.class,stock.sku())).isZero();
        assertThat(as(manager,()->alerts.list(null,0,20)).totalElements()).isGreaterThanOrEqualTo(4);
        assertThatThrownBy(()->scoped(manager,DataScope.WAREHOUSE,Set.of("HCM"),()->alerts.get(id))).isInstanceOf(BusinessException.class);
    }
}
