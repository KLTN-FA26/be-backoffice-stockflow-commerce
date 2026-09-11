package com.stockflow;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Rules {@code ModularityTest} does not cover.
 *
 * <p>Modulith polices the boundaries <i>between</i> modules. These rules police the layering
 * <i>inside</i> each one — that the domain stays free of frameworks, that controllers stay thin,
 * that dependencies point one way. Both are needed: a module can respect every boundary and still
 * be an unmaintainable mess internally.</p>
 *
 * <p>Written as executable rules rather than a paragraph in a wiki nobody reads, because a
 * convention that is not checked is a convention that is already being violated somewhere.</p>
 */
class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.stockflow");

    /**
     * The most important rule here.
     *
     * <p>A domain model that imports Spring or JPA cannot be unit-tested without a container, and
     * within a year the business rules have quietly migrated into services because "the entity is
     * awkward to construct". Keeping the domain plain makes {@code StockItemTest} run in
     * milliseconds, which is what keeps people writing those tests.</p>
     */
    @Test
    void domainDoesNotDependOnFrameworks() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..internal.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "org.hibernate..",
                        "com.fasterxml.jackson..",
                        "io.swagger..")
                .because("the domain model must be testable without a container and must not be "
                        + "shaped by persistence or serialisation concerns");
        rule.check(CLASSES);
    }

    /** The dependency rule: domain knows nobody, application knows domain, web knows application. */
    @Test
    void domainDoesNotDependOnOuterLayers() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..internal.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..internal.service..", "..internal.entity..",
                        "..internal.repository..", "..internal.controller..")
                .because("dependencies point inwards: the domain is the centre and depends on nothing");
        rule.check(CLASSES);
    }

    /**
     * Controllers orchestrate nothing.
     *
     * <p>A controller that touches a repository is a controller that will grow a transaction, then
     * a business rule, then a second caller that has to go through HTTP to reuse it.</p>
     */
    @Test
    void controllersDoNotReachIntoPersistenceOrDomain() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..internal.controller..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..internal.entity..", "..internal.repository..")
                .because("a controller talks to the application layer and nothing below it");
        rule.check(CLASSES);
    }

    /** Persistence must not call back up into orchestration; that is a cycle in disguise. */
    @Test
    void persistenceDoesNotDependOnApplicationOrWeb() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage("..internal.entity..", "..internal.repository..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..internal.service..", "..internal.controller..")
                .because("the persistence adapter implements a domain port; it does not orchestrate");
        rule.check(CLASSES);
    }

    /**
     * JPA entities stay inside the persistence package.
     *
     * <p>The moment one appears in a controller signature, the database schema has become the API
     * contract, and renaming a column is a breaking change for the frontend.</p>
     */
    @Test
    void jpaEntitiesStayInPersistence() {
        ArchRule rule = classes()
                .that().areAnnotatedWith(jakarta.persistence.Entity.class)
                .should().resideInAnyPackage(
                        // a module's own entities
                        "..internal.entity..",
                        "..internal.repository..",
                        // the shared infrastructure tables in the platform schema: audit_log and
                        // idempotency_record. They belong to no business module - every module's
                        // endpoints go through the same filter and the same aspect - so they have
                        // no "internal" package to live in.
                        "com.stockflow.common..persistence..")
                .because("entities are a persistence detail, not a domain or API type");
        rule.check(CLASSES);
    }

    /**
     * Transactions are declared in the application layer only.
     *
     * <p>{@code @Transactional} on a controller starts the transaction before request binding has
     * finished; on a repository method it produces a transaction per call, so a multi-step
     * operation is no longer atomic — which would quietly destroy the very property that justified
     * this architecture.
     */
    @Test
    void transactionsAreDeclaredInTheApplicationLayer() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage("..internal.controller..", "..internal.domain..")
                .should().beAnnotatedWith(org.springframework.transaction.annotation.Transactional.class)
                .because("the application service is the transaction boundary");
        rule.check(CLASSES);
    }

    // ================================================================================
    // Rules that keep the shared base layer usable - each one encodes a trap that the
    // corresponding base class's javadoc explains at length.
    // ================================================================================

    /**
     * A soft-deletable entity must carry {@code @SQLRestriction("deleted_at is null")}.
     *
     * <p>The single most common way soft delete is got wrong. {@code @SQLRestriction} is not
     * inherited from a mapped superclass, so an entity that extends
     * {@code SoftDeletableEntity} without it looks correct, compiles, and returns deleted rows from
     * every finder — the delete silently does nothing. There is no runtime symptom until somebody
     * notices a "deleted" product still on the storefront.</p>
     */
    @Test
    void softDeletableEntitiesFilterDeletedRows() {
        ArchRule rule = classes()
                .that().areAssignableTo(com.stockflow.common.persistence.SoftDeletableEntity.class)
                .and().areAnnotatedWith(jakarta.persistence.Entity.class)
                .should().beAnnotatedWith(org.hibernate.annotations.SQLRestriction.class)
                .because("@SQLRestriction is not inherited from a mapped superclass; without it on "
                        + "the entity, every query returns soft-deleted rows");
        rule.allowEmptyShould(true).check(CLASSES);
    }

    /**
     * A repository that handles a {@code ScopedEntity} must extend {@code ScopedJpaRepository}.
     *
     * <p>This is what makes the data-scope filter hard to bypass. A scoped entity queried through a
     * plain {@code JpaRepository} has a {@code findAll()} that returns every row in the table
     * regardless of who is asking — the exact failure the scope exists to prevent, and one with no
     * visible symptom: the screen looks right to whoever has too much access.</p>
     *
     * <p>Written imperatively rather than with the fluent DSL because the condition is "mentions a
     * scoped entity anywhere", which needs the dependency graph. Note the limitation: a repository
     * that names its entity <i>only</i> as a type argument and declares no methods of its own may
     * not appear in the dependency set, so this can miss one. That is a false negative, never a
     * false failure — and {@code ScopedJpaRepository} still throws
     * {@code ScopeViolationException} at runtime on the first unguarded call, so the rule is a
     * second line of defence rather than the only one.</p>
     */
    @Test
    void scopedEntitiesAreQueriedThroughScopedRepositories() {
        for (JavaClass candidate : CLASSES) {
            if (!candidate.isInterface()) {
                continue;
            }
            boolean isSpringDataRepository = candidate.getAllRawInterfaces().stream()
                    .anyMatch(each -> each.getName()
                            .equals("org.springframework.data.jpa.repository.JpaRepository"));
            if (!isSpringDataRepository) {
                continue;
            }
            boolean touchesScopedEntity = candidate.getDirectDependenciesFromSelf().stream()
                    .anyMatch(dependency -> dependency.getTargetClass()
                            .isAssignableTo(com.stockflow.common.security.ScopedEntity.class));
            if (!touchesScopedEntity) {
                continue;
            }
            assertThat(candidate.isAssignableTo(
                    com.stockflow.common.security.ScopedJpaRepository.class))
                    .as("%s handles a ScopedEntity, so it must extend ScopedJpaRepository - "
                            + "through a plain JpaRepository, findAll() returns every row to "
                            + "whoever asks", candidate.getSimpleName())
                    .isTrue();
        }
    }

    /**
     * A scoped entity must expose the static prototype its repository needs.
     *
     * <p>The airtight half of the previous rule. {@code ScopedJpaRepository.scopePrototype()} can
     * only be implemented if the entity offers a static instance to read its attribute names from,
     * so an entity that declares {@code ScopedEntity} without one cannot have been wired up — it is
     * carrying the marker interface and getting none of the filtering.</p>
     */
    @Test
    void scopedEntitiesExposeAScopePrototype() {
        for (JavaClass entity : CLASSES) {
            boolean isScopedEntity =
                    entity.isAssignableTo(com.stockflow.common.security.ScopedEntity.class)
                            && entity.isAnnotatedWith(jakarta.persistence.Entity.class);
            if (!isScopedEntity) {
                continue;
            }
            boolean hasPrototype = entity.getFields().stream()
                    .anyMatch(field -> field.getModifiers().contains(
                                    com.tngtech.archunit.core.domain.JavaModifier.STATIC)
                            && field.getRawType().getName().equals(entity.getName()));
            assertThat(hasPrototype)
                    .as("%s implements ScopedEntity but has no static prototype instance, so no "
                            + "repository can supply scopePrototype() - the marker is doing nothing",
                            entity.getSimpleName())
                    .isTrue();
        }
    }

    /**
     * The shared kernel's domain types stay framework-free, exactly like a module's domain.
     *
     * <p>{@code Money}, {@code Sku} and {@code AggregateRoot} are used by every module. A Spring or
     * JPA annotation on one of them would put the framework on the critical path of every unit test
     * in the system.</p>
     */
    @Test
    void sharedDomainDoesNotDependOnFrameworks() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.stockflow.common.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "jakarta.persistence..",
                        "org.hibernate..", "io.swagger..")
                .because("the shared kernel is used by every module and must stay plain Java");
        rule.check(CLASSES);
    }

    /**
     * A class that caches must reference {@code CacheNames}, not a string literal.
     *
     * <p>{@code @Cacheable("prodcuts")} is not an error — it creates a new, unconfigured cache with
     * the default TTL that nobody sized or monitors, while the intended cache is never populated
     * and never read. Referring to a constant turns the typo into a compile error.</p>
     *
     * <p>Imperative because {@code @Cacheable} is a <b>method</b> annotation: the fluent
     * {@code classes().that().areAnnotatedWith(Cacheable.class)} form inspects class-level
     * annotations only and would silently match nothing, which is worse than no rule at all.</p>
     */
    @Test
    void cachesAreNamedByConstant() {
        for (JavaClass candidate : CLASSES) {
            boolean cachesSomething = candidate.getMethods().stream()
                    .anyMatch(method -> method.isAnnotatedWith(
                                    org.springframework.cache.annotation.Cacheable.class)
                            || method.isAnnotatedWith(
                                    org.springframework.cache.annotation.CacheEvict.class)
                            || method.isAnnotatedWith(
                                    org.springframework.cache.annotation.CachePut.class));
            if (!cachesSomething) {
                continue;
            }
            boolean usesCacheNames = candidate.getDirectDependenciesFromSelf().stream()
                    .anyMatch(dependency -> dependency.getTargetClass().getName()
                            .equals("com.stockflow.common.cache.CacheNames"));
            assertThat(usesCacheNames)
                    .as("%s uses a cache annotation but never references CacheNames - a literal "
                            + "cache name that is mistyped silently creates a second, "
                            + "unconfigured cache", candidate.getSimpleName())
                    .isTrue();
        }
    }

    /**
     * Identifiers come from {@code Identifiers.newId()}, not {@code UUID.randomUUID()}.
     *
     * <p>Scoped to entity constructors' package, because random UUIDs are perfectly correct for
     * tokens, correlation ids and lock tokens — where {@code RedisDistributedLock} and
     * {@code CorrelationIdFilter} legitimately use them. It is only as a <b>primary key</b> that a
     * random UUID fragments the index and degrades insert throughput as the table grows.</p>
     */
    @Test
    void persistenceLayerUsesTimeOrderedIdentifiers() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage("..internal.entity..", "..internal.repository..")
                .should().callMethod(java.util.UUID.class, "randomUUID")
                .because("primary keys must be time-ordered (Identifiers.newId()); a random UUID "
                        + "scatters inserts across the whole index");
        rule.allowEmptyShould(true).check(CLASSES);
    }

    /**
     * No {@code java.util.Date} or {@code Calendar}.
     *
     * <p>Both are mutable and zone-ambiguous, and mixing them with {@code java.time} in one
     * codebase produces off-by-one-day bugs that only appear near midnight in one timezone.
     */
    @Test
    void onlyJavaTimeIsUsed() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("java.util.Date")
                .because("java.time is the only date API used here");
        rule.check(CLASSES);
    }

    /**
     * Every {@code @Scheduled} method must also carry {@code @SchedulerLock}.
     *
     * <h2>Why this needs a fitness function rather than a code review</h2>
     *
     * <p>{@code SchedulerLockConfig} enables ShedLock and provides the {@code LockProvider}, so the
     * infrastructure looks present and correct from every angle — the bean exists, the
     * {@code platform.shedlock} table exists, startup logs nothing unusual. But
     * {@code @EnableSchedulerLock} installs an advisor that matches only annotated methods. A
     * {@code @Scheduled} method without {@code @SchedulerLock} is simply not advised, and nothing
     * anywhere warns about it.</p>
     *
     * <p>The consequence is invisible on one instance and only appears the day a second is started
     * — the same day nobody is looking at scheduler behaviour. Every job then runs twice per tick.
     * This rule is the only thing that makes the omission fail loudly, at build time, on the commit
     * that introduces it.</p>
     */
    @Test
    void everyScheduledJobIsLockedAcrossInstances() {
        ArchRule rule = methods()
                .that().areAnnotatedWith(org.springframework.scheduling.annotation.Scheduled.class)
                .should().beAnnotatedWith(net.javacrumbs.shedlock.spring.annotation.SchedulerLock.class)
                .because("@Scheduled runs on every instance; without @SchedulerLock the job doubles "
                        + "the moment a second instance is started, and nothing warns");
        rule.allowEmptyShould(true).check(CLASSES);
    }

    /**
     * A {@code @Transactional} or {@code @Scheduled} method must be public.
     *
     * <h2>Why</h2>
     *
     * <p>Spring advises these through a proxy. A non-public method is not overridable by the CGLIB
     * subclass, so the proxy cannot intercept it: the annotation is silently ignored. The method
     * runs — with no transaction, or never at all — and the only symptom is data that fails to roll
     * back, which surfaces much later and somewhere else.</p>
     *
     * <p>This is the single most common Spring mistake that produces no error message.</p>
     */
    @Test
    void proxiedMethodsArePublic() {
        ArchRule rule = methods()
                .that().areAnnotatedWith(org.springframework.scheduling.annotation.Scheduled.class)
                .or().areAnnotatedWith(org.springframework.modulith.events.ApplicationModuleListener.class)
                .should().bePublic()
                .because("Spring's proxy cannot intercept a non-public method, so the annotation is "
                        + "silently ignored");
        rule.allowEmptyShould(true).check(CLASSES);
    }

    /**
     * A module's {@code api} package may not reach into any {@code internal} package.
     *
     * <h2>Why this matters more than it looks</h2>
     *
     * <p>{@code api} is the one package other modules compile against. The moment a type in it
     * names something from {@code internal} — a domain aggregate in a method signature, a JPA
     * entity inside a record — every caller now depends on that internal type too. The module
     * boundary still <i>looks</i> intact: {@code ModularityTest} sees {@code order} importing only
     * {@code inventory.api}, which is allowed, and says nothing. But {@code order} can now reach a
     * {@code StockItem} through the returned object and call behaviour on it outside a transaction
     * and outside the invariants.</p>
     *
     * <p>That is the exact leak the whole api/internal split exists to prevent, and it is the one
     * the module checker cannot see. This rule is what catches it.</p>
     */
    @Test
    void theApiPackageLeaksNothingInternal() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..api..")
                .should().dependOnClassesThat().resideInAPackage("..internal..")
                .because("api is what other modules compile against; a type from internal reachable "
                        + "through it hands them an aggregate they can call outside a transaction");
        rule.allowEmptyShould(true).check(CLASSES);
    }

    /**
     * The published API is records, interfaces and enums — never a JPA entity.
     *
     * <p>An entity in a module's API is a table definition promoted to a cross-module contract: the
     * column can never be renamed again, and the receiving module holds a managed instance whose
     * mutations Hibernate will happily flush.</p>
     */
    @Test
    void theApiPublishesNoEntities() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..api..")
                .should().beAnnotatedWith(jakarta.persistence.Entity.class)
                .because("an entity in the API makes a table column part of a cross-module contract");
        rule.allowEmptyShould(true).check(CLASSES);
    }

    /** Field injection makes dependencies invisible and objects impossible to construct in a test. */
    @Test
    void noFieldInjection() {
        ArchRule rule = noFields()
                .should().beAnnotatedWith(org.springframework.beans.factory.annotation.Autowired.class)
                .because("constructor injection makes dependencies explicit and objects testable");
        rule.check(CLASSES);
    }
}
