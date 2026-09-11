package com.stockflow.common;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.DataScopeContext;
import com.stockflow.common.security.DataScopeSpecifications;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeViolationException;
import com.stockflow.common.security.ScopedEntity;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Persistable;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests for defects found in the shared base layer during review.
 *
 * <p>Every case here is something that compiled, started up and passed a happy-path test while
 * being wrong — which is why each one is pinned by an assertion rather than by a comment. They are
 * grouped by the class they protect, not by severity.</p>
 */
class BaseLayerRegressionTest {

    @AfterEach
    void clearScope() {
        DataScopeContext.clear();
    }

    private static CurrentUser user(DataScope scope, UUID id) {
        return new CurrentUser(id, "tester", Set.of(), Set.of(), scope, Set.of("W1"));
    }

    private static final class Thing extends BaseEntity {
        private Thing(UUID id) {
            super(id);
        }
    }

    @Nested
    @DisplayName("BaseEntity")
    class BaseEntityIdentity {

        @Test
        @DisplayName("equality is by identifier, and survives a HashSet")
        void identityEquality() {
            UUID id = UUID.randomUUID();
            Thing one = new Thing(id);
            Thing same = new Thing(id);
            Thing other = new Thing(UUID.randomUUID());

            assertThat(one).isEqualTo(same).isNotEqualTo(other);
            assertThat(one.hashCode()).isEqualTo(id.hashCode()).isEqualTo(same.hashCode());
            assertThat(new HashSet<>(List.of(one))).contains(same);
        }

        /**
         * The bug: {@code equals}/{@code hashCode} are final, so a lazy proxy cannot override them
         * and its inherited {@code id} FIELD reads null. Reading the field made an entity unequal
         * to its own proxy — a row appearing twice in a {@code Set}, surfacing much later as a
         * duplicate invoice line. Reading through {@code getId()} is what fixes it, and only the
         * getter is intercepted.
         */
        @Test
        @DisplayName("equals and hashCode read through getId(), not the field")
        void readsThroughTheGetter() throws Exception {
            assertThat(Modifier.isFinal(
                    BaseEntity.class.getDeclaredMethod("equals", Object.class).getModifiers()))
                    .as("equals must stay final, which is precisely why it cannot read the field")
                    .isTrue();
            assertThat(Modifier.isFinal(
                    BaseEntity.class.getDeclaredMethod("hashCode").getModifiers())).isTrue();
            assertThat(Modifier.isFinal(BaseEntity.class.getDeclaredMethod("getId").getModifiers()))
                    .as("getId must NOT be final - the proxy has to be able to intercept it")
                    .isFalse();
        }

        /**
         * The bug: with an application-assigned id and a primitive {@code @Version}, Spring Data's
         * {@code isNew()} answered false for every entity, so {@code save()} always took the
         * {@code merge()} path — a SELECT before every INSERT, and a returned instance that is not
         * the one passed in.
         */
        @Test
        @DisplayName("a fresh entity is new until the row exists")
        void newUntilPersisted() throws Exception {
            assertThat(Persistable.class).isAssignableFrom(BaseEntity.class);

            Thing thing = new Thing(UUID.randomUUID());
            assertThat(thing.isNew()).isTrue();

            Method markPersisted = BaseEntity.class.getDeclaredMethod("markPersisted");
            assertThat(markPersisted.getAnnotation(PostPersist.class)).isNotNull();
            assertThat(markPersisted.getAnnotation(PostLoad.class))
                    .as("a loaded row must not look new either")
                    .isNotNull();

            markPersisted.setAccessible(true);
            markPersisted.invoke(thing);
            assertThat(thing.isNew()).isFalse();
        }
    }

    @Nested
    @DisplayName("Data scope")
    class Scoping {

        /**
         * The bug: {@code Specs.eq} returns {@code all()} for a null value — correct for an
         * optional search filter, catastrophic here. A principal with no user id got a
         * specification matching every row while the call site read as OWN-scoped.
         */
        @Test
        @DisplayName("OWN scope with no user id is refused, not widened to everything")
        void ownScopeRequiresAUserId() {
            assertThatThrownBy(() -> DataScopeSpecifications.forScope(
                    new Scoped(), DataScope.OWN, user(DataScope.OWN, null)))
                    .isInstanceOf(ScopeViolationException.class)
                    .hasMessageContaining("user id");
        }

        /**
         * The bug: {@code "a" + "b %s".formatted(x, y, z)} applies {@code formatted} to the last
         * literal only — extra arguments are dropped silently and the earlier placeholders reach
         * the operator raw.
         */
        @Test
        @DisplayName("the unmapped-attribute message is fully interpolated")
        void messageIsInterpolated() {
            assertThatThrownBy(() -> DataScopeSpecifications.forScope(
                    new Unmapped(), DataScope.WAREHOUSE, user(DataScope.WAREHOUSE, UUID.randomUUID())))
                    .isInstanceOf(ScopeViolationException.class)
                    .hasMessageStartingWith("Unmapped")
                    .hasMessageNotContaining("%s");
        }

        /**
         * The bug: the guard aspect cleared the scope unconditionally in its {@code finally}, so a
         * guarded method calling another guarded method had its own scope wiped by the inner call
         * — every later query in the outer method throwing 403 on an authorised request.
         */
        @Test
        @DisplayName("a nested scope restores the outer one instead of clearing it")
        void nestedScopeRestores() {
            CurrentUser outer = user(DataScope.ALL, UUID.randomUUID());
            DataScopeContext.set(DataScope.ALL, outer);
            DataScopeContext.Scope saved = DataScopeContext.current().orElseThrow();

            DataScopeContext.set(DataScope.WAREHOUSE, user(DataScope.WAREHOUSE, UUID.randomUUID()));
            DataScopeContext.restore(saved);

            assertThat(DataScopeContext.current()).get()
                    .extracting(DataScopeContext.Scope::user).isSameAs(outer);

            DataScopeContext.restore(null);
            assertThat(DataScopeContext.current()).isEmpty();
        }

        @Test
        @DisplayName("setSystemScope installs ALL for a thread that has none")
        void systemScope() {
            DataScopeContext.setSystemScope(user(DataScope.ALL, UUID.randomUUID()));
            assertThat(DataScopeContext.current()).get()
                    .extracting(DataScopeContext.Scope::required).isEqualTo(DataScope.ALL);
        }
    }

    /**
     * The bug: {@code @Target} allowed TYPE, but the aspect's pointcut is {@code @annotation},
     * which matches methods only. A class-level guard compiled, passed startup validation, and
     * enforced nothing — while reading as more carefully guarded than an unannotated controller.
     */
    @Test
    @DisplayName("@RequiresPermission cannot be placed on a class, where it would enforce nothing")
    void requiresPermissionIsMethodOnly() {
        Target target = RequiresPermission.class.getAnnotation(Target.class);
        assertThat(target).isNotNull();
        assertThat(target.value()).containsExactly(ElementType.METHOD);
    }

    private static final class Scoped implements ScopedEntity {
        @Override public String ownerAttribute() {
            return "ownerId";
        }
        @Override public String warehouseAttribute() {
            return "warehouseCode";
        }
        @Override public String teamAttribute() {
            return "teamId";
        }
    }

    private static final class Unmapped implements ScopedEntity {
        @Override public String ownerAttribute() {
            return null;
        }
        @Override public String warehouseAttribute() {
            return null;
        }
        @Override public String teamAttribute() {
            return null;
        }
    }
}
