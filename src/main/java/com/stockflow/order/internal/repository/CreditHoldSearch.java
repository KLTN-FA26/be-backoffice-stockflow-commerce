package com.stockflow.order.internal.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/** The queue of orders over their credit limit, oldest hold first (SCRUM-427). */
public interface CreditHoldSearch {

    Page<UUID> heldOrders(Pageable pageable);
}
