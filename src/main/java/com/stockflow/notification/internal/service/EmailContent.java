package com.stockflow.notification.internal.service;

import java.util.List;
import java.util.Objects;

/**
 * What a customer message says, before it is laid out. Every part is plain text, already in the customer's language:
 * the templates ({@code templates/email/customer.html} and {@code .txt}) only arrange it, and the HTML one escapes
 * every part ({@code th:text}, never {@code th:utext}). Nothing here is markup, so nothing a customer or Sales typed -
 * a company name with {@code <script>} in it - can become markup.
 *
 * @param subject   the subject line
 * @param preheader the line a mail client previews after the subject
 * @param brand     the sender's name above the heading
 * @param heading   the message in one line
 * @param greeting  "Kính gửi ...,"
 * @param intro     paragraphs before the facts
 * @param facts     label / value pairs: order number, carrier, amounts
 * @param table     order lines, when the message lists them; null otherwise
 * @param outro     paragraphs after the facts
 * @param closing   "Trân trọng,"
 * @param signature who signs
 * @param footer    the small print: an automatic message, whether to reply
 */
record EmailContent(String subject, String preheader, String brand, String heading, String greeting,
                    List<String> intro, List<Fact> facts, Table table, List<String> outro, String closing,
                    String signature, String footer) {

    EmailContent {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(heading, "heading");
        intro = intro == null ? List.of() : List.copyOf(intro);
        facts = facts == null ? List.of() : List.copyOf(facts);
        outro = outro == null ? List.of() : List.copyOf(outro);
    }

    /** A missing value shows as nothing, never as "null". */
    record Fact(String label, String value) {

        Fact {
            Objects.requireNonNull(label, "label");
            value = value == null ? "" : value;
        }
    }

    /**
     * @param headers   column titles; the first column is left-aligned, the others are numbers
     * @param rows      one list of cells per row, as many as headers
     * @param note      a line under the table ("... and 3 more lines"); null for none
     */
    record Table(List<String> headers, List<List<String>> rows, String note) {

        Table {
            headers = List.copyOf(headers);
            rows = rows.stream().map(List::copyOf).toList();
            int columns = headers.size();
            if (rows.stream().anyMatch(row -> row.size() != columns)) {
                throw new IllegalArgumentException("Every row has one cell per header");
            }
        }
    }
}
