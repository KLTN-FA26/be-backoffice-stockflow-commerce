/**
 * <b>Templated email, SMS and in-app notifications</b>
 *
 * <p>WBS 3.19.2 · database schema {@code notification}</p>
 *
 * <p><b>May depend on:</b> {@code identity :: api} only, as an address book: a staff notice that names a team
 * ("Sales is notified", "alert purchasing") is sent to the active accounts holding that role, and only identity knows
 * them. {@code warehouse :: api}, which named the warehouse of a stock alert, went with the stock alerts (team plan
 * 5-1). The module makes no business decision through another module and no other module calls it for one;
 * identity does not depend on this module. Plus {@code common} and {@code contracts}, which are available to every module.</p>
 *
 * <p><b>Customers' addresses</b> (SCRUM-352) come the other way round: {@code api.CustomerDirectory} is declared here
 * and implemented by the customer module, because customer already depends on this module (the portal invitation)
 * and a dependency back would be a cycle. The order events name the customer by id; the address is read when the
 * e-mail is sent.</p>
 *
 * <p><b>E-mail</b> leaves through one {@code EmailTransport} - Resend's API on the servers, SMTP into Mailpit on a
 * laptop, an in-memory recorder in the tests ({@code stockflow.notification.email.provider}).</p>
 *
 * <p>The module has two packages and the split is the whole point:</p>
 * <ul>
 *   <li>{@code api} — the public API, exposed as a named interface. This is all any other module
 *       may import, which is why a dependency on this module is written {@code "notification :: api"}.</li>
 *   <li>{@code internal} — everything else, invisible to other modules. The compiler will not stop
 *       you reaching in; {@code ModularityTest} will.</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"identity :: api"})
package com.stockflow.notification;
