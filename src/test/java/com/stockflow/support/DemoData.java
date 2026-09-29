package com.stockflow.support;

import java.util.UUID;

/**
 * Rows of the demo seed ({@code db/demo}) that integration tests rely on.
 *
 * <p>Since V20260928004000 an order's customer id is a foreign key to {@code customer.customer}, so
 * a test that places an order needs a customer that exists; a random UUID is refused at insert.
 * The demo seed V20260928009000 creates this one for exactly that purpose.</p>
 */
public final class DemoData {

    /** The demo customer, created by V20260928009000__demo_master_data.sql. */
    public static final UUID CUSTOMER_ID = UUID.fromString("c0000000-0000-4000-8000-000000000001");

    private DemoData() {
    }
}
