package com.stockflow.payment.internal.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An installment a bank transfer's content names (15 §4.2 step 5): the contract's number and the installment's place,
 * as {@link Installment#transferReference} writes it and the VietQR code fills it in - {@code HD-20261108-000007-1 DOT2},
 * or {@code ... FEE1} for a fee item.
 *
 * <p>Read the way banks hand it back, not the way it was written: upper or lower case, the dashes kept, turned into
 * spaces or dropped, other words before and after it ({@code MBVCB.1234.HD-...DOT2.CT tu ...}). With every separator
 * dropped, the order number's sequence (six digits or more) and the contract's own count run together
 * ({@code 0000071}); each split of the run is a {@link #contractNumbers() candidate}, and the installments that exist
 * decide which one was meant.</p>
 *
 * @param contractNumbers the contract numbers the content may mean, one unless the separators were dropped
 * @param seq             the installment's seq in its contract: its place in the plan, or the fee item's seq
 */
public record TransferReference(List<String> contractNumbers, int seq) {

    /** Letters and digits only, upper case: every other character is a separator. */
    private static final Pattern NOT_ALNUM = Pattern.compile("[^A-Z0-9]+");
    /**
     * HD, the contract's day, the order's sequence and the contract's count, then DOT or FEE and the number. The count is
     * either after a separator or run together with the sequence ({@code \d{7,}}): split later.
     */
    private static final Pattern REFERENCE = Pattern.compile(
            "HD ?(\\d{8}) ?(\\d{6,} \\d{1,3}|\\d{7,}) ?(DOT|FEE) ?(\\d{1,2})(?!\\d)");
    private static final int MAX_COUNT_DIGITS = 3;

    public TransferReference {
        contractNumbers = List.copyOf(contractNumbers);
    }

    /**
     * Every installment {@code text} names, in the order they appear, each once; empty when it names none.
     *
     * @param text a transfer's content, or SePay's payment code; null reads as nothing
     */
    public static List<TransferReference> read(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String plain = Normalizer.normalize(text.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT);
        String spaced = NOT_ALNUM.matcher(plain).replaceAll(" ").strip();
        Set<TransferReference> found = new LinkedHashSet<>();
        Matcher m = REFERENCE.matcher(spaced);
        while (m.find()) {
            List<String> numbers = contractNumbers(m.group(1), m.group(2));
            int n = Integer.parseInt(m.group(4));
            if (numbers.isEmpty() || n < 1) {
                continue;
            }
            int seq = "FEE".equals(m.group(3)) ? Installment.FIRST_FEE_SEQ + n - 1 : n;
            if ("FEE".equals(m.group(3)) ? seq > Installment.LAST_FEE_SEQ : seq >= Installment.FIRST_FEE_SEQ) {
                continue;
            }
            found.add(new TransferReference(numbers, seq));
        }
        return List.copyOf(found);
    }

    /** {@code HD-<day>-<sequence>-<count>} for each way {@code run} splits into a sequence and a count. */
    private static List<String> contractNumbers(String day, String run) {
        List<String> numbers = new ArrayList<>();
        int space = run.indexOf(' ');
        if (space > 0) {
            numbers.add(number(day, run.substring(0, space), run.substring(space + 1)));
            return numbers;
        }
        for (int split = 6; split < run.length(); split++) {
            String count = run.substring(split);
            if (count.length() <= MAX_COUNT_DIGITS && count.charAt(0) != '0') {
                numbers.add(number(day, run.substring(0, split), count));
            }
        }
        return numbers;
    }

    private static String number(String day, String sequence, String count) {
        return "HD-" + day + "-" + sequence + "-" + Integer.parseInt(count);
    }
}
