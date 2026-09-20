package com.stockflow.customer.internal.repository;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.JpaAuditingConfig;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.customer.internal.domain.AddressType;
import com.stockflow.customer.internal.domain.Customer;
import com.stockflow.customer.internal.domain.CustomerAddress;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The default-address flag must reach the database. It once did not: the adapter cleared every
 * default with a bulk UPDATE while the loaded rows still said "default", so those rows looked
 * unchanged, were never written back, and the customer ended up with no default address at all
 * (checkout then had nowhere to deliver). The in-memory answer was right, which is why each test
 * flushes and clears the persistence context before it looks.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, CustomerRepositoryAdapter.class})
@Testcontainers(disabledWithoutDocker = true)
class CustomerAddressPersistenceIntegrationTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired EntityManager em;
    @Autowired CustomerRepositoryAdapter adapter;
    @MockitoBean CurrentUserProvider users;

    private static CustomerAddress address(AddressType type, String line1) {
        return new CustomerAddress(Identifiers.newId(), type, "Nguyen Van A", "0901234567", line1, null,
                "26734", "Ben Nghe", "79", "Ho Chi Minh", "VN", null, false, 0L);
    }

    private Customer reload(UUID id) {
        em.flush();
        em.clear();
        return adapter.findById(id).orElseThrow();
    }

    private static List<String> defaults(Customer customer, AddressType type) {
        return customer.addresses().stream()
                .filter(a -> a.type() == type && a.defaultAddress()).map(CustomerAddress::line1).toList();
    }

    @Test
    void theFirstAddressOfEachTypeIsStoredAsTheDefault() {
        var id = Identifiers.newId();
        var customer = Customer.register(id, Identifiers.newId(), "An", "an@example.com", null);
        customer.addAddress(address(AddressType.SHIPPING, "one"));
        customer.addAddress(address(AddressType.SHIPPING, "two"));
        customer.addAddress(address(AddressType.BILLING, "bill"));
        adapter.save(customer);

        var loaded = reload(id);

        assertThat(defaults(loaded, AddressType.SHIPPING)).containsExactly("one");
        assertThat(defaults(loaded, AddressType.BILLING)).containsExactly("bill");
    }

    @Test
    void movingTheDefaultIsStoredAndLeavesTheOtherTypeAlone() {
        var id = Identifiers.newId();
        var customer = Customer.register(id, Identifiers.newId(), "An", "an2@example.com", null);
        customer.addAddress(address(AddressType.SHIPPING, "one"));
        var two = customer.addAddress(address(AddressType.SHIPPING, "two"));
        customer.addAddress(address(AddressType.BILLING, "bill"));
        adapter.save(customer);

        var loaded = reload(id);
        loaded.setDefaultAddress(two.id());
        adapter.save(loaded);
        var afterMove = reload(id);

        assertThat(defaults(afterMove, AddressType.SHIPPING)).containsExactly("two");
        assertThat(defaults(afterMove, AddressType.BILLING)).containsExactly("bill");

        afterMove.deleteAddress(two.id());
        adapter.save(afterMove);
        var afterDelete = reload(id);

        assertThat(afterDelete.addresses()).extracting(CustomerAddress::id).doesNotContain(two.id());
        assertThat(defaults(afterDelete, AddressType.SHIPPING)).as("the remaining one is promoted").containsExactly("one");
    }

    @Test
    void savingAnUnrelatedChangeKeepsEveryDefault() {
        var id = Identifiers.newId();
        var customer = Customer.register(id, Identifiers.newId(), "An", "an3@example.com", null);
        customer.addAddress(address(AddressType.SHIPPING, "one"));
        customer.addAddress(address(AddressType.BILLING, "bill"));
        adapter.save(customer);

        var loaded = reload(id);
        loaded.updateProfile("An Nguyen", null, loaded.version());
        adapter.save(loaded);
        var again = reload(id);

        assertThat(defaults(again, AddressType.SHIPPING)).containsExactly("one");
        assertThat(defaults(again, AddressType.BILLING)).containsExactly("bill");
        assertThat(again.fullName()).isEqualTo("An Nguyen");
    }
}
