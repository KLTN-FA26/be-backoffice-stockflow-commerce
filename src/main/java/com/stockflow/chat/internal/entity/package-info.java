/**
 * JPA entities: the table mapping for this module's aggregates.
 *
 * <p>Separate from {@code repository} on purpose. These classes exist to describe rows — columns,
 * constraints, associations, the optimistic-lock version — and nothing else. The domain model in
 * {@code internal.domain} is the one that holds behaviour, and it does not know these types exist;
 * {@code internal.repository} owns the translation between the two.</p>
 *
 * <h2>Why these types are public, and what stops them leaking</h2>
 *
 * <p>They used to sit in the same package as the repositories and could therefore be
 * package-private, which is the strongest possible guarantee that an entity never reaches a
 * controller: it would not compile. Splitting entity from repository — the layer names the team
 * wanted — costs that guarantee, because {@code internal.repository} now has to see them.</p>
 *
 * <p>The protection moves rather than disappearing. {@code ArchitectureTest} forbids
 * {@code internal.controller} from depending on {@code internal.entity} or
 * {@code internal.repository} at all, so an entity reaching a JSON response fails the build with a
 * message naming both classes. And {@code internal} is still invisible to other modules, enforced
 * by {@code ModularityTest}. What changed is which tool reports the mistake, not whether it is
 * caught — and it is the same trade the whole modular monolith makes: boundaries enforced by
 * fitness functions rather than by {@code javac}.</p>
 *
 * <p>The practical rule is unchanged: <b>never return a JPA entity from a controller.</b> Map it to
 * a response DTO. An entity that escapes is how a column name becomes part of your public API and
 * can never be renamed again.</p>
 */
package com.stockflow.chat.internal.entity;
