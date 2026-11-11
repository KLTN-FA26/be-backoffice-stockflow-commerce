package com.stockflow.order.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Who makes the customers' printed goods (4A-1, kltn-docs 17 §4.3): the company's own workshop or a partner, handled
 * alike - they differ only in how a production order reaches them. Kept by the Production Coordinator, with the
 * producer's standard lead time (the days from sending an order to the goods at the warehouse, default 14, open
 * question B44), which the late warnings measure against (§4.12).
 *
 * <p>Never deleted: production orders name it. An INACTIVE producer takes no new production order.</p>
 */
public final class Producer {

    /** 17 §4.3: a sheet (PDF) the coordinator hands over, or an e-mail the system sends; an API is for later. */
    public enum Channel { EMAIL, PDF }

    public enum Status { ACTIVE, INACTIVE }

    /** The default standard lead time (open question B44). */
    public static final int DEFAULT_LEAD_DAYS = 14;

    private static final Pattern CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{1,31}");

    private final UUID id;
    private final String code;
    private String name;
    private String contactName;
    private String contactPhone;
    private String email;
    private Channel channel;
    private int standardLeadDays;
    private Status status;
    private String note;
    private final long version;

    /** Restores one as stored. */
    public Producer(UUID id, String code, String name, String contactName, String contactPhone, String email,
                    Channel channel, int standardLeadDays, Status status, String note, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.code = Objects.requireNonNull(code, "code");
        this.name = name;
        this.contactName = contactName;
        this.contactPhone = contactPhone;
        this.email = email;
        this.channel = Objects.requireNonNull(channel, "channel");
        this.standardLeadDays = standardLeadDays;
        this.status = Objects.requireNonNull(status, "status");
        this.note = note;
        this.version = version;
    }

    /** What the coordinator enters for a producer. A null lead time is the default (B44). */
    public record Details(String name, String contactName, String contactPhone, String email, Channel channel,
                          Integer standardLeadDays, String note) {
    }

    /**
     * A new producer, ACTIVE. The code is upper-cased; it identifies the producer for good.
     *
     * @throws BusinessException {@code VALIDATION_FAILED} for a code that is not 2-32 letters, digits, '-' or '_', or
     *         details that break a rule ({@link #edit})
     */
    public static Producer register(String code, Details details) {
        String normalized = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(normalized).matches()) {
            throw invalid("A producer's code is 2 to 32 letters, digits, '-' or '_', starting with a letter or digit");
        }
        Producer producer = new Producer(Identifiers.newId(), normalized, null, null, null, null, Channel.EMAIL,
                DEFAULT_LEAD_DAYS, Status.ACTIVE, null, 0L);
        producer.edit(details);
        return producer;
    }

    /**
     * New details: a name, a channel - an e-mail address when orders go by e-mail - and a lead time of 1 to 365 days.
     *
     * @throws BusinessException {@code VALIDATION_FAILED}
     */
    public void edit(Details details) {
        Objects.requireNonNull(details, "details");
        String newName = text(details.name(), 200);
        if (newName == null) {
            throw invalid("A producer has a name of at most 200 characters");
        }
        String newEmail = text(details.email(), 254);
        if (newEmail != null && !isEmail(newEmail)) {
            throw invalid("The e-mail address is not one");
        }
        Channel newChannel = details.channel() == null ? Channel.EMAIL : details.channel();
        if (newChannel == Channel.EMAIL && newEmail == null) {
            throw invalid("Orders go to the producer by e-mail: give its address, or choose the PDF channel");
        }
        int lead = details.standardLeadDays() == null ? DEFAULT_LEAD_DAYS : details.standardLeadDays();
        if (lead < 1 || lead > 365) {
            throw invalid("The standard lead time is 1 to 365 days");
        }
        this.name = newName;
        this.contactName = text(details.contactName(), 200);
        this.contactPhone = text(details.contactPhone(), 32);
        this.email = newEmail;
        this.channel = newChannel;
        this.standardLeadDays = lead;
        this.note = text(details.note(), 500);
    }

    /** Takes no new production order while INACTIVE; those it has are kept. */
    public void changeStatus(Status newStatus) {
        this.status = Objects.requireNonNull(newStatus, "status");
    }

    /** Whether a new production order may name it. */
    public boolean takesOrders() {
        return status == Status.ACTIVE;
    }

    /**
     * The shape {@code [^@\s]+@[^@\s]+\.[^@\s]+}: no whitespace, exactly one '@' with something before it, and a dot
     * after it with something on both sides. Checked in one pass over the characters rather than by that regular
     * expression, whose two {@code [^@\s]+} around the dot backtrack over each other: quadratic on a domain of many
     * dots (CodeQL {@code java/polynomial-redos}). The 254-character bound kept it cheap; this keeps it linear.
     */
    static boolean isEmail(String value) {
        int at = -1;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            // Java's \s: space, tab, line feed, vertical tab, form feed, carriage return.
            if (c == ' ' || c == '\t' || c == '\n' || c == '\013' || c == '\f' || c == '\r') {
                return false;
            }
            if (c == '@') {
                if (at >= 0) {
                    return false;
                }
                at = i;
            }
        }
        if (at < 1) {
            return false;
        }
        // A dot with at least one character of the domain before it and one after it.
        for (int i = at + 2; i < value.length() - 1; i++) {
            if (value.charAt(i) == '.') {
                return true;
            }
        }
        return false;
    }

    /** Trimmed, null when blank; refused when longer than {@code max}. */
    private static String text(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.length() > max) {
            throw invalid("A text field is at most %d characters".formatted(max));
        }
        return trimmed;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }

    public UUID id() { return id; }
    public String code() { return code; }
    public String name() { return name; }
    public String contactName() { return contactName; }
    public String contactPhone() { return contactPhone; }
    public String email() { return email; }
    public Channel channel() { return channel; }
    public int standardLeadDays() { return standardLeadDays; }
    public Status status() { return status; }
    public String note() { return note; }
    public long version() { return version; }
}
