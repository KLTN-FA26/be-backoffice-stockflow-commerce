package com.stockflow.common.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The scope arithmetic, which decides which rows a user sees. Inverting the comparison in
 * {@code effective()} would turn every admin endpoint into a data leak with no visible symptom,
 * so every combination is asserted rather than a representative few.
 */
class DataScopeTest {

    @AfterEach
    void clearContext() {
        DataScopeContext.clear();
    }

    private static CurrentUser user(DataScope scope, String... warehouses) {
        return new CurrentUser(UUID.randomUUID(), "tester", Set.of(), Set.of(), scope,
                Set.of(warehouses));
    }

    @Test
    @DisplayName("OWN is narrower than TEAM, than WAREHOUSE, than ALL")
    void ordering() {
        assertThat(DataScope.ALL.isBroaderThan(DataScope.WAREHOUSE)).isTrue();
        assertThat(DataScope.WAREHOUSE.isBroaderThan(DataScope.TEAM)).isTrue();
        assertThat(DataScope.TEAM.isBroaderThan(DataScope.OWN)).isTrue();
        assertThat(DataScope.OWN.isBroaderThan(DataScope.OWN)).isFalse();
    }

    @Test
    @DisplayName("no combination of endpoint and grant ever widens what the user sees")
    void effectiveScopeIsAlwaysTheNarrowerOfTheTwo() {
        for (DataScope endpoint : DataScope.values()) {
            for (DataScope granted : DataScope.values()) {
                DataScope effective =
                        new DataScopeContext.Scope(endpoint, user(granted)).effective();

                assertThat(effective.isBroaderThan(endpoint))
                        .as("endpoint %s, grant %s widened past the endpoint", endpoint, granted)
                        .isFalse();
                assertThat(effective.isBroaderThan(granted))
                        .as("endpoint %s, grant %s widened past the user's grant", endpoint, granted)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("an endpoint declaring ALL does not grant ALL to a warehouse-scoped user")
    void endpointScopeDoesNotEscalate() {
        DataScopeContext.Scope scope =
                new DataScopeContext.Scope(DataScope.ALL, user(DataScope.WAREHOUSE, "HCM"));

        assertThat(scope.effective()).isEqualTo(DataScope.WAREHOUSE);
    }

    @Test
    @DisplayName("the scope does not survive into another thread")
    void doesNotLeakToAsyncThreads() throws Exception {
        DataScopeContext.set(DataScope.OWN, user(DataScope.OWN));

        boolean[] seenInOtherThread = {true};
        Thread other = new Thread(() -> seenInOtherThread[0] = DataScopeContext.current().isPresent());
        other.start();
        other.join();

        // Correct, not a limitation: a background job acts as the system, not as whoever happened
        // to trigger it.
        assertThat(seenInOtherThread[0]).isFalse();
    }

    @Test
    @DisplayName("callUnscoped restores the scope even when the action throws")
    void callUnscopedIsExceptionSafe() {
        CurrentUser caller = user(DataScope.WAREHOUSE, "HCM");
        DataScopeContext.set(DataScope.WAREHOUSE, caller);

        assertThatThrownBy(() -> DataScopeContext.callUnscoped(() -> {
            assertThat(DataScopeContext.current()).isEmpty();
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(DataScopeContext.current()).get()
                .extracting(DataScopeContext.Scope::user)
                .isEqualTo(caller);
    }

    @Test
    @DisplayName("outside a request the system sees everything; inside, only the effective scope")
    void systemOrCurrent() {
        assertThat(DataScopeContext.systemOrCurrent()).isEqualTo(DataScope.ALL);

        DataScopeContext.set(DataScope.ALL, user(DataScope.OWN));
        assertThat(DataScopeContext.systemOrCurrent()).isEqualTo(DataScope.OWN);
    }

    @Test
    @DisplayName("a scope with a missing half is rejected rather than defaulting to permissive")
    void rejectsNulls() {
        assertThatThrownBy(() -> new DataScopeContext.Scope(null, user(DataScope.OWN)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DataScopeContext.Scope(DataScope.OWN, null))
                .isInstanceOf(NullPointerException.class);
    }
}
