package com.stockflow.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import org.springframework.data.domain.Persistable;

import java.util.Objects;
import java.util.UUID;

/**
 * The base class every JPA entity in this system extends.
 *
 * <p>It supplies the three things every table needs and that are easy to get subtly wrong: an
 * application-assigned time-ordered id, an optimistic-lock version, and an {@code equals}/
 * {@code hashCode} pair that behaves correctly inside collections and across a persistence
 * context. It inherits the four audit columns from {@link AuditableEntity}.</p>
 *
 * <h2>The id is assigned by the application, never by the database</h2>
 *
 * <p>There is no {@code @GeneratedValue}. The constructor takes the id, and callers get one from
 * {@code Identifiers.newId()}. That is a deliberate choice with consequences worth knowing:</p>
 * <ul>
 *   <li><b>The id exists before the insert.</b> An aggregate can reference its own id, publish an
 *       event carrying it, and be put in a {@code HashSet} — all before anything reaches the
 *       database. With a database-generated id, every one of those is a null until flush.</li>
 *   <li><b>{@code equals}/{@code hashCode} on the id are safe.</b> The classic JPA entity-equality
 *       problem — the hash changes when the database assigns the id, so the object is lost inside
 *       the {@code HashSet} it was already added to — cannot happen here, because the id never
 *       changes.</li>
 *   <li><b>Spring Data can no longer tell new from existing by "is the id null".</b> That is the
 *       one thing an application-assigned id takes away, and it is why this class implements
 *       {@link Persistable} — see below.</li>
 * </ul>
 *
 * <h2>Why {@code Persistable} is implemented, and what breaks without it</h2>
 *
 * <p>{@code SimpleJpaRepository.save} branches on {@code isNew()}: true calls {@code persist()},
 * false calls {@code merge()}. Spring Data answers that question through
 * {@code JpaMetamodelEntityInformation.isNew}, which starts with:</p>
 *
 * <pre>
 * if (versionAttribute.isEmpty()
 *         || versionAttribute.map(Attribute::getJavaType).map(Class::isPrimitive).orElse(false)) {
 *     return super.isNew(entity);          // i.e. "is the id null"
 * }
 * </pre>
 *
 * <p>The version here is a primitive {@code long}, so it takes that first branch — and the id is
 * assigned in the constructor and is never null. {@code isNew()} would therefore return
 * {@code false} for <b>every</b> entity, including one that has never been near the database.
 * (A {@code Long} version would not help either: it would then compare against null, and a
 * brand-new entity's version is 0, not null.)</p>
 *
 * <p>Two consequences, neither of which produces an error:</p>
 *
 * <ul>
 *   <li>Every insert issues a {@code SELECT} first, because {@code merge()} has to check whether
 *       the row exists. On a write-heavy path — the audit trail, order lines — that doubles the
 *       round trips for nothing.</li>
 *   <li>{@code merge()} returns a <i>different</i> instance than the one passed in. Code that does
 *       {@code repository.save(entity); entity.setSomething(...);} mutates a detached copy and the
 *       change is silently lost. This is why the repository adapters in this codebase always use
 *       the value {@code save} returns.</li>
 * </ul>
 *
 * <p>{@link #isNew()} answers from a transient flag that JPA's lifecycle callbacks set: false once
 * the row has been inserted ({@code @PostPersist}) or loaded ({@code @PostLoad}), true otherwise.
 * That is the standard remedy for application-assigned identifiers, and it makes {@code save()} do
 * what its name says.</p>
 *
 * <h2>Why {@code version} is a primitive</h2>
 *
 * <p>A primitive cannot be null, so an entity always has a defined version — there is no
 * "unversioned" state for a caller to mishandle, and {@code getVersion()} never needs a null check.
 * With {@code Persistable} in place, the choice no longer affects insert-versus-merge at all.</p>
 *
 * @see com.stockflow.common.id.Identifiers
 */
@MappedSuperclass
public abstract class BaseEntity extends AuditableEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Optimistic lock.
     *
     * <p>Hibernate adds {@code where version = ?} to every update and increments it. Two
     * transactions that read the same row and both write it: the second gets zero rows updated and
     * an {@code OptimisticLockingFailureException} rather than silently overwriting the first.
     * This is the cheap protection that applies everywhere; a pessimistic lock is the expensive one
     * reserved for hot rows like stock.</p>
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /**
     * True until the row exists in the database.
     *
     * <p>{@code @Transient} so JPA never maps it, {@code transient} so Java serialisation never
     * carries it — a deserialised entity that claimed to be new would be re-inserted.</p>
     *
     * <p>Note the default: {@code true}. Hibernate instantiates an entity it is about to load using
     * the no-argument constructor, so this field is briefly true for a loaded row too — until
     * {@link #markPersisted()} runs on {@code @PostLoad}, which happens before the entity is
     * handed to any application code.</p>
     */
    @Transient
    private transient boolean persisted = false;

    /** Required by JPA. Application code uses the id-taking constructor. */
    protected BaseEntity() {
    }

    protected BaseEntity(UUID id) {
        this.id = Objects.requireNonNull(id, "id must not be null - use Identifiers.newId()");
    }

    @Override
    public UUID getId() {
        return id;
    }

    /** {@inheritDoc} See the class javadoc for why this cannot be derived from the id or version. */
    @Override
    public boolean isNew() {
        return !persisted;
    }

    /**
     * Both callbacks mean the same thing: this object now corresponds to a row.
     *
     * <p>{@code @PostPersist} fires after the {@code INSERT} is executed, not when {@code persist()}
     * is called. That is the correct moment — until the insert has run, a rollback would leave the
     * object new again.</p>
     */
    @PostPersist
    @PostLoad
    void markPersisted() {
        this.persisted = true;
    }

    public long getVersion() {
        return version;
    }

    /**
     * Equality by identifier, tolerant of Hibernate proxies.
     *
     * <p>{@code getClass() != other.getClass()} is the obvious implementation and it is wrong under
     * JPA: a lazily-loaded association hands you a generated subclass, so an entity never equals
     * its own proxy. {@code Hibernate.getClass} unwraps that. The consequence of getting this wrong
     * is a row appearing twice in a {@code Set}, which surfaces much later as a duplicate line on
     * an invoice.</p>
     *
     * <h2>Why {@code getId()} and not the {@code id} field</h2>
     *
     * <p>{@code equals} and {@code hashCode} are {@code final}, so the generated proxy subclass
     * cannot override them: when {@code this} or {@code other} is an uninitialised proxy, the
     * inherited {@code id} <b>field</b> on that instance was never assigned and reads as
     * {@code null}. Reading the field would make {@code equals} return false and
     * {@code hashCode} fall back to the class hash — for two references to the same row. Unwrapping
     * the class with {@code Hibernate.getClass} and then reading the field is the version that
     * looks correct and is not.</p>
     *
     * <p>{@code getId()} is not final, so the proxy does intercept it and returns the real
     * identifier. There is no extra cost: {@code Hibernate.getClass} already initialises the proxy
     * on the line above.</p>
     */
    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null) {
            return false;
        }
        Class<?> thisType = org.hibernate.Hibernate.getClass(this);
        Class<?> otherType = org.hibernate.Hibernate.getClass(other);
        if (!thisType.equals(otherType)) {
            return false;
        }
        BaseEntity that = (BaseEntity) other;
        return getId() != null && getId().equals(that.getId());
    }

    /**
     * Hash of the identifier, which never changes for the life of the object.
     *
     * <p>Uses the class's own hash when the id is somehow null, so a half-constructed entity does
     * not throw inside a {@code HashMap}. Reads through {@code getId()} for the proxy reason given
     * on {@link #equals(Object)}.</p>
     */
    @Override
    public final int hashCode() {
        UUID identifier = getId();
        return identifier == null
                ? org.hibernate.Hibernate.getClass(this).hashCode()
                : identifier.hashCode();
    }

    @Override
    public String toString() {
        // getId()/getVersion(), not the fields: on a proxy the fields read null/0 and the log
        // line would name a row that looks unsaved.
        return "%s(id=%s, version=%d)"
                .formatted(org.hibernate.Hibernate.getClass(this).getSimpleName(),
                        getId(), getVersion());
    }
}
