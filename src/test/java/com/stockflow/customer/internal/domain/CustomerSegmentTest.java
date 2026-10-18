package com.stockflow.customer.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A customer group's invariants (P7; ck_segment_code / ck_segment_name) and a customer's move between groups. */
class CustomerSegmentTest {

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    @Test
    void aCodeIsCanonicalUpperCaseAndFixedANameIsRequired() {
        CustomerSegment segment = CustomerSegment.create(UUID.randomUUID(), "  dai-ly_1 ", " Đại lý cấp 1 ", "  ");
        assertThat(segment.code()).isEqualTo("DAI-LY_1");
        assertThat(segment.name()).isEqualTo("Đại lý cấp 1");
        assertThat(segment.description()).isNull();
        assertThat(segment.version()).isZero();
        for (String bad : new String[]{null, "", "  ", "-LEAD", "Đại lý", "A B", "X".repeat(65)}) {
            assertThatThrownBy(() -> CustomerSegment.create(UUID.randomUUID(), bad, "Name", null))
                    .as(String.valueOf(bad)).extracting(CustomerSegmentTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        }
        assertThatThrownBy(() -> CustomerSegment.create(UUID.randomUUID(), "SME", " ", null))
                .extracting(CustomerSegmentTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> CustomerSegment.create(UUID.randomUUID(), "SME", "N", "d".repeat(1001)))
                .extracting(CustomerSegmentTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void renamingNeedsTheVersionRead() {
        CustomerSegment segment = CustomerSegment.restore(UUID.randomUUID(), "SME", "Shops", null, 3);
        assertThatThrownBy(() -> segment.rename("Small shops", null, 2))
                .extracting(CustomerSegmentTest::code).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        assertThat(segment.name()).isEqualTo("Shops");
        segment.rename("Small shops", "Independent cafés", 3);
        assertThat(segment.name()).isEqualTo("Small shops");
        assertThat(segment.code()).as("never rewritten").isEqualTo("SME");
    }

    @Test
    void aCustomerMovesBetweenGroupsOnlyFromTheVersionRead() {
        UUID group = UUID.randomUUID();
        Customer customer = new Customer(UUID.randomUUID(), null, "Cong ty A", "a@example.com", null, null,
                CustomerStatus.ACTIVE, java.util.List.of(), 4, java.time.Instant.EPOCH);
        assertThatThrownBy(() -> customer.assignSegment(group, 3)).isInstanceOf(IllegalStateException.class);
        assertThat(customer.segmentId()).isNull();
        customer.assignSegment(group, 4);
        assertThat(customer.segmentId()).isEqualTo(group);
        customer.assignSegment(null, 4);
        assertThat(customer.segmentId()).as("out of every group").isNull();
    }
}
