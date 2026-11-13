package com.stockflow.chat.internal.service;

import com.stockflow.chat.internal.domain.Actor;
import com.stockflow.chat.internal.domain.ConversationRepository;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.design.api.CustomerDesigns;
import com.stockflow.order.api.CustomerDocuments;
import com.stockflow.payment.api.CustomerInstallments;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The customer panel beside a conversation (kltn-docs 16 §4.1 step 3, §7 "18 Customer Information"): the profile and
 * the recent orders, quotes, contracts, production orders, open instalments, designs and conversations - each through
 * the {@code api} of the module that owns it, a handful of statements in all. Staff only.
 */
@Service
@Transactional(readOnly = true)
public class CustomerOverviewService {

    /** How many of each kind of document the panel shows; the full lists are each module's own pages. */
    private static final int RECENT = 10;

    private final ChatCallers callers;
    private final CustomerService customers;
    private final CustomerDocuments documents;
    private final CustomerInstallments installments;
    private final CustomerDesigns designs;
    private final ConversationRepository conversations;
    private final ChatViews views;

    public CustomerOverviewService(ChatCallers callers, CustomerService customers, CustomerDocuments documents,
                                   CustomerInstallments installments, CustomerDesigns designs,
                                   ConversationRepository conversations, ChatViews views) {
        this.callers = callers;
        this.customers = customers;
        this.documents = documents;
        this.installments = installments;
        this.designs = designs;
        this.conversations = conversations;
        this.views = views;
    }

    public ChatCustomerOverview overview(CurrentUser user, UUID customerId) {
        Actor staff = callers.staff(user);
        CustomerSummary customer = customers.findById(customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
        return new ChatCustomerOverview(customer.customerId(), customer.code(), customer.fullName(), customer.email(),
                customer.phone(), customer.status(), documents.recentOrders(customerId, RECENT),
                documents.recentQuotes(customerId, RECENT), documents.recentContracts(customerId, RECENT),
                documents.recentProductionOrders(customerId, RECENT), installments.openOf(customerId, RECENT),
                designs.recentDesigns(customerId, RECENT),
                views.rows(conversations.ofCustomer(customerId, null, 0, 5).items(), user.userId(), staff));
    }
}
