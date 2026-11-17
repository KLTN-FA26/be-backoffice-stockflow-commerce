/**
 * <b>Sales chat, conversation routing, assisted orders</b>
 *
 * <p>WBS 3.16 · database schema {@code chat}</p>
 *
 * <p><b>May depend on:</b> customer, order, design, identity, payment, product, invoicing, notification - each
 * through its {@code api} only: the customer and their documents (a conversation's contexts, 16 BR-02, and the
 * customer panel beside a conversation), the documents' own operations its cards call (16 BR-03), the staff who answer
 * customers, and the bell that tells an offline customer of a reply and the supervisors of a late one. None of them depends on the chat, so no cycle. Plus
 * {@code common} and {@code contracts}, which are available to every module.</p>
 *
 * <p>The module has two packages and the split is the whole point:</p>
 * <ul>
 *   <li>{@code api} — the public API, exposed as a named interface. This is all any other module
 *       may import, which is why a dependency on this module is written {@code "chat :: api"}.</li>
 *   <li>{@code internal} — everything else, invisible to other modules. The compiler will not stop
 *       you reaching in; {@code ModularityTest} will.</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"customer :: api", "order :: api", "design :: api",
        "identity :: api", "payment :: api", "product :: api", "invoicing :: api", "notification :: api"})
package com.stockflow.chat;
