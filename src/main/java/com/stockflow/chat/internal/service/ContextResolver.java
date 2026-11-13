package com.stockflow.chat.internal.service;

import com.stockflow.chat.internal.domain.ContextKind;
import com.stockflow.chat.internal.domain.ContextRef;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.design.api.CustomerDesigns;
import com.stockflow.invoicing.api.IssuedEInvoices;
import com.stockflow.order.api.CustomerDocument;
import com.stockflow.order.api.CustomerDocuments;
import com.stockflow.product.api.CustomerSkus;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.ProductStatus;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What a conversation may be about, kltn-docs 16 BR-02: a document is talked about only in its own customer's
 * conversations, and a customer names only what their portal shows them. Each document is read through the
 * {@code api} of the module that owns it; the chat stores the reference and nothing of the document.
 *
 * <p>One answer, {@code CHAT_CONTEXT_NOT_FOUND} (404), for a document that does not exist, one of another customer and
 * one the customer is not shown yet (a draft quote or contract): the chat must not be a way to learn which documents
 * exist. {@code tg_conversation_context_owner} refuses another customer's document again in the database.</p>
 */
@Component
public class ContextResolver {

    /** Drafts are the staff's working paper; the customer meets a quote or a contract once it is sent. */
    private static final Set<String> HIDDEN_FROM_CUSTOMER = Set.of("DRAFT", "PENDING_APPROVAL");

    /**
     * A document found.
     *
     * @param customerId        whose it is; null for a catalogue product, which is anyone's to ask about
     * @param label             its number or name, for the conversation page
     * @param status            its own status, as its module names it
     * @param responsibleUserId the Sales account that answers for it (16 §4.1 step 2: routed to them first); null when
     *                          none is known
     */
    public record Resolved(ContextRef ref, UUID customerId, String label, String status, UUID responsibleUserId) {
    }

    private final ProductService products;
    private final CustomerSkus customerSkus;
    private final CustomerDesigns designs;
    private final CustomerDocuments documents;
    private final IssuedEInvoices einvoices;

    public ContextResolver(ProductService products, CustomerSkus customerSkus, CustomerDesigns designs,
                           CustomerDocuments documents, IssuedEInvoices einvoices) {
        this.products = products;
        this.customerSkus = customerSkus;
        this.designs = designs;
        this.documents = documents;
        this.einvoices = einvoices;
    }

    /**
     * The document, when it is {@code customerId}'s to talk about (a catalogue product always is).
     *
     * @param byCustomer whether the customer names it (only what the portal shows them), rather than Sales
     * @throws BusinessException {@code CHAT_CONTEXT_NOT_FOUND} otherwise
     */
    public Resolved requireOwnedBy(ContextRef ref, UUID customerId, boolean byCustomer) {
        return find(ref)
                .filter(found -> found.customerId() == null || found.customerId().equals(customerId))
                .filter(found -> !byCustomer || visibleToCustomer(found))
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_CONTEXT_NOT_FOUND));
    }

    /** The document whoever it belongs to; empty when it does not exist. */
    public Optional<Resolved> find(ContextRef ref) {
        UUID id = ref.id();
        return switch (ref.kind()) {
            case PRODUCT -> products.findById(id).map(p -> new Resolved(ref, null, p.name(),
                    p.status() == null ? null : p.status().name(), null));
            case CUSTOMER_SKU -> customerSkus.findByVariant(id).map(s -> new Resolved(ref, s.ownerCustomerId(),
                    s.sku(), s.status(), null));
            case DESIGN -> designs.design(id).map(d -> new Resolved(ref, d.customerId(), d.name(), d.status(), null));
            case DESIGN_PROJECT -> designs.project(id).map(d -> new Resolved(ref, d.customerId(), d.name(), d.status(),
                    null));
            case QUOTE -> documents.quote(id).map(d -> resolved(ref, d));
            case ORDER -> documents.order(id).map(d -> resolved(ref, d));
            case CONTRACT -> documents.contract(id).map(d -> resolved(ref, d));
            case PRODUCTION_ORDER -> documents.productionOrder(id).map(d -> resolved(ref, d));
            case EINVOICE -> einvoices.find(id).map(e -> new Resolved(ref, e.customerId(), e.code(), e.status(), null));
        };
    }

    private static boolean visibleToCustomer(Resolved found) {
        if (found.ref().kind() == ContextKind.PRODUCT) {
            return Objects.equals(found.status(), ProductStatus.PUBLISHED.name());
        }
        boolean staffPaper = found.ref().kind() == ContextKind.QUOTE || found.ref().kind() == ContextKind.CONTRACT
                || found.ref().kind() == ContextKind.PRODUCTION_ORDER;
        return !staffPaper || found.status() == null || !HIDDEN_FROM_CUSTOMER.contains(found.status());
    }

    private static Resolved resolved(ContextRef ref, CustomerDocument document) {
        return new Resolved(ref, document.customerId(), document.number(), document.status(),
                document.responsibleUserId());
    }
}
