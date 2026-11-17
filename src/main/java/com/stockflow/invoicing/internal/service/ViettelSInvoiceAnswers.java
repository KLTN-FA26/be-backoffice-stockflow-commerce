package com.stockflow.invoicing.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.ProviderUnavailableException;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.Result;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.ResultState;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.Submission;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.SubmissionOutcome;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * <b>UNVERIFIED MAPPING - read this before trusting it with a real account.</b>
 *
 * <p>What SInvoice's answers <i>mean</i> for the port: issued, refused, still working, never received, no answer. The
 * field names read here are in Viettel's public description and in Odoo's module. What is <b>not</b> in any public
 * source, and is therefore decided here and only here, is marked {@code ASSUMPTION} below:</p>
 * <ol>
 *   <li><b>The error-code table.</b> The public copies of the description have the heading ("Danh sách lỗi trả về") and
 *       no table. So no error code is interpreted: only <i>that</i> there is one, and the HTTP status.</li>
 *   <li><b>What an unknown transaction id looks like</b> in {@code searchInvoiceByTransactionUuid}. Odoo takes "no
 *       {@code result}" as "never received"; the description shows no sample. Only the cleanest form of it is taken
 *       for "never received" here.</li>
 *   <li><b>When the tax authority's code is there</b>, and what a refusal by the tax authority looks like. The
 *       description shows {@code codeOfTax}, {@code exchangeStatus}, {@code exchangeDes} on a look-up and defines no
 *       values for the last two.</li>
 *   <li><b>The form of {@code invoiceNo}</b>: the samples show the symbol followed by the number
 *       ({@code K21DTT4}, and the pre-2022 {@code AB/19E0000522}).</li>
 * </ol>
 *
 * <p>Each assumption errs towards <i>not knowing</i>: an answer that cannot be read is "no answer" or "unknown", and
 * the invoice is asked about again; nothing here turns an unclear answer into "refused" (which lets the accountant
 * issue again), "never received" (which sends the invoice again) or "issued". When Viettel's test account and its full description are at hand, this class is the one to correct -
 * the client, the payload and the provider do not interpret.</p>
 */
final class ViettelSInvoiceAnswers {

    /** The largest file taken out of a ZIP: an invoice's XML is kilobytes; this is a bound, not an expectation. */
    private static final int MAX_FILE_BYTES = 20 * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 50;
    private static final Pattern TRAILING_NUMBER = Pattern.compile("(\\d{1,8})$");

    private ViettelSInvoiceAnswers() {
    }

    /**
     * The answer to {@code createInvoice}.
     *
     * <p>Documented: on success {@code errorCode} and {@code description} are null and {@code result} carries
     * {@code invoiceNo} and {@code reservationCode}; an error is {@code {"code": 400, "message": ..., "data": ...}} or a
     * non-null {@code errorCode}.</p>
     *
     * <p>ASSUMPTION 1: an error that is SInvoice's <i>decision</i> - a 4xx with an error body, or a 2xx with an
     * {@code errorCode} - means no invoice was created: REJECTED. Everything else that is not a success (5xx, 401/403,
     * 408, 429, a body that is not JSON, a 2xx without an invoice number) is <i>no answer</i>: SInvoice may hold the
     * invoice, and the look-up tells.</p>
     *
     * @throws ProviderUnavailableException for no answer
     */
    static Submission submission(int status, JsonNode body) {
        String error = errorCode(body);
        if (status >= 200 && status < 300) {
            if (error != null) {
                return new Submission(SubmissionOutcome.REJECTED, error, errorMessage(body));
            }
            if (text(first(body == null ? null : body.get("result")), "invoiceNo") != null) {
                // SInvoice answers a transaction id it already holds with the invoice it made the first time, so a
                // repeat cannot be told from a first submission here; either way exactly one invoice exists.
                return new Submission(SubmissionOutcome.ACCEPTED, null, null);
            }
            throw new ProviderUnavailableException("Viettel SInvoice answered a submission without an invoice number");
        }
        if (decision(status) && error != null) {
            return new Submission(SubmissionOutcome.REJECTED, error, errorMessage(body));
        }
        throw new ProviderUnavailableException("Viettel SInvoice answered a submission with HTTP " + status);
    }

    /**
     * The answer to {@code searchInvoiceByTransactionUuid}.
     *
     * <p>Documented: {@code result} is a list; its entry has {@code invoiceNo}, {@code reservationCode},
     * {@code issueDate} (milliseconds) and, once the tax authority gave its code, {@code codeOfTax}.</p>
     *
     * <p>ASSUMPTION 2: NOT_FOUND is only a clean "nothing": a 2xx with no error code and a {@code result} that is there
     * and empty (null or an empty list; Odoo: "no result" is "not sent yet"). NOT_FOUND is what lets the caller send the
     * transaction again, and the accountant send the invoice as a new transaction, so anything less clear - a 4xx, an
     * error code with no entry, no {@code result} at all - is UNKNOWN: the invoice is asked about again later. Taking an
     * error ("session expired", "tax code not registered") for "never received" would send an invoice SInvoice may well
     * hold.</p>
     *
     * <p>ASSUMPTION 3: an entry without {@code codeOfTax} is PENDING, whatever {@code exchangeStatus} and
     * {@code exchangeDes} say. An invoice the tax authority refused still exists at SInvoice under its number, so it is
     * not the port's REJECTED ("no invoice exists"); it stays waiting and a person settles it on SInvoice's portal.
     * {@link #exchangeTrouble} says whether the entry reports trouble, for the log.</p>
     *
     * @throws ProviderUnavailableException for no answer
     */
    static Result result(int status, JsonNode body) {
        boolean answered = status >= 200 && status < 300 || decision(status);
        if (!answered || body == null) {
            throw new ProviderUnavailableException("Viettel SInvoice answered a look-up with HTTP " + status);
        }
        JsonNode result = body.get("result");
        JsonNode entry = first(result);
        if (entry == null) {
            boolean empty = result != null && (result.isNull() || result.isArray() && result.isEmpty());
            String error = errorCode(body);
            if (status >= 200 && status < 300 && error == null && empty) {
                return Result.of(ResultState.NOT_FOUND);
            }
            return new Result(ResultState.UNKNOWN, null, null, null, null, error, errorMessage(body));
        }
        String invoiceNo = text(entry, "invoiceNo");
        String taxAuthorityCode = text(entry, "codeOfTax");
        String lookupCode = text(entry, "reservationCode");
        JsonNode issueDate = entry.get("issueDate");
        if (invoiceNo == null || taxAuthorityCode == null || lookupCode == null || issueDate == null
                || !issueDate.canConvertToLong()) {
            return Result.of(ResultState.PENDING);
        }
        return new Result(ResultState.ISSUED, number(invoiceNo), taxAuthorityCode, lookupCode,
                Instant.ofEpochMilli(issueDate.asLong()), null, null);
    }

    /** The invoice number as SInvoice writes it ({@code invoiceNo} of the look-up's entry); null when there is none. */
    static String invoiceNo(JsonNode body) {
        return text(first(body == null ? null : body.get("result")), "invoiceNo");
    }

    /** Whether a look-up's entry reports trouble exchanging the invoice with the tax authority ({@code exchangeDes}). */
    static boolean exchangeTrouble(JsonNode body) {
        JsonNode entry = first(body == null ? null : body.get("result"));
        return text(entry, "codeOfTax") == null && text(entry, "exchangeDes") != null;
    }

    /**
     * ASSUMPTION 4: the number is the digits {@code invoiceNo} ends with - what follows the symbol, which ends in
     * letters ({@code C26TAA15} → 15; {@code AB/19E0000522} → 522).
     *
     * @throws ProviderUnavailableException when it ends in no number of 1 to 8 digits above zero: guessing a number
     *         onto an issued invoice is worse than asking again
     */
    static int number(String invoiceNo) {
        Matcher digits = TRAILING_NUMBER.matcher(invoiceNo.strip());
        if (digits.find()) {
            int number = Integer.parseInt(digits.group(1));
            if (number > 0) {
                return number;
            }
        }
        throw new ProviderUnavailableException("Viettel SInvoice gave an invoice number that cannot be read");
    }

    /** The symbol {@code invoiceNo} begins with: what precedes the number. */
    static String symbol(String invoiceNo) {
        String stripped = invoiceNo.strip();
        Matcher digits = TRAILING_NUMBER.matcher(stripped);
        return digits.find() ? stripped.substring(0, digits.start()) : stripped;
    }

    /**
     * The file of {@code getInvoiceRepresentationFile}: {@code fileToBytes}, base64. Documented: SInvoice may answer
     * with an empty file for a few seconds after issuing ("gọi sau 2-5 giây"); that is null here, and the caller asks
     * again.
     *
     * @throws ProviderUnavailableException when the answer is an error or not a file
     */
    static byte[] file(int status, JsonNode body) {
        if (status < 200 || status >= 300 || body == null || errorCode(body) != null) {
            throw new ProviderUnavailableException("Viettel SInvoice did not hand the invoice's file over (HTTP "
                    + status + ")");
        }
        String encoded = text(body, "fileToBytes");
        if (encoded == null) {
            return null;
        }
        try {
            return Base64.getMimeDecoder().decode(encoded);
        } catch (IllegalArgumentException notBase64) {
            throw new ProviderUnavailableException("Viettel SInvoice handed over a file that cannot be read");
        }
    }

    /**
     * The invoice's XML out of the ZIP SInvoice hands over: the first {@code .xml} entry, looked for one ZIP deeper too
     * (Odoo's module finds it in a nested archive on some accounts). Bounded, because an archive is input.
     *
     * @throws ProviderUnavailableException when there is none
     */
    static byte[] xmlOf(byte[] zip) {
        byte[] xml = xmlOf(zip, true);
        if (xml == null) {
            throw new ProviderUnavailableException("Viettel SInvoice's archive holds no XML");
        }
        return xml;
    }

    private static byte[] xmlOf(byte[] zip, boolean lookInside) {
        byte[] nested = null;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            int entries = 0;
            while ((entry = in.getNextEntry()) != null && entries++ < MAX_ZIP_ENTRIES) {
                String name = entry.getName().toLowerCase(java.util.Locale.ROOT);
                if (entry.isDirectory() || !(name.endsWith(".xml") || name.endsWith(".zip"))) {
                    continue;
                }
                byte[] bytes = bounded(in);
                if (name.endsWith(".xml")) {
                    return bytes;
                }
                if (nested == null) {
                    nested = bytes;
                }
            }
        } catch (IOException notZip) {
            throw new ProviderUnavailableException("Viettel SInvoice's archive cannot be read");
        }
        return nested != null && lookInside ? xmlOf(nested, false) : null;
    }

    private static byte[] bounded(ZipInputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            if (out.size() + read > MAX_FILE_BYTES) {
                throw new IOException("An archive entry is larger than an invoice can be");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ the error envelope

    /**
     * A 4xx that is SInvoice refusing what was asked, as opposed to refusing the caller (401, 403), giving up (408) or
     * asking for patience (429) - none of which says anything about the invoice.
     */
    private static boolean decision(int status) {
        return status >= 400 && status < 500 && status != 401 && status != 403 && status != 408 && status != 429;
    }

    /**
     * The error an answer names: {@code errorCode} (the documented envelope), else {@code message} or {@code code} of
     * the other envelope the description shows ({@code {"code": 400, "message": "BAD_REQUEST_..."}}), else
     * {@code error}; null when it names none. At most 64 characters, the width it is stored in.
     */
    static String errorCode(JsonNode body) {
        if (body == null || !body.isObject()) {
            return null;
        }
        String code = text(body, "errorCode");
        if (code == null && (body.hasNonNull("code") || body.hasNonNull("error"))) {
            code = text(body, "message");
            if (code == null || code.length() > 64 || code.contains(" ")) {
                code = text(body, "code") != null ? text(body, "code") : text(body, "error");
            }
        }
        return code == null ? null : code.length() <= 64 ? code : code.substring(0, 64);
    }

    /** SInvoice's own words about an error, for the accountant; never more than the invoice's rejection message holds. */
    private static String errorMessage(JsonNode body) {
        String words = text(body, "description");
        if (words == null) {
            words = text(body, "data") != null ? text(body, "data") : text(body, "message");
        }
        return words == null ? null : words.length() <= 500 ? words : words.substring(0, 500);
    }

    /** {@code result} is a list on a look-up and an object on a submission: its first entry, or itself. */
    private static JsonNode first(JsonNode result) {
        if (result == null || result.isNull()) {
            return null;
        }
        if (result.isArray()) {
            return result.isEmpty() || !result.get(0).isObject() ? null : result.get(0);
        }
        return result.isObject() ? result : null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field) || node.get(field).isContainerNode()) {
            return null;
        }
        String value = node.get(field).asText().strip();
        return value.isEmpty() ? null : value;
    }
}
