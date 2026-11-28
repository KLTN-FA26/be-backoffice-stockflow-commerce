package com.stockflow.customer.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Who is responsible for a customer on a day (sales-owner plan §2.2), as {@link ResponsibleSales} answers it.
 *
 * @param ownerId      the stored owner: the active owner for OWNER and COVER; for MANAGERS the owner who left or whose
 *                     account is not active, null when the customer never had one
 * @param actingUserId who acts: the owner (OWNER) or the stand-in (COVER); null for MANAGERS
 * @param managerIds   for MANAGERS, the active Sales Managers, else the active System Admins (may be empty only if
 *                     nobody of either is active); empty otherwise
 */
public record Responsible(UUID customerId, ResponsibleKind kind, UUID ownerId, UUID actingUserId,
                          List<UUID> managerIds) {

    public Responsible {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(kind, "kind");
        managerIds = managerIds == null ? List.of() : List.copyOf(managerIds);
        if ((kind == ResponsibleKind.MANAGERS) != (actingUserId == null)) {
            throw new IllegalArgumentException("Only MANAGERS has no acting user");
        }
    }

    /** Whom to tell: the acting user, or the managers when there is none. Never every salesperson (D6). */
    public List<UUID> recipients() {
        return actingUserId != null ? List.of(actingUserId) : managerIds;
    }

    /** Whether a notice must say the customer has no active owner ("cần phân công"). */
    public boolean needsAssignment() {
        return kind == ResponsibleKind.MANAGERS;
    }
}
