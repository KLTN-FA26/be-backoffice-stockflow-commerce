package com.stockflow.invoicing.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.ProviderUnavailableException;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.Result;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.ResultState;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.Submission;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.SubmissionOutcome;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
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
 * <i>the account</i> ({@link #ACCOUNT_STATE}) refuses it too (HĐ-1). The documentation of the create call says of every
 * error it lists, these included, that the invoice is not created and not sent to the tax authority, and of the quota
 * that it does not resolve on its own and is not to be retried automatically
 * (developer.sepay.vn/vi/einvoice-api/v1/xuat-hoa-don-dien-tu; the same codes on /xuat-hoa-don-dieu-chinh and
 * /xuat-hoa-don-thay-the; HTTP 500 for the quota, billing, registration and account, 422 for the tax approval;
 * /xu-ly-loi: "retrying does not change these"). They used to be taken for "SePay did not answer": the invoice sat
 * SUBMITTING, sent again every few minutes, while the accountant was told only that it was slow. Refused under a code
 * of the system's ({@link #accountCode}), the invoice is reopened and issued again once the account is fixed on
 * my.sepay.vn, and the accountants are told the cause once ({@code EInvoiceProvider.accountProblem}). A timeout, any
 * other 5xx, a 401 after a fresh token, a 429 or an answer that is not JSON say nothing about the invoice.</p>
 *
 * <p><b>The result after it was taken in</b> is SePay's tracking call
 * ({@code GET /v1/invoices/create/check/{tracking_code}} for a create, an adjust and a replace alike - the page says the
 * path is shared by the three, and {@code /adjust/check/}, {@code /replace/check/} answer 404 on the sandbox (WF-05
 * BE-61, run X-4); developer.sepay.vn/vi/einvoice-api/v1/theo-doi-trang-thai-xuat-hoa-don): {@code Pending} (SePay retries its own
 * errors for up to two hours), {@code Success}, {@code Failed} with a {@code message}. A {@code Failed} whose message
 * says SePay could not confirm the result with the invoicing provider ("chưa xác nhận được kết quả phát hành") is not a
 * failure: the documentation says the result is unknown and the request must not be sent again
 * ({@link Tracking#UNCONFIRMED}; kltn-docs 21 BR-05). Every other {@code Failed} is a refusal: the documentation says
 * to correct the data and issue again.</p>
 *
 * <p><b>Telling the two apart rests on SePay's words</b>, since the tracking carries no code for it. So the words are
 * read without accents, case or extra spacing ({@link #unconfirmed}), and narrowly: the phrase "result not confirmed
 * yet", its words next to each other - Vietnamese with or without accents, or English. Narrowly on purpose: a reading
 * too loose takes a real refusal ("chưa xác nhận được MST người mua theo kết quả tra cứu") for "not known", and an
 * unknown result ({@link #RESULT_UNCONFIRMED}) is taken back by the accountant only with SePay support's written answer
 * (decision Q-5, {@code EInvoiceIssuance.withdraw}), holding its series back meanwhile. A {@code Failed}
 * without words is unconfirmed. And a refusal read from the words is still not trusted on its own: it carries
 * {@link #ISSUANCE_FAILED}, which {@code SepayEInvoiceProvider.refusalUnconfirmedByLookup} names, so the invoice is
 * recorded as refused only once SePay can no longer be working on the transaction and, asked again, still says so
 * ({@code EInvoiceIssuance}, "A refusal nobody confirmed is recorded only once it is settled"). A rewording SePay makes
 * tomorrow then costs a wait of a few hours, never a second invoice.</p>
 *
 * <h2>ASSUMPTIONS - what the documentation does not state</h2>
 * <ul>
 *   <li><b>The invoice's state.</b> The look-up by {@code reference_code} returns {@code status}; the documentation
 *       shows {@code draft} and {@code issued} and lists no other value (/chi-tiet-hoa-don, /danh-sach-hoa-don).
 *       {@code issued} with a number is taken for issued, as are {@code replaced} and {@code adjusted} (an invoice
 *       later corrected was issued). The same words <b>without</b> a number are SePay holding the invoice while it is
 *       numbered: PENDING, never UNKNOWN (WF-05 BE-07, decision Q-5) - an UNKNOWN invoice could once be withdrawn
 *       and issued again, which would have made a second invoice of one SePay already holds. {@code draft} is still
 *       being processed. {@link #REFUSED} - {@code failed},
 *       {@code rejected}, {@code cancelled}, {@code deleted} - are taken for "no invoice in force exists under this
 *       reference", i.e. REJECTED: under each of those words the invoice is refused or void, so the accountant
 *       correcting it and issuing it again under a new reference issues the only invoice in force. That is the
 *       conservative reading of words SePay may never send; anything else is asked about again, never taken for "not
 *       there".</li>
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
    /**
     * About the account, not the invoice (/xu-ly-loi; the misspelling is SePay's own): nothing was created, and nothing
     * will be until it is fixed on SePay's portal. Each is refused under the system's code {@link #accountCode} gives.
     */
    static final Set<String> ACCOUNT_STATE = Set.of("QUOTA_HAS_BEEN_USERD_UP", "REGISTRATION_NOT_COMPLETE",
            "BILLING_IS_UNPAID", "EINVOICE_ACCOUNT_NOT_FOUND", "REGISTRATION_NOT_TAX_APPROVED");
    /** The plan's invoices are used up. */
    static final String QUOTA_EXHAUSTED = "SEPAY_QUOTA_EXHAUSTED";
    /** The plan is not paid. */
    static final String BILLING_UNPAID = "SEPAY_BILLING_UNPAID";
    /** The registration is not complete, not approved by the tax authority, or not the one named. */
    static final String ACCOUNT_NOT_READY = "SEPAY_ACCOUNT_NOT_READY";
    /** The series has no SePay template code: nothing was sent. */
    static final String TEMPLATE_MISSING = "SEPAY_TEMPLATE_MISSING";
    /** SePay's tracking said Failed: its processing refused the invoice after taking it in. */
    static final String ISSUANCE_FAILED = "SEPAY_ISSUANCE_FAILED";
    /** SePay's tracking said it could not confirm the result with the invoicing provider: not to be sent again. */
    static final String RESULT_UNCONFIRMED = "SEPAY_RESULT_UNCONFIRMED";
    private static final Set<String> ISSUED = Set.of("issued", "replaced", "adjusted");
    /** Look-up states taken for "no invoice in force under this reference" (see ASSUMPTIONS). */
    static final Set<String> REFUSED = Set.of("failed", "rejected", "cancelled", "canceled", "deleted");
    /**
     * What SePay's tracking says when it does not know the result (/theo-doi-trang-thai-xuat-hoa-don: "chưa xác nhận
     * được kết quả phát hành"), as words compared without accents, case or extra spaces ({@link #unconfirmed}): the
     * phrase itself, its words next to each other - "chưa xác nhận (được) kết quả", or in English "unconfirmed result" /
     * "result (is) not (yet) confirmed". Words merely present somewhere are not enough: "chưa xác nhận được MST người
     * mua theo kết quả tra cứu" is a refusal of the buyer's data.
     */
    private static final Pattern UNCONFIRMED = Pattern.compile("\\bchua xac nhan( duoc)? ket qua\\b"
            + "|\\bunconfirmed results?\\b"
            + "|\\bresults? (is |was )?(still )?(unconfirmed|not (yet )?confirmed)\\b");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Set<String> WORKING = Set.of("draft", "pending", "processing");
    private static final List<String> TAX_AUTHORITY_CODE_FIELDS = List.of("tax_authority_code", "mccqt", "cqt_code");
    private static final List<String> LOOKUP_CODE_FIELDS = List.of("lookup_code", "search_code", "secret_code");
    private static final int CODE_LIMIT = 64;
    private static final Pattern MCCQT = Pattern.compile("<(?:\\w+:)?MCCQT[^>]*>\\s*([^<\\s][^<]*?)\\s*<");
    private static final Pattern SIGNING_TIME = Pattern.compile("<(?:\\w+:)?SigningTime[^>]*>\\s*([^<]+?)\\s*<");
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private SepayEInvoiceAnswers() {
    }

    /** The system's rejection code for one of SePay's account errors; null for any other code. */
    static String accountCode(String sepayCode) {
        if (sepayCode == null) {
            return null;
        }
        return switch (sepayCode) {
            case "QUOTA_HAS_BEEN_USERD_UP" -> QUOTA_EXHAUSTED;
            case "BILLING_IS_UNPAID" -> BILLING_UNPAID;
            case "REGISTRATION_NOT_COMPLETE", "REGISTRATION_NOT_TAX_APPROVED", "EINVOICE_ACCOUNT_NOT_FOUND" ->
                    ACCOUNT_NOT_READY;
            default -> null;
        };
    }

    /**
     * What a create, adjust or replace answer says.
     *
     * @param kind {@code create}, {@code adjust} or {@code replace}: where the submission's tracking is asked
     * @throws ProviderUnavailableException when the answer says nothing about the invoice
     */
    static Submission submission(int status, JsonNode body, String kind) {
        String code = errorCode(body);
        if (status >= 200 && status < 300 && code == null && !refused(body)) {
            return new Submission(SubmissionOutcome.ACCEPTED, null, null, trackingReference(kind, body));
        }
        if (status == 409 && DOCUMENT_EXISTED.equals(code)) {
            return new Submission(SubmissionOutcome.DUPLICATE, null, null);
        }
        if (code != null && ACCOUNT_STATE.contains(code)) {
            // Nothing was created (the create call's documentation, for every error it lists): a refusal under the
            // system's code, with SePay's own code kept in the words the accountant reads.
            return new Submission(SubmissionOutcome.REJECTED, accountCode(code), code + ": " + errorMessage(body));
        }
        if ((status == 400 || status == 409 || status == 422) && body != null && body.has("error")) {
            return new Submission(SubmissionOutcome.REJECTED, code != null ? code : "SEPAY_HTTP_" + status,
                    errorMessage(body));
        }
        throw new ProviderUnavailableException("SePay eInvoice did not take the invoice in (HTTP " + status + ")");
    }

    /** Where SePay's tracking says a submission stands. */
    enum Tracking {
        /** Still processed; SePay retries its own errors for up to two hours. */
        PENDING,
        SUCCESS,
        /** Refused after it was taken in: nothing is issued under the reference. */
        FAILED,
        /** SePay does not know the result: never sent again until it does (BR-05). */
        UNCONFIRMED,
        /** The answer says nothing (no such tracking code, a shape not recognised). */
        NONE
    }

    /**
     * The handle a create, adjust or replace answer gives: {@code <kind>/<tracking_code>}; null when it gives none that
     * can be put in a path safely. The kind says which call took the submission in, for the person reading the call
     * log; the tracking is asked at {@code /create/check/} for every kind ({@code SepayEInvoiceClient.tracking}).
     */
    static String trackingReference(String kind, JsonNode body) {
        JsonNode data = body == null ? null : body.get("data");
        String code = text(data, "tracking_code");
        if (kind == null || code == null || !code.matches("[0-9A-Za-z-]{1,64}")) {
            return null;
        }
        return kind + "/" + code;
    }

    /**
     * What a tracking answer says ({@code data.status}, {@code data.message}), the invoice it carries
     * ({@code data.invoice.status}) first.
     *
     * <p><b>The top-level status reports the request, not the invoice</b> (sandbox run T-8, 24/11/2026: for a draft
     * whose issue failed, {@code /create/check} answered {@code "status": "Success"}, "Xuất hóa đơn điện tử thành
     * công", while its {@code invoice.status} was {@code "failed"}, number {@code "0"}). So an invoice the answer shows
     * issued is never read as a refusal, whatever the top level says, and one it shows refused is never read as
     * done - SUCCESS only ever means "wait for the look-up" here ({@code SepayEInvoiceProvider.query}), and nothing is
     * recorded ISSUED from a tracking answer: only a look-up with a number and the tax authority's code does
     * ({@link #state}, {@link #issued}). The invoice-level status is a code, not words: nothing here reads its
     * message.</p>
     *
     * @throws ProviderUnavailableException when the answer says nothing (a 5xx, a 401 after a fresh token, a 429)
     */
    static Tracking tracking(int status, JsonNode body) {
        if (status == 401 || status == 408 || status == 429 || status >= 500) {
            throw new ProviderUnavailableException("SePay eInvoice did not answer the tracking (HTTP " + status + ")");
        }
        JsonNode data = body == null ? null : body.get("data");
        if (status < 200 || status >= 300 || data == null || !data.isObject()) {
            return Tracking.NONE;
        }
        String invoiceState = text(data.get("invoice"), "status");
        invoiceState = invoiceState == null ? "" : invoiceState.toLowerCase(Locale.ROOT);
        if (ISSUED.contains(invoiceState)) {
            // Held at SePay: waited for until the look-up shows its number (Q-5), never a refusal.
            return Tracking.SUCCESS;
        }
        String state = text(data, "status");
        state = state == null ? "" : state.toLowerCase(Locale.ROOT);
        if ("failed".equals(state) && unconfirmed(trackingMessage(body))) {
            // Still the one case read from words (Q-5, accepted): "not confirmed" is never taken for a refusal.
            return Tracking.UNCONFIRMED;
        }
        if (REFUSED.contains(invoiceState)) {
            // The invoice itself failed, whatever the request's own status says (T-8): nothing issued under it.
            return Tracking.FAILED;
        }
        return switch (state) {
            case "pending" -> Tracking.PENDING;
            case "success" -> Tracking.SUCCESS;
            case "failed" -> Tracking.FAILED;
            default -> Tracking.NONE;
        };
    }

    /**
     * Whether a {@code Failed} tracking's message says SePay does not know the result rather than that the invoice was
     * refused. Without words it cannot be told from the unconfirmed one, so it is taken for it: never for a refusal.
     */
    static boolean unconfirmed(String message) {
        if (message == null || message.isBlank()) {
            return true;
        }
        String plain = MARKS.matcher(Normalizer.normalize(message, Normalizer.Form.NFD)).replaceAll("")
                .replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT);
        String words = SPACES.matcher(plain).replaceAll(" ").strip();
        return UNCONFIRMED.matcher(words).find();
    }

    /** The tracking's {@code data.message}: why it failed; null when it says nothing. */
    static String trackingMessage(JsonNode body) {
        JsonNode data = body == null ? null : body.get("data");
        return text(data, "message");
    }

    /**
     * The invoices left in the plan ({@code GET /v1/usage}: {@code data.quota_remaining}, a string in the
     * documentation's sample, developer.sepay.vn/vi/einvoice-api/v1/kiem-tra-han-ngach); null when the answer has no
     * such number.
     *
     * @throws ProviderUnavailableException when the answer says nothing
     */
    static Long quotaRemaining(int status, JsonNode body) {
        if (status < 200 || status >= 300) {
            throw new ProviderUnavailableException("SePay eInvoice did not tell its quota (HTTP " + status + ")");
        }
        JsonNode data = body == null ? null : body.get("data");
        String value = text(data, "quota_remaining");
        if (value == null || !value.matches("-?[0-9]{1,12}")) {
            return null;
        }
        return Long.parseLong(value);
    }

    /**
     * Whether an account's details ({@code GET /v1/provider-accounts/{id}}, {@code data.templates[]} with
     * {@code template_code} and {@code invoice_series}, developer.sepay.vn/vi/einvoice-api/v1/chi-tiet-tai-khoan) list
     * the template code and the symbol; null when they list no templates at all.
     *
     * @throws ProviderUnavailableException when the answer says nothing
     */
    static Boolean offersSeries(int status, JsonNode body, String templateCode, String symbol) {
        if (status < 200 || status >= 300) {
            throw new ProviderUnavailableException("SePay eInvoice did not show the account (HTTP " + status + ")");
        }
        JsonNode data = body == null ? null : body.get("data");
        JsonNode templates = data == null ? null : data.get("templates");
        if (templates == null || !templates.isArray() || templates.isEmpty()) {
            return null;
        }
        for (JsonNode template : templates) {
            if (templateCode.equals(text(template, "template_code"))
                    && symbol.equals(text(template, "invoice_series"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Where an invoice looked up by its reference stands. ISSUED here is a candidate: {@link #issued} completes it;
     * REJECTED is one of {@link #REFUSED}.
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
            // Issued at SePay and not numbered yet: SePay holds it, so it is waited for (Q-5), never "cannot say".
            return invoiceNumber(data) != null ? ResultState.ISSUED : ResultState.PENDING;
        }
        if (REFUSED.contains(state)) {
            return ResultState.REJECTED;
        }
        return WORKING.contains(state) ? ResultState.PENDING : ResultState.UNKNOWN;
    }

    /**
     * Whether a look-up's {@code data} names the invoice issued (or corrected since), number or not: SePay holds it, so
     * no tracking word may turn it into a refusal ({@code SepayEInvoiceProvider.query}).
     */
    static boolean saysIssued(JsonNode data) {
        String state = text(data, "status");
        return state != null && ISSUED.contains(state.toLowerCase(Locale.ROOT));
    }

    /**
     * An issued invoice as the system records it, or null while it lacks the tax authority's code. It carries the
     * series SePay says it numbered the invoice under ({@code template_code}, {@code invoice_series}; WF-05 BE-30): an
     * adjustment or a replacement is sent naming no series, and SePay picks one (sandbox run T-4: {@code C26TSE}, the
     * original's), so what is recorded is checked against what SePay says rather than assumed.
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
                signedAt != null ? signedAt : now, null, null)
                .numberedUnder(text(data, "template_code"), text(data, "invoice_series"));
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
