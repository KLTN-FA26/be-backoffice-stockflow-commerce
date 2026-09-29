package com.stockflow;

import com.stockflow.support.*;
import com.stockflow.common.domain.*;
import com.stockflow.common.error.*;
import com.stockflow.common.security.*;
import com.stockflow.customer.api.*;
import com.stockflow.design.api.*;
import com.stockflow.order.api.*;
import com.stockflow.order.internal.service.DesignQuoteService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@IntegrationTest
@Import(PostgresContainer.class)
class DesignQuoteCheckoutIntegrationTest {
    @Autowired DesignQuoteService quotes;
    @Autowired OrderService orders;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @MockitoBean CustomerService customers;
    @MockitoBean DesignService designs;
    final UUID customer=UUID.randomUUID(),owner=UUID.randomUUID(),seller=UUID.randomUUID(),snapshot=UUID.randomUUID(),stock=UUID.randomUUID();
    final String sku="QUOTE-"+UUID.randomUUID().toString().substring(0,12).toUpperCase();
    final Instant until=Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    @BeforeEach void fixture(){
        when(customers.findById(customer)).thenReturn(Optional.of(new CustomerSummary(customer,owner,"Buyer","buyer@example.test","0901234567",null,"ACTIVE",0,Instant.now())));
        var address=new CheckoutCustomer.CheckoutAddress("Buyer","0901234567","12 Nguyen Hue",null,"26734","Ben Nghe","79","Ho Chi Minh City","VN",null);
        when(customers.resolveCheckout(eq(customer),any(),any())).thenReturn(new CheckoutCustomer(customer,"Buyer","buyer@example.test","0901234567",address,address));
        when(designs.verifySnapshotForSku(snapshot,customer,sku)).thenReturn(new ConfirmedDesign(snapshot,UUID.randomUUID(),"a".repeat(64)));
        tx.executeWithoutResult(t->jdbc.update("insert into inventory.stock_item(id,sku,location_code,on_hand,reserved,status,created_at) values (?,?,'HCM-A',10,0,'AVAILABLE',now())",stock,sku));
    }
    <T>T as(UUID actor,DataScope scope,java.util.function.Supplier<T> action){
        var previous=DataScopeContext.current().orElse(null);DataScopeContext.set(DataScope.ALL,new CurrentUser(actor,"test",Set.of(),Set.of(),scope,Set.of()));
        try{return action.get();}finally{DataScopeContext.restore(previous);}
    }
    DesignQuoteService.Summary draft(){return as(seller,DataScope.ALL,()->quotes.create(UUID.randomUUID(),customer,snapshot,sku,2,BigDecimal.valueOf(3_000_000),until,"Final VND price per unit; no additional charge in this order"));}
    DesignQuoteService.Summary accepted(){var d=draft();var sent=as(seller,DataScope.ALL,()->quotes.issue(d.id(),d.version()));
        return as(owner,DataScope.OWN,()->quotes.accept(sent.id(),sent.version()));}
    PlaceOrderCommand command(UUID request,UUID quote,int quantity,long price){return new PlaceOrderCommand(request,customer,null,null,true,
            List.of(new PlaceOrderCommand.Line(new Sku(sku),quantity,Money.vnd(price),snapshot,quote)));}
    void error(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,ErrorCode code){assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(code));}
    @Test void negotiationPreservesRevisionsAndOnlyOwnerCanAccept(){
        var d=draft();var sent=as(seller,DataScope.ALL,()->quotes.issue(d.id(),d.version()));
        error(()->as(seller,DataScope.ALL,()->quotes.accept(sent.id(),sent.version())),ErrorCode.FORBIDDEN);
        var change=as(owner,DataScope.OWN,()->quotes.requestChanges(sent.id(),sent.version(),"Can the total be 5.6 million?"));
        var revised=as(seller,DataScope.ALL,()->quotes.revise(change.id(),change.version(),2,BigDecimal.valueOf(2_800_000),until,"Final all-in unit price"));
        assertThat(revised.offer().revision()).isEqualTo(2);
        var issued=as(seller,DataScope.ALL,()->quotes.issue(revised.id(),revised.version()));
        error(()->as(owner,DataScope.OWN,()->quotes.accept(issued.id(),sent.version())),ErrorCode.DESIGN_QUOTE_CONFLICT);
        var accepted=as(owner,DataScope.OWN,()->quotes.accept(issued.id(),issued.version()));
        assertThat(as(owner,DataScope.OWN,()->quotes.history(d.id(),0,20)).items()).extracting(o->o.unitPrice().amount().longValue()).containsExactly(2_800_000L,3_000_000L);
        error(()->as(seller,DataScope.ALL,()->quotes.revise(d.id(),accepted.version(),1,BigDecimal.ONE,until,"Changed")),ErrorCode.DESIGN_QUOTE_CONFLICT);
        error(()->as(UUID.randomUUID(),DataScope.OWN,()->quotes.get(d.id())),ErrorCode.NOT_FOUND);
        assertThatThrownBy(()->tx.executeWithoutResult(t->jdbc.update("update ordering.design_quote_offer set unit_price=1 where quote_id=?",d.id())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void unpublishedRevisionsStayPrivateAndRequestPrecisionReplaysSafely(){
        UUID request=UUID.randomUUID();Instant precise=until.plusNanos(123456);
        var first=as(seller,DataScope.ALL,()->quotes.create(request,customer,snapshot,sku,2,BigDecimal.valueOf(3_000_000),precise,"Final price"));
        assertThat(as(seller,DataScope.ALL,()->quotes.create(request,customer,snapshot,sku,2,BigDecimal.valueOf(3_000_000),precise,"Final price")).id()).isEqualTo(first.id());
        error(()->as(owner,DataScope.OWN,()->quotes.get(first.id())),ErrorCode.NOT_FOUND);
        assertThat(as(owner,DataScope.OWN,()->quotes.list(0,20)).items()).isEmpty();
        var second=as(seller,DataScope.ALL,()->quotes.revise(first.id(),first.version(),2,BigDecimal.valueOf(2_900_000),until,"Final price"));
        var sent=as(seller,DataScope.ALL,()->quotes.issue(second.id(),second.version()));
        assertThat(as(owner,DataScope.OWN,()->quotes.history(first.id(),0,20)).items()).extracting(o->o.revision()).containsExactly(2);
        assertThat(as(seller,DataScope.ALL,()->quotes.history(first.id(),0,20)).items()).hasSize(2);
        var draft=as(seller,DataScope.ALL,()->quotes.revise(sent.id(),sent.version(),2,BigDecimal.valueOf(2_800_000),until,"Still negotiating"));
        error(()->as(owner,DataScope.OWN,()->quotes.accept(draft.id(),draft.version())),ErrorCode.NOT_FOUND);
        as(seller,DataScope.ALL,()->quotes.cancel(draft.id(),draft.version(),"Withdraw unpublished revision"));
        error(()->as(owner,DataScope.OWN,()->quotes.get(draft.id())),ErrorCode.NOT_FOUND);
        assertThat(as(owner,DataScope.OWN,()->quotes.list(0,20)).items()).isEmpty();
    }
    @Test void checkoutRejectsTamperedPriceMissingQuoteAndChangedReplay(){
        var quote=accepted();UUID request=UUID.randomUUID();
        error(()->orders.placeOrder(command(request,quote.id(),2,1)),ErrorCode.CHECKOUT_PRICE_CHANGED);
        error(()->orders.placeOrder(command(request,null,2,3_000_000)),ErrorCode.DESIGN_QUOTE_REQUIRED);
        error(()->orders.placeOrder(command(request,quote.id(),1,3_000_000)),ErrorCode.DESIGN_QUOTE_REQUIRED);
        var original=command(request,quote.id(),2,3_000_000);var order=orders.placeOrder(original);
        assertThat(order.total().amount().longValue()).isEqualTo(6_000_000);
        assertThat(as(owner,DataScope.OWN,()->quotes.get(quote.id())).consumedOrderId()).isEqualTo(order.orderId());
        assertThat(orders.placeOrder(original).orderId()).isEqualTo(order.orderId());
        error(()->orders.placeOrder(command(request,quote.id(),1,3_000_000)),ErrorCode.IDEMPOTENCY_KEY_REUSED);
        var changedAddress=new PlaceOrderCommand(request,customer,UUID.randomUUID(),null,true,original.lines());
        error(()->orders.placeOrder(changedAddress),ErrorCode.IDEMPOTENCY_KEY_REUSED);
        error(()->orders.placeOrder(command(UUID.randomUUID(),quote.id(),2,3_000_000)),ErrorCode.DESIGN_QUOTE_REQUIRED);
    }
    @Test void stockFailureRollsBackQuoteConsumptionAndReplayEvidence(){
        var quote=accepted();var request=command(UUID.randomUUID(),quote.id(),2,3_000_000);
        tx.executeWithoutResult(t->jdbc.update("update inventory.stock_item set on_hand=0 where id=?",stock));
        assertThatThrownBy(()->orders.placeOrder(request)).isInstanceOf(BusinessException.class);
        assertThat(as(owner,DataScope.OWN,()->quotes.get(quote.id())).status()).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("select count(*) from ordering.checkout_request where request_id=?",Integer.class,request.requestId())).isZero();
        tx.executeWithoutResult(t->jdbc.update("update inventory.stock_item set on_hand=10 where id=?",stock));
        assertThat(orders.placeOrder(request).total().amount().longValue()).isEqualTo(6_000_000);
    }
    @Test void competingOrdersCannotConsumeOneAcceptedQuoteTwice() throws Exception {
        var quote=accepted();
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var calls=new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for(int i=0;i<2;i++)calls.add(workers.submit(()->{
                try{orders.placeOrder(command(UUID.randomUUID(),quote.id(),2,3_000_000));return true;}
                catch(BusinessException e){assertThat(e.errorCode()).isEqualTo(ErrorCode.DESIGN_QUOTE_REQUIRED);return false;}
            }));
            int successes=0;for(var result:calls)if(result.get(20,java.util.concurrent.TimeUnit.SECONDS))successes++;
            assertThat(successes).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("select reserved from inventory.stock_item where id=?",Integer.class,stock)).isEqualTo(2);
    }
}
