package com.stockflow.common.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The response envelope is the one contract every client depends on, so its shape is asserted
 * rather than assumed.
 */
class ApiEnvelopeTest {

    @Test
    @DisplayName("a success carries no error fields")
    void successIsClean() {
        ApiResponse<String> response = ApiResponse.ok("value");

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isEqualTo("value");
        // Null so Jackson omits them - a success response should not carry four null error fields.
        assertThat(response.errorCode()).isNull();
        assertThat(response.fieldErrors()).isNull();
    }

    @Test
    @DisplayName("a failure carries the code, the correlation id and no data")
    void failureCarriesWhatSupportNeeds() {
        ApiResponse<Void> response = ApiResponse.error("NOT_FOUND", "Không tìm thấy", "abc-123");

        assertThat(response.success()).isFalse();
        assertThat(response.data()).isNull();
        assertThat(response.errorCode()).isEqualTo("NOT_FOUND");
        // The id a user can read off the screen and quote in a ticket.
        assertThat(response.correlationId()).isEqualTo("abc-123");
    }

    @Test
    @DisplayName("an empty field-error list is normalised to null so it is omitted from the JSON")
    void emptyFieldErrorsAreOmitted() {
        assertThat(ApiResponse.error("X", "m", List.of(), "cid").fieldErrors()).isNull();
        assertThat(ApiResponse.error("X", "m", null, "cid").fieldErrors()).isNull();
    }

    @Test
    @DisplayName("field errors survive so a form can place each message")
    void fieldErrorsArePreserved() {
        ApiResponse<Void> response = ApiResponse.error("VALIDATION_FAILED", "Dữ liệu không hợp lệ",
                List.of(new FieldError("quantity", "phải lớn hơn 0", "Min")), "cid");

        assertThat(response.fieldErrors()).singleElement()
                .satisfies(error -> {
                    assertThat(error.field()).isEqualTo("quantity");
                    // The constraint name is stable across translations; the message is not.
                    assertThat(error.code()).isEqualTo("Min");
                });
    }

    @Test
    @DisplayName("paging flags are derived, so no client has to compute them")
    void pagingFlags() {
        PageResponse<String> first = PageResponse.of(List.of("a", "b"), 0, 2, 5);
        assertThat(first.totalPages()).isEqualTo(3);
        assertThat(first.hasNext()).isTrue();
        assertThat(first.hasPrevious()).isFalse();

        PageResponse<String> last = PageResponse.of(List.of("e"), 2, 2, 5);
        // The off-by-one every client gets wrong at least once.
        assertThat(last.hasNext()).isFalse();
        assertThat(last.hasPrevious()).isTrue();
    }

    @Test
    @DisplayName("an exactly-full last page does not claim a further page")
    void exactlyFullLastPage() {
        PageResponse<String> page = PageResponse.of(List.of("c", "d"), 1, 2, 4);

        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    @DisplayName("an empty result is a valid page, not a special case")
    void emptyPage() {
        PageResponse<String> page = PageResponse.empty(0, 20);

        assertThat(page.items()).isEmpty();
        assertThat(page.totalPages()).isZero();
        assertThat(page.hasNext()).isFalse();
        assertThat(page.hasPrevious()).isFalse();
    }

    @Test
    @DisplayName("the items list cannot be mutated through the response")
    void itemsAreCopied() {
        java.util.List<String> mutable = new java.util.ArrayList<>(List.of("a"));
        PageResponse<String> page = PageResponse.of(mutable, 0, 20, 1);

        mutable.add("b");

        assertThat(page.items()).containsExactly("a");
    }

    // ---- builder: the factories cannot express every case, but build() re-imposes the same
    //      invariants they enforce structurally, so a contradictory envelope never ships ----

    @Test
    @DisplayName("builder: a success may also carry a human message")
    void builderSuccessWithMessage() {
        ApiResponse<String> response = ApiResponse.<String>builder()
                .data("value")
                .message("Đã lưu nháp")
                .build();

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isEqualTo("value");
        assertThat(response.message()).isEqualTo("Đã lưu nháp");
        assertThat(response.errorCode()).isNull();
    }

    @Test
    @DisplayName("builder: a failure can be assembled one field at a time")
    void builderFailureIncremental() {
        ApiResponse<Void> response = ApiResponse.<Void>builder()
                .success(false)
                .errorCode("VALIDATION_FAILED")
                .message("Dữ liệu không hợp lệ")
                .fieldError(FieldError.of("sku", "bắt buộc"))
                .fieldError(FieldError.of("quantity", "phải lớn hơn 0"))
                .correlationId("cid")
                .build();

        assertThat(response.success()).isFalse();
        assertThat(response.fieldErrors()).hasSize(2);
        assertThat(response.correlationId()).isEqualTo("cid");
    }

    @Test
    @DisplayName("builder: a success carrying an errorCode is rejected at build time")
    void builderRejectsSuccessWithErrorCode() {
        assertThatThrownBy(() -> ApiResponse.builder().success(true).errorCode("X").build())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("builder: a success carrying field errors is rejected")
    void builderRejectsSuccessWithFieldErrors() {
        assertThatThrownBy(() -> ApiResponse.builder()
                .success(true).fieldError(FieldError.of("a", "m")).build())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("builder: a failure without an errorCode is rejected")
    void builderRejectsFailureWithoutCode() {
        assertThatThrownBy(() -> ApiResponse.builder().success(false).build())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("builder: a failure carrying data is rejected - no leak past the envelope")
    void builderRejectsFailureWithData() {
        assertThatThrownBy(() -> ApiResponse.<String>builder()
                .success(false).errorCode("X").data("leak").build())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("builder: field errors are copied, not shared with the caller's list")
    void builderCopiesFieldErrors() {
        java.util.List<FieldError> mutable =
                new java.util.ArrayList<>(List.of(FieldError.of("a", "m")));
        ApiResponse<Void> response = ApiResponse.<Void>builder()
                .success(false).errorCode("X").fieldErrors(mutable).build();

        mutable.add(FieldError.of("b", "m2"));

        assertThat(response.fieldErrors()).hasSize(1);
    }
}
