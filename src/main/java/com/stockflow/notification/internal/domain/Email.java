package com.stockflow.notification.internal.domain;

import java.util.List;
import java.util.regex.Pattern;

/**
 * One e-mail, ready for a transport: who it is from and to, what it says in plain text and, optionally, in HTML.
 *
 * <p>Every transport receives the same value, so supplier, staff and customer mail are built once and the provider is a
 * configuration choice. {@code text} is always present: a message with only HTML is more likely to be filtered as spam,
 * and a plain-text reader still gets everything.</p>
 *
 * <p>{@code idempotencyKey} names the logical message, not the attempt: a redelivered event or a retry after a timeout
 * sends the same key, and a provider that honours it (Resend keeps it 24 hours) sends the message once. Null where a
 * second send is the caller's explicit intent (a resent portal invitation carries a new secret).</p>
 *
 * <p>{@code category} becomes a provider tag for its dashboards: letters, digits, {@code _} and {@code -} only (Resend's
 * rule), and never anything personal.</p>
 *
 * <p>{@code attachments} are files sent with the message - an issued e-invoice's PDF and XML (WF-05 E5). They are part
 * of the logical message: every attempt under one idempotency key carries the same files, or a provider that honours
 * the key refuses the second attempt as a different message.</p>
 *
 * <p>{@link #toString()} names sizes and counts only. Subjects and bodies carry names, amounts and - for an invitation -
 * a secret link, and a value that is printed whole the day someone logs it is a leak waiting for that day.</p>
 */
public record Email(String from, List<String> to, String replyTo, String subject, String text, String html,
                    String idempotencyKey, String category, List<Attachment> attachments) {

    /**
     * A file sent with the message.
     *
     * @param name        the file name the recipient saves it under: one line, no path
     * @param contentType its media type ({@code application/pdf})
     * @param content     its bytes
     */
    public record Attachment(String name, String contentType, byte[] content) {

        public Attachment {
            if (name == null || name.isBlank() || name.chars().anyMatch(c -> Character.isISOControl(c) || c == '/'
                    || c == '\\')) {
                throw new IllegalArgumentException("An attachment needs a file name: one line, no path");
            }
            if (contentType == null || contentType.isBlank()) {
                throw new IllegalArgumentException("An attachment needs a content type");
            }
            if (content == null || content.length == 0) {
                throw new IllegalArgumentException("An attachment needs content");
            }
        }

        /** The name and the size, never the bytes. */
        @Override
        public String toString() {
            return "Attachment[" + name + ", " + content.length + " bytes]";
        }
    }

    /** A message with no attachment: every message but the issued e-invoice's. */
    public Email(String from, List<String> to, String replyTo, String subject, String text, String html,
                 String idempotencyKey, String category) {
        this(from, to, replyTo, subject, text, html, idempotencyKey, category, List.of());
    }

    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9_-]{1,256}");
    private static final int MAX_KEY_LENGTH = 256;

    public Email {
        if (from == null || from.isBlank()) {
            throw new IllegalArgumentException("An e-mail needs a sender");
        }
        to = to == null ? List.of() : List.copyOf(to);
        if (to.isEmpty() || to.stream().anyMatch(address -> address == null || address.isBlank()
                || address.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("An e-mail needs at least one recipient, each a single line");
        }
        // A line break in a header is how a header is injected; a subject is one line whatever the caller passed.
        subject = subject == null ? "" : oneLine(subject);
        if (subject.isBlank()) {
            throw new IllegalArgumentException("An e-mail needs a subject");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("An e-mail needs a plain-text body");
        }
        replyTo = replyTo == null || replyTo.isBlank() ? null : oneLine(replyTo);
        html = html == null || html.isBlank() ? null : html;
        if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH
                || !idempotencyKey.chars().allMatch(c -> c > 0x20 && c < 0x7f))) {
            throw new IllegalArgumentException("An idempotency key is 1.." + MAX_KEY_LENGTH + " printable ASCII characters");
        }
        if (category != null && !TAG.matcher(category).matches()) {
            throw new IllegalArgumentException("A category is letters, digits, '_' and '-' only");
        }
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    /** A text-only message with no idempotency key and no category: staff and supplier mail. */
    public static Email plain(String from, List<String> to, String replyTo, String subject, String text) {
        return new Email(from, to, replyTo, subject, text, null, null, null);
    }

    private static String oneLine(String value) {
        StringBuilder line = new StringBuilder(value.length());
        value.codePoints().forEach(c -> line.appendCodePoint(Character.isISOControl(c) ? ' ' : c));
        return line.toString().strip();
    }

    @Override
    public String toString() {
        return "Email[to=" + to.size() + " recipient(s), subjectLength=" + subject.length() + ", textLength="
                + text.length() + ", html=" + (html != null) + ", idempotent=" + (idempotencyKey != null)
                + ", category=" + category + ", attachments=" + attachments.size() + "]";
    }
}
