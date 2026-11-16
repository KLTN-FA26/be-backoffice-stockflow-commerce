package com.stockflow.invoicing.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.ProviderUnavailableException;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.Result;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.ResultState;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.Submission;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.SubmissionOutcome;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What SePay eInvoice's answers mean - the one place that decides it, from SePay's developer documentation
 * (developer.sepay.vn, "eInvoice API", "Xử lý lỗi").
 *
 * <p>Errors come as {@code {"success": false, "error": {"code", "message"}}} and are told apart by {@code code}. An
 * error that is SePay's decision about <i>the invoice</i> (a 400, 409 or 422 with a code) refuses it. An error about
 * <i>the account</i> - quota used up, registration or billing not done, the tax authority not having approved the
 * registration - is not the invoice's fault and is fixed on SePay's portal: nothing was created, so the invoice stays
 * waiting and is sent again once it is. A timeout, a 5xx, a 401 after a fresh token, a 429 or an answer that is not
 * JSON say nothing about the invoice.</p>
 *
 * <h2>ASSUMPTIONS - what the documentation does not state</h2>
 * <ul>
 *   <li><b>The invoice's state.</b> The look-up by {@code reference_code} returns {@code status}; the documentation
 *       shows {@code draft} and {@code issued}. {@code issued} with a number is taken for issued, as are
 *       {@code replaced} and {@code adjusted} (an invoice later corrected was issued). {@code draft} is still being
 *       processed. Anything else is asked about again, never taken for "not there".</li>
 *   <li><b>The tax authority's code and the look-up code</b> are in no documented answer. The code is taken from a
 *       field of the answer if SePay adds one, else from the signed XML's {@code MCCQT}; a sandbox invoice that has
 *       neither is given a code marked {@code SEPAY-SANDBOX} so nothing takes it for a real one. A production invoice
 *       without one waits. The look-up code is SePay's own if it gives one, else the reference the invoice is
 *       looked up by on SePay.</li>
 *   <li><b>"Not there".</b> A 404 is SePay never having recorded the reference only when it carries SePay's error
 *       body; a 404 without one (a proxy's page) is not known.</li>
 * </ul>
 *
 * <p><b>Seen on SePay's sandbox</b> (one invoice issued through this adapter, 2026-11-16): the look-up answers 404 for
 * a few seconds after a create was taken in, then {@code issued} with the number; the download call takes the
 * {@code reference_code} (the documentation's path says tracking code) and hands over the XML and the PDF; the
 * sandbox's XML has an empty {@code MCCQT}, so the sandbox code above is what such an invoice carries; the PDF prints
 * the reference as its "Mã tra cứu", which is why it is the look-up code. The seller printed is the SePay account's
 * registration, not the system's seller.</p>
 */
final class SepayEInvoiceAnswers {

    static final String DOCUMENT_EXISTED = "EINVOICE_DOCUMENT_EXISTED";
    /** Marks a tax authority code made up for a sandbox invoice SePay gave none for. */
    static final String SANDBOX_CODE_PREFIX = "SEPAY-SANDBOX-";
    /** About the account, not the invoice: fixed on SePay's portal, and the invoice waits until it is. */
    static final Set<String> ACCOUNT_STATE = Set.of("QUOTA_HAS_BEEN_USERD_UP", "REGISTRATION_NOT_COMPLETE",
            "BILLING_IS_UNPAID", "EINVOICE_ACCOUNT_NOT_FOUND", "REGISTRATION_NOT_TAX_APPROVED");
    private static final Set<String> ISSUED = Set.of("issued", "replaced", "adjusted");
    private static final Set<String> WORKING = Set.of("draft", "pending", "processing");
    private static final List<String> TAX_AUTHORITY_CODE_FIELDS = List.of("tax_authority_code", "mccqt", "cqt_code");
    private static final List<String> LOOKUP_CODE_FIELDS = List.of("lookup_code", "search_code", "secret_code");
    private static final int CODE_LIMIT = 64;
    private static final Pattern MCCQT = Pattern.compile("<(?:\\w+:)?MCCQT[^>]*>\\s*([^<\\s][^<]*?)\\s*<");
    private static final Pattern SIGNING_TIME = Pattern.compile("<(?:\\w+:)?SigningTime[^>]*>\\s*([^<]+?)\\s*<");
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private SepayEInvoiceAnswers() {
    }

    /** An error about the account: nothing was created, and nothing will be until it is fixed on SePay's portal. */
    static final class AccountStateException extends RuntimeException {

        private static final long serialVersionUID = 1L;
        private final String code;

        AccountStateException(String code) {
            super("SePay eInvoice refused the account: " + code);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    /**
     * What a create, adjust or replace answer says.
     *
     * @throws AccountStateException       for an error about the account
     * @throws ProviderUnavailableException when the answer says nothing about the invoice
     */
    static Submission submission(int status, JsonNode body) {
        String code = errorCode(body);
        if (status >= 200 && status < 300 && code == null && !refused(body)) {
            return new Submission(SubmissionOutcome.ACCEPTED, null, null);
        }
        if (status == 409 && DOCUMENT_EXISTED.equals(code)) {
            return new Submission(SubmissionOutcome.DUPLICATE, null, null);
        }
        if (code != null && ACCOUNT_STATE.contains(code)) {
            throw new AccountStateException(code);
        }
        if ((status == 400 || status == 409 || status == 422) && body != null && body.has("error")) {
            return new Submission(SubmissionOutcome.REJECTED, code != null ? code : "SEPAY_HTTP_" + status,
                    errorMessage(body));
        }
        throw new ProviderUnavailableException("SePay eInvoice did not take the invoice in (HTTP " + status + ")");
    }

    /**
     * Where an invoice looked up by its reference stands. ISSUED here is a candidate: {@link #issued} completes it.
     *
     * @throws ProviderUnavailableException when the answer says nothing (a 5xx, a 401 after a fresh token, a 429)
     */
    static ResultState state(int status, JsonNode body) {
        if (status == 404) {
            return body != null && (body.has("error") || refused(body)) ? ResultState.NOT_FOUND : ResultState.UNKNOWN;
        }
        if (status == 401 || status == 408 || status == 429 || status >= 500) {
            throw new ProviderUnavailableException("SePay eInvoice did not answer the look-up (HTTP " + status + ")");
        }
        JsonNode data = body == null ? null : body.get("data");
        if (status < 200 || status >= 300 || data == null || !data.isObject()) {
            return ResultState.UNKNOWN;
        }
        String state = text(data, "status");
        state = state == null ? "" : state.toLowerCase(Locale.ROOT);
        if (ISSUED.contains(state)) {
            return invoiceNumber(data) != null ? ResultState.ISSUED : ResultState.UNKNOWN;
        }
        return WORKING.contains(state) ? ResultState.PENDING : ResultState.UNKNOWN;
    }

    /**
     * An issued invoice as the system records it, or null while it lacks the tax authority's code.
     *
     * @param data      the look-up's {@code data}
     * @param xml       the signed XML, when SePay hands it over; null otherwise
     * @param reference the reference it was looked up by
     * @param sandbox   whether the account is SePay's sandbox
     */
    static Result issued(JsonNode data, byte[] xml, String reference, boolean sandbox, Instant now) {
        String xmlText = xml == null ? null : new String(xml, StandardCharsets.UTF_8);
        String taxAuthorityCode = first(data, TAX_AUTHORITY_CODE_FIELDS);
        if (taxAuthorityCode == null && xmlText != null) {
            taxAuthorityCode = match(MCCQT, xmlText);
        }
        if (taxAuthorityCode == null && sandbox) {
            taxAuthorityCode = SANDBOX_CODE_PREFIX + reference;
        }
        if (taxAuthorityCode == null) {
            return null;
        }
        String lookupCode = first(data, LOOKUP_CODE_FIELDS);
        if (lookupCode == null) {
            lookupCode = text(data, "reference_code") != null ? text(data, "reference_code") : reference;
        }
        Instant signedAt = xmlText == null ? null : instant(match(SIGNING_TIME, xmlText));
        return new Result(ResultState.ISSUED, invoiceNumber(data), limit(taxAuthorityCode), limit(lookupCode),
                signedAt != null ? signedAt : now, null, null);
    }

    /** The number SePay gave, 1 to 99 999 999; null for a draft's {@code "0"} or anything else. */
    static Integer invoiceNumber(JsonNode data) {
        String number = text(data, "invoice_number");
        if (number == null || !number.matches("[0-9]{1,8}")) {
            return null;
        }
        try {
            int value = Integer.parseInt(number);
            return value >= 1 ? value : null;
        } catch (NumberFormatException unreadable) {
            // Eight ASCII digits always parse; kept so an answer can never throw out of the reading of it.
            return null;
        }
    }

    /**
     * A file the download call handed over, decoded; null when it has none to hand over (a 404, an empty content).
     *
     * @throws ProviderUnavailableException when the answer says nothing
     */
    static byte[] file(int status, JsonNode body) {
        if (status == 404 || status == 400) {
            return null;
        }
        if (status < 200 || status >= 300) {
            throw new ProviderUnavailableException("SePay eInvoice did not hand the file over (HTTP " + status + ")");
        }
        JsonNode data = body == null ? null : body.get("data");
        String content = data == null ? null : text(data, "content");
        if (content == null) {
            return null;
        }
        try {
            byte[] bytes = Base64.getMimeDecoder().decode(content);
            return bytes.length == 0 ? null : bytes;
        } catch (IllegalArgumentException notBase64) {
            throw new ProviderUnavailableException("SePay eInvoice handed over a file that cannot be read");
        }
    }

    /** The XML itself: SePay may hand it over zipped, as Viettel does. */
    static byte[] xmlOf(byte[] file) {
        if (file != null && file.length > 1 && file[0] == 'P' && file[1] == 'K') {
            return ViettelSInvoiceAnswers.xmlOf(file);
        }
        return file;
    }

    static String errorCode(JsonNode body) {
        JsonNode error = body == null ? null : body.get("error");
        return error == null ? null : text(error, "code");
    }

    static String errorMessage(JsonNode body) {
        JsonNode error = body == null ? null : body.get("error");
        String message = error == null ? null : text(error, "message");
        return message == null ? "SePay eInvoice refused the invoice" : message;
    }

    private static boolean refused(JsonNode body) {
        JsonNode success = body == null ? null : body.get("success");
        return success != null && success.isBoolean() && !success.asBoolean();
    }

    private static String first(JsonNode data, List<String> fields) {
        for (String field : fields) {
            String value = text(data, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || value.isContainerNode()) {
            return null;
        }
        String text = value.asText().strip();
        return text.isEmpty() ? null : text;
    }

    private static String match(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).strip() : null;
    }

    /** A signing time as the XML writes it: with an offset, or Viet Nam's local time without one. */
    private static Instant instant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException noOffset) {
            try {
                return LocalDateTime.parse(value).atZone(ZONE).toInstant();
            } catch (DateTimeParseException unreadable) {
                return null;
            }
        }
    }

    private static String limit(String value) {
        return value.length() <= CODE_LIMIT ? value : value.substring(0, CODE_LIMIT);
    }
}
