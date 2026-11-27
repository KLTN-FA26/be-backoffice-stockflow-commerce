/**
 * <b>The back-office AI assistant</b>
 *
 * <p>ADR-0011 · design {@code docs/assistant-design.md} · database schema {@code assistant}</p>
 *
 * <p>One agent that answers staff questions in Vietnamese by calling tools, each a thin wrapper over a read method of
 * another module's {@code api}. Every tool carries the permission of the screen that shows the same data, so the
 * assistant never shows anyone what that screen would not. It reads; it never writes.</p>
 *
 * <p><b>May depend on:</b> inventory, order (orders, production orders and an order's stock coverage), procurement,
 * reporting, fulfillment, catalog, customer (to find a customer by name for its prices, orders and invoices), payment
 * (an order's instalments), invoicing (the e-invoice list) and warehouse (a task's warehouse code), through their
 * {@code api}; chat (a staff conversation's recent messages and documents, for the reply draft and the summary) and
 * product (the variant a customer's SKU was drawn on, whose standard price the quote price review shows, and the customers' SKUs drawn on a catalogue variant, whose stock is the variant's goods). Plus
 * {@code common} and {@code contracts}, which are available to every module. Nothing depends on it:
 * it is a leaf, so switching it off, or its model provider failing, touches no other flow, and no dependency it takes
 * can close a cycle.</p>
 *
 * <p>The module has two packages and the split is the whole point:</p>
 * <ul>
 *   <li>{@code api} — the public API, exposed as a named interface. Empty: no module calls the assistant.</li>
 *   <li>{@code internal} — everything else, invisible to other modules. The compiler will not stop
 *       you reaching in; {@code ModularityTest} will.</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {
        "inventory :: api", "order :: api", "procurement :: api", "reporting :: api", "fulfillment :: api",
        "catalog :: api", "customer :: api", "payment :: api", "invoicing :: api", "warehouse :: api",
        "identity :: api", "notification :: api", "chat :: api", "product :: api"})
package com.stockflow.assistant;
