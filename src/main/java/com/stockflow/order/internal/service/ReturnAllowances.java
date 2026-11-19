package com.stockflow.order.internal.service;

import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.ReturnClaims;
import com.stockflow.order.internal.domain.ReturnPolicy;
import com.stockflow.order.internal.domain.ReturnRequest;
import com.stockflow.order.internal.domain.ReturnRequestRepository;
import com.stockflow.order.internal.domain.SalesContract;
import com.stockflow.order.internal.domain.SalesContractRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What bounds a return of an order, read the one way for the two that ask: opening a return
 * ({@link ReturnRequestService}) and showing the customer what is still returnable on each delivery
 * ({@link CustomerOrdersService}). The rules are the aggregate's ({@link ReturnRequest#open},
 * {@link ReturnRequest#claimable}); this reads what they are applied to - the order's own policy and what its returns
 * already claim - so the portal cannot offer a figure that opening then refuses.
 */
@Component
class ReturnAllowances {

    private final ReturnRequestRepository returns;
    private final SalesContractRepository contracts;
    private final ReturnPolicy policy;
    private final CustomerService customers;

    ReturnAllowances(ReturnRequestRepository returns, SalesContractRepository contracts, ReturnPolicy policy,
                     CustomerService customers) {
        this.returns = returns;
        this.contracts = contracts;
        this.policy = policy;
        this.customers = customers;
    }

    /**
     * The return rules for the order: the complaint period its contract agreed (Điều 318), else the configured one. A
     * contract cancelled and drawn up again is read as the live one.
     */
    ReturnPolicy policyOf(UUID orderId) {
        return policy.withWindowDays(contracts.findLiveByOrderId(orderId).map(SalesContract::returnWindowDays)
                .orElse(null));
    }

    /** What the order's returns claim of its lines, per delivery: one statement for the whole order. */
    ReturnClaims claimsOf(UUID orderId) {
        return returns.claimsOf(orderId);
    }

    /**
     * Each delivery's lines of {@code order} and what of them a portal return may claim at {@code now}. An account that
     * is not ACTIVE may open no return on the portal ({@code RETURN_CUSTOMER_NOT_ACTIVE}, which
     * {@link ReturnRequestService#open} checks for a portal claim): its lines are shown, each with nothing returnable,
     * so the portal does not offer a claim that opening would refuse.
     */
    ReturnRequest.Claimable claimable(Order order, Instant now) {
        UUID orderId = order.id().value();
        ReturnRequest.Claimable claimable = ReturnRequest.claimable(order, claimsOf(orderId), now, policyOf(orderId));
        String status = customers.findById(order.customerId()).map(CustomerSummary::status).orElse(null);
        return "ACTIVE".equals(status) ? claimable : nothingReturnable(claimable);
    }

    static ReturnRequest.Claimable nothingReturnable(ReturnRequest.Claimable claimable) {
        Map<UUID, List<ReturnRequest.ClaimableLine>> byShipment = new java.util.LinkedHashMap<>();
        claimable.byShipment().forEach((shipment, lines) -> byShipment.put(shipment, zeroed(lines)));
        return new ReturnRequest.Claimable(byShipment, zeroed(claimable.wholeOrder()));
    }

    private static List<ReturnRequest.ClaimableLine> zeroed(List<ReturnRequest.ClaimableLine> lines) {
        return lines.stream()
                .map(l -> new ReturnRequest.ClaimableLine(l.orderLineId(), l.sku(), l.quantity(), 0))
                .toList();
    }
}
