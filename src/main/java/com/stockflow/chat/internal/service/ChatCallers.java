package com.stockflow.chat.internal.service;

import com.stockflow.chat.internal.domain.Actor;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.customer.api.CustomerService;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Who is calling, in the chat's terms.
 *
 * <p>A customer's portal account and Sales hold the same {@code chat-conversations} permission, and the CUSTOMER role's
 * data scope is ALL: what keeps a customer to their own conversations is that the customer endpoints resolve the
 * customer from the sign-in (never from the request) and the staff endpoints refuse a portal account
 * ({@link ScopeGuards#requireBackOffice}) before anything else.</p>
 */
@Component
public class ChatCallers {

    /** The permission resources of the chat (V20261124000300). */
    public static final String CONVERSATIONS = "chat-conversations";
    public static final String SUPERVISION = "chat-supervision";
    public static final String CANNED_RESPONSES = "chat-canned-responses";

    /** A customer's portal account and the customer it speaks for. */
    public record CustomerCaller(UUID customerId, Actor actor) {
    }

    private final CustomerService customers;

    public ChatCallers(CustomerService customers) {
        this.customers = customers;
    }

    /**
     * The signed-in customer.
     *
     * @throws BusinessException {@code CUSTOMER_NOT_FOUND} for an account with no customer profile
     */
    public CustomerCaller customer(CurrentUser user) {
        UUID customerId = customers.findByUserId(user.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND)).customerId();
        return new CustomerCaller(customerId, Actor.customer(user.userId()));
    }

    /**
     * The signed-in staff member; a supervisor when they hold {@code chat-supervision:UPDATE} (assign, take
     * escalations, 16 §2) - a permission, never a role name, so it follows the role matrix as it is edited.
     *
     * @throws BusinessException {@code FORBIDDEN} for a portal account
     */
    public Actor staff(CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return Actor.agent(user.userId(), user.can(SUPERVISION, Action.UPDATE));
    }
}
