package com.stockflow.design.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A customer's designs and design projects, read for a module that refers to them without owning them - the chat
 * (kltn-docs 16 §1: a conversation can be about a design or a design project of the customer). Read-only, one statement
 * per call; any status is answered, archived included: whether it may be talked about is the caller's question.
 */
public interface CustomerDesigns {

    Optional<CustomerDesign> design(UUID designId);

    Optional<CustomerDesign> project(UUID projectId);

    /** The customer's designs, the most recently changed first, at most {@code limit}. */
    List<CustomerDesign> recentDesigns(UUID customerId, int limit);
}
