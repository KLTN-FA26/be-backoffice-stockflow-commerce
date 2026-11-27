/**
 * <b>The public API of the {@code assistant} module</b> — empty on purpose.
 *
 * <p>The assistant is a leaf: it calls other modules' {@code api}, and no module calls it. The package exists, with
 * its {@code @NamedInterface}, so the module has the same shape as every other one and a first caller would not have
 * to decide where its contract goes.</p>
 *
 * <p>What would belong here, if anything ever does: a service interface plus exactly the types in its method
 * signatures. Records and interfaces only — never a JPA entity, never a domain aggregate.</p>
 */
@org.springframework.modulith.NamedInterface("api")
package com.stockflow.assistant.api;
