/**
 * <b>VAT e-invoices: drafts drawn up on delivery, issued through an e-invoice provider, kept for ten years</b>
 *
 * <p>WF-05 E2 (plan §1.3-§1.5), kltn-docs 21 · NĐ 254/2026/NĐ-CP, TT 91/2026/TT-BTC · database schema
 * {@code invoicing}</p>
 *
 * <p><b>May depend on:</b> {@code order :: api}, only to read what a delivered shipment is invoiced for
 * ({@code DeliveredShipments}); {@code customer :: api}, only to copy the buyer from its legal profile. It changes
 * nothing in either: an invoice does not decide payments or an order's status (21 BR-11). It hears of a delivery through
 * {@code OrderDelivered} from {@code contracts}, and of a refunded return through {@code ReturnResolved}; it asks
 * {@code order :: api} only whose order a return is ({@code ReturnRequestLookup}). Plus {@code common} and {@code contracts}, which are available to every
 * module.</p>
 *
 * <p>An issued invoice is never cancelled or changed. What was wrong is corrected the ways TT 91/2026/TT-BTC Điều 10
 * gives (WF-05 E4): a notice and no invoice for a wrong buyer name or address ({@code EInvoiceErrorNotices}); an
 * adjustment or a replacement naming the invoice for a wrong tax code, quantity, price, rate or amount, with the
 * written agreement of the two parties ({@code EInvoiceCorrections}); a decrease adjustment for goods returned, drawn
 * up as a draft when the order module announces the return's refund ({@code ReturnResolved}).</p>
 *
 * <p>Nothing depends on this module yet. When the order's completion asks whether every delivered shipment has an
 * issued invoice (plan §4), the question is declared in {@code order :: api} and answered here, as payment's
 * {@code InstallmentOrders} is answered by order: order must not depend on a module that depends on it.</p>
 *
 * <p>The provider is a port ({@code EInvoiceProvider}). {@code SandboxProvider} is the default at capstone scale and
 * issues nothing of legal value; a real provider (Viettel SInvoice, package E3) is another implementation of the same
 * port, chosen by {@code invoicing.einvoice_provider_config}, with no change to the invoice's own rules.</p>
 *
 * <p>The module has two packages and the split is the whole point:</p>
 * <ul>
 *   <li>{@code api} — the public API, exposed as a named interface. Empty for now: no module calls in.</li>
 *   <li>{@code internal} — everything else, invisible to other modules. The compiler will not stop
 *       you reaching in; {@code ModularityTest} will.</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"order :: api", "customer :: api"})
package com.stockflow.invoicing;
