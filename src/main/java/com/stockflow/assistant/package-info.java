/**
 * <b>The back-office AI assistant</b>
 *
 * <p>ADR-0010 · design {@code docs/assistant-design.md} · database schema {@code assistant}</p>
 *
 * <p>One agent that answers staff questions in Vietnamese by calling tools, each a thin wrapper over a read method of
 * another module's {@code api}. Every tool carries the permission of the screen that shows the same data, so the
 * assistant never shows anyone what that screen would not. It reads; it never writes.</p>
 *
 * <p><b>May depend on:</b> inventory, order, procurement, reporting, fulfillment, catalog and production, through
 * their {@code api}. Plus
 * {@code common} and {@code contracts}, which are available to every module. Nothing depends on it: it is a leaf, so
 * switching it off, or its model provider failing, touches no other flow.</p>
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
        "catalog :: api", "production :: api"})
package com.stockflow.assistant;
