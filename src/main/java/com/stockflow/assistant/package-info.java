/**
 * <b>The back-office AI assistant</b>
 *
 * <p>ADR-0010 · design {@code docs/assistant-design.md} · database schema {@code assistant}</p>
 *
 * <p>One agent that answers staff questions in Vietnamese by calling tools, each a thin wrapper over a read method of
 * another module's {@code api}. Every tool carries the permission of the screen that shows the same data, so the
 * assistant never shows anyone what that screen would not. It reads; it never writes. It also reads a photographed
 * delivery note into a draft goods receipt for the goods-receipt screen (design §8.1), under that screen's own
 * permission ({@code procurement-goods-receipts:CREATE}); that too is a proposal, never a write.</p>
 *
 * <p><b>May depend on:</b> inventory, order, procurement, reporting, fulfillment, catalog, production, customer
 * (to find a customer by name for its prices and its terms), payment (what a customer owes) and warehouse (a task's
 * warehouse code), through their {@code api}. Plus
 * {@code common} and {@code contracts}, which are available to every module. Nothing depends on it: it is a leaf, so
 * switching it off, or its model provider failing, touches no other flow, and no dependency it takes can close a
 * cycle.</p>
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
        "catalog :: api", "production :: api", "customer :: api", "payment :: api", "warehouse :: api"})
package com.stockflow.assistant;
