package com.stockflow.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * The envelope every endpoint returns, success or failure, so a client writes one response handler.
 *
 * <h2>Success</h2>
 * <pre>
 * { "success": true, "data": { ... }, "timestamp": "2026-09-10T09:15:00Z" }
 * </pre>
 *
 * <h2>Failure</h2>
 * <pre>
 * {
 *   "success": false,
 *   "errorCode": "VALIDATION_FAILED",
 *   "message": "Dữ liệu không hợp lệ",
 *   "fieldErrors": [ { "field": "quantity", "message": "phải lớn hơn 0", "code": "Min" } ],
 *   "correlationId": "8f3a...",
 *   "timestamp": "2026-09-10T09:15:00Z"
 * }
 * </pre>
 *
 * <p><b>{@code errorCode} is the contract; {@code message} is for humans.</b> The code is an
 * {@link com.stockflow.common.error.ErrorCode} name and never changes. The message is translated
 * and may be reworded at any time, so nothing should branch on it.</p>
 *
 * <p><b>{@code correlationId} is on the failure path for a reason.</b> A user reporting "it said
 * something went wrong" is unactionable; a user reading an id off the screen leads straight to the
 * stack trace. It is also returned in the {@code X-Correlation-Id} header, but a header is
 * invisible to somebody taking a screenshot.</p>
 *
 * <p>Null fields are omitted from the JSON ({@code @JsonInclude(NON_NULL)}), so a success response
 * does not carry four null error fields and a failure does not carry a null {@code data}.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "ApiResponse", description = "Uniform response envelope")
public record ApiResponse<T>(

        boolean success,

        @Schema(description = "Present on success")
        T data,

        @Schema(description = "Stable machine-readable code. Branch on this, never on the message.",
                example = "VALIDATION_FAILED")
        String errorCode,

        @Schema(description = "Human-readable and translated. May be reworded at any time.")
        String message,

        @Schema(description = "Per-field validation failures, so a form can place each message")
        List<FieldError> fieldErrors,

        @Schema(description = "Ties this response to the server logs; quote it in a support ticket")
        String correlationId,

        Instant timestamp
) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, null, null, null, Instant.now());
    }

    /** Success with no body - a 204-like response that still carries the envelope. */
    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, null, null, null, null, null, Instant.now());
    }

    public static <T> ApiResponse<T> error(String errorCode, String message) {
        return new ApiResponse<>(false, null, errorCode, message, null, null, Instant.now());
    }

    public static <T> ApiResponse<T> error(String errorCode, String message, String correlationId) {
        return new ApiResponse<>(false, null, errorCode, message, null, correlationId, Instant.now());
    }

    public static <T> ApiResponse<T> error(String errorCode, String message,
                                           List<FieldError> fieldErrors, String correlationId) {
        return new ApiResponse<>(false, null, errorCode, message,
                fieldErrors == null || fieldErrors.isEmpty() ? null : List.copyOf(fieldErrors),
                correlationId, Instant.now());
    }

    /**
     * Builder, for the responses the four factories above do not cover — a success that also carries
     * a human message ("Draft saved"), or a response assembled a field at a time.
     *
     * <p>The factories stay the default: they cannot express a nonsensical envelope, so reach for
     * one of them first. The builder exists for the genuine edge cases — and to keep it honest,
     * {@link Builder#build()} enforces the same invariants the factories give for free, so a
     * success-with-an-errorCode or a failure-carrying-data fails loudly at build time rather than
     * shipping a contradictory body a client cannot interpret.</p>
     */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /** Mutable assembler for {@link ApiResponse}; validates the result in {@link #build()}. */
    public static final class Builder<T> {

        private boolean success = true;
        private T data;
        private String errorCode;
        private String message;
        private List<FieldError> fieldErrors;
        private String correlationId;

        private Builder() {
        }

        public Builder<T> success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder<T> data(T data) {
            this.data = data;
            return this;
        }

        public Builder<T> errorCode(String errorCode) {
            this.errorCode = errorCode;
            return this;
        }

        public Builder<T> message(String message) {
            this.message = message;
            return this;
        }

        /** Adds one field error, initialising the list on first use. */
        public Builder<T> fieldError(FieldError fieldError) {
            if (fieldError != null) {
                if (this.fieldErrors == null) {
                    this.fieldErrors = new java.util.ArrayList<>();
                }
                this.fieldErrors.add(fieldError);
            }
            return this;
        }

        public Builder<T> fieldErrors(List<FieldError> fieldErrors) {
            this.fieldErrors = fieldErrors == null ? null : new java.util.ArrayList<>(fieldErrors);
            return this;
        }

        public Builder<T> correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        /**
         * @throws IllegalStateException if the parts contradict each other — the guarantee the
         *         factory methods enforce structurally and the reason this builder is safe to add
         */
        public ApiResponse<T> build() {
            boolean hasFieldErrors = fieldErrors != null && !fieldErrors.isEmpty();
            if (success) {
                if (errorCode != null) {
                    throw new IllegalStateException(
                            "a successful ApiResponse cannot carry an errorCode");
                }
                if (hasFieldErrors) {
                    throw new IllegalStateException(
                            "a successful ApiResponse cannot carry fieldErrors");
                }
            } else {
                if (errorCode == null) {
                    throw new IllegalStateException(
                            "a failure ApiResponse must carry an errorCode");
                }
                if (data != null) {
                    throw new IllegalStateException(
                            "a failure ApiResponse must not carry data");
                }
            }
            return new ApiResponse<>(success, data, errorCode, message,
                    hasFieldErrors ? List.copyOf(fieldErrors) : null,
                    correlationId, Instant.now());
        }
    }
}
