package com.stockflow.chat.internal.service;

import com.stockflow.chat.internal.domain.BusinessHours;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.Period;
import java.time.ZoneId;
import java.util.Set;

/**
 * Sales chat settings ({@code stockflow.chat}). The defaults are the owner's decisions of 2026-11-13 (kltn-docs 16 §4.1
 * step 3, open questions F3, F5); a deployment changes them here, not in code.
 *
 * @param businessZone         the zone business hours are read in
 * @param businessDays         the days Sales answer
 * @param businessOpens        when they start, {@code HH:mm}
 * @param businessCloses       when they stop, {@code HH:mm}
 * @param firstResponseTarget  how soon the first answer is due, counted in business hours (BR-09): 15 minutes
 * @param resolutionTarget     how soon a conversation is to be resolved, in working days of the business hours (BR-09):
 *                             2 - asked on Saturday at 17:00, due on Tuesday at 17:00. Days only: a month of working
 *                             days is no calendar month, so months and years are refused
 * @param autoCloseAfter       how long a conversation waits on a silent customer before it is auto-closed (16 §5)
 * @param reopenWindow         how long after it is resolved or auto-closed the customer's word reopens it, after which
 *                             it is closed for good (16 §5)
 * @param editWindow           how long its author may still edit or delete a message
 * @param customerNoticePeriod one notice to an offline customer per conversation per period, however many replies
 * @param draftQuoteValidity   how long a quote drawn up from a quote request card is valid, until Sales change it
 * @param slaJobBatch          conversations one run of an SLA job handles at most
 * @param typingInterval       at most one typing signal per person per conversation in this time; the rest are dropped
 * @param presenceTimeout      a staff socket silent this long (no heartbeat, no frame) shows the agent as away
 * @param maxPageSize          the most messages or conversations one request returns
 */
@ConfigurationProperties(prefix = "stockflow.chat")
public record ChatProperties(
        @DefaultValue("Asia/Ho_Chi_Minh") String businessZone,
        @DefaultValue({"MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY"}) Set<DayOfWeek> businessDays,
        @DefaultValue("08:00") String businessOpens,
        @DefaultValue("17:30") String businessCloses,
        @DefaultValue("PT15M") Duration firstResponseTarget,
        @DefaultValue("P2D") Period resolutionTarget,
        @DefaultValue("P7D") Duration autoCloseAfter,
        @DefaultValue("P14D") Duration reopenWindow,
        @DefaultValue("PT5M") Duration editWindow,
        @DefaultValue("PT1H") Duration customerNoticePeriod,
        @DefaultValue("P14D") Duration draftQuoteValidity,
        @DefaultValue("100") int slaJobBatch,
        @DefaultValue("PT2S") Duration typingInterval,
        @DefaultValue("PT90S") Duration presenceTimeout,
        @DefaultValue("100") int maxPageSize) {

    public ChatProperties {
        requirePositive(firstResponseTarget, "first-response-target");
        if (resolutionTarget == null || resolutionTarget.getYears() != 0 || resolutionTarget.getMonths() != 0
                || resolutionTarget.getDays() < 1) {
            throw new IllegalArgumentException("stockflow.chat.resolution-target is a positive number of working days");
        }
        requirePositive(autoCloseAfter, "auto-close-after");
        requirePositive(reopenWindow, "reopen-window");
        requirePositive(editWindow, "edit-window");
        requirePositive(customerNoticePeriod, "customer-notice-period");
        requirePositive(draftQuoteValidity, "draft-quote-validity");
        requirePositive(typingInterval, "typing-interval");
        requirePositive(presenceTimeout, "presence-timeout");
        if (maxPageSize < 1 || slaJobBatch < 1) {
            throw new IllegalArgumentException("stockflow.chat.max-page-size and sla-job-batch must be at least 1");
        }
        businessHours(businessZone, businessDays, businessOpens, businessCloses);
    }

    /** The hours the chat's clocks count in. */
    public BusinessHours businessHours() {
        return businessHours(businessZone, businessDays, businessOpens, businessCloses);
    }

    /** A page size the caller asked for, kept between 1 and {@link #maxPageSize}. */
    public int pageSize(Integer requested, int fallback) {
        int size = requested == null ? fallback : requested;
        return Math.max(1, Math.min(size, maxPageSize));
    }

    private static BusinessHours businessHours(String zone, Set<DayOfWeek> days, String opens, String closes) {
        return new BusinessHours(ZoneId.of(zone), days, LocalTime.parse(opens), LocalTime.parse(closes));
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("stockflow.chat." + name + " must be positive");
        }
    }
}
