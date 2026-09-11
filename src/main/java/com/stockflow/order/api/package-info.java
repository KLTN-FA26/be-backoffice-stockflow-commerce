/**
 * <b>The public API of the {@code order} module.</b> Everything other modules are allowed to
 * touch lives here, and nothing else does.
 *
 * <h2>Why this package needs an annotation to work</h2>
 *
 * <p>Spring Modulith exposes a module's <b>base package</b> by default and treats every nested
 * package as internal. This package is nested, so without {@code @NamedInterface} it would be
 * private and no other module could import a single type from it — the module would have no API at
 * all. The annotation names it, which is what lets another module write
 * {@code allowedDependencies = "order :: api"}.</p>
 *
 * <p>Forgetting it is caught, not silent: the first module that imports from here fails
 * {@code ModularityTest} with a boundary violation. But the message points at the <i>caller</i>,
 * not at the missing annotation, so it is worth knowing that this line is load-bearing.</p>
 *
 * <h2>What belongs here</h2>
 *
 * <p>The service interface, plus exactly the types that appear in its method signatures and the
 * types those drag in. Nothing more. Records and interfaces only — never a JPA entity, never a
 * domain aggregate. Handing another module an aggregate lets it call behaviour on that aggregate
 * outside a transaction and outside the invariants.</p>
 *
 * <p>Everything here is a contract with the rest of the system. Adding a method is safe; renaming a
 * field or removing a method breaks every module that calls in, and {@code javac} is what will tell
 * you, not {@code ModularityTest}.</p>
 */
@org.springframework.modulith.NamedInterface("api")
package com.stockflow.order.api;
