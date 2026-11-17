/**
 * <b>Installments of signed contracts, payment receipts, credit position, refundable amounts and refund records</b>
 *
 * <p>kltn-docs 15. Since 4B-10 no money ledger: no customer transfers, allocations, receivables of credit orders, carrier
 * remittances or refund lifecycle - the accountant records receipts per installment, and the system works out what is to
 * be refunded. The full books of money are the accounting software's (15 BR-06).</p>
 *
 * <p>WBS 3.15 · database schema {@code payment}</p>
 *
 * <p><b>May depend on:</b> none — this module calls no other business module. Plus {@code common} and {@code contracts}, which are
 * available to every module.</p>
 *
 * <p>The module has two packages and the split is the whole point:</p>
 * <ul>
 *   <li>{@code api} — the public API, exposed as a named interface. This is all any other module
 *       may import, which is why a dependency on this module is written {@code "payment :: api"}.</li>
 *   <li>{@code internal} — everything else, invisible to other modules. The compiler will not stop
 *       you reaching in; {@code ModularityTest} will.</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"customer :: api"})
package com.stockflow.payment;
