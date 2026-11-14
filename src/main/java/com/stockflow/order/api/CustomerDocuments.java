package com.stockflow.order.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A customer's quotes, orders, sales contracts and production orders, read for a module that refers to them without
 * owning them - the chat (kltn-docs 16): a conversation is about one of them only when it is the conversation's
 * customer's (BR-02), and the agent sees the customer's recent ones beside the conversation (16 §4.1 step 3).
 *
 * <p>Read-only and one statement per call. Each lookup answers for any status: whether a document may be talked about
 * is the chat's question, not this one's; a draft quote is still the customer's.</p>
 */
public interface CustomerDocuments {

    Optional<CustomerDocument> quote(UUID quoteId);

    Optional<CustomerDocument> order(UUID orderId);

    Optional<CustomerDocument> contract(UUID contractId);

    Optional<CustomerDocument> productionOrder(UUID productionOrderId);

    /** The customer's quotes, the newest first, at most {@code limit}. */
    List<CustomerDocument> recentQuotes(UUID customerId, int limit);

    /** The customer's orders, the newest first, at most {@code limit}. */
    List<CustomerDocument> recentOrders(UUID customerId, int limit);

    /** The customer's sales contracts, the newest first, at most {@code limit}. */
    List<CustomerDocument> recentContracts(UUID customerId, int limit);

    /** The customer's production orders, the newest first, at most {@code limit}. */
    List<CustomerDocument> recentProductionOrders(UUID customerId, int limit);
}
