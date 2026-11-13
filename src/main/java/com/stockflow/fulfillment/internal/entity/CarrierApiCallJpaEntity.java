package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One call to a carrier's API (table {@code fulfillment.carrier_api_call}; kltn-docs 09 BR-29 "mọi lần gọi API hãng
 * lưu nhật ký - yêu cầu, kết quả, lỗi"). Append-only. A token call keeps no body: it carries the API key.
 */
@Entity
@Table(name = "carrier_api_call", schema = "fulfillment")
public class CarrierApiCallJpaEntity extends BaseEntity {

    private static final int MAX_BODY = 4000;

    @Column(name = "carrier_id", nullable = false, updatable = false) private UUID carrierId;
    @Column(name = "booking_id", updatable = false) private UUID bookingId;
    @Column(name = "operation", nullable = false, length = 16, updatable = false) private String operation;
    @Column(name = "outcome", nullable = false, length = 16, updatable = false) private String outcome;
    @Column(name = "http_status", updatable = false) private Integer httpStatus;
    @Column(name = "request_body", updatable = false) private String requestBody;
    @Column(name = "response_body", updatable = false) private String responseBody;
    @Column(name = "error", length = 1000, updatable = false) private String error;
    @Column(name = "duration_ms", nullable = false, updatable = false) private int durationMs;
    @Column(name = "called_at", nullable = false, updatable = false) private Instant calledAt;

    protected CarrierApiCallJpaEntity() {
    }

    public CarrierApiCallJpaEntity(UUID id, UUID carrierId, UUID bookingId, String operation, String outcome,
                                   Integer httpStatus, String requestBody, String responseBody, String error,
                                   int durationMs, Instant calledAt) {
        super(id);
        this.carrierId = carrierId;
        this.bookingId = bookingId;
        this.operation = operation;
        this.outcome = outcome;
        this.httpStatus = httpStatus;
        this.requestBody = cut(requestBody, MAX_BODY);
        this.responseBody = cut(responseBody, MAX_BODY);
        this.error = cut(error, 1000);
        this.durationMs = Math.max(0, durationMs);
        this.calledAt = calledAt;
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    public UUID getCarrierId() { return carrierId; }
    public UUID getBookingId() { return bookingId; }
    public String getOperation() { return operation; }
    public String getOutcome() { return outcome; }
    public Integer getHttpStatus() { return httpStatus; }
    public String getRequestBody() { return requestBody; }
    public String getResponseBody() { return responseBody; }
    public String getError() { return error; }
    public int getDurationMs() { return durationMs; }
    public Instant getCalledAt() { return calledAt; }
}
